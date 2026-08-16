package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.EpochReason;
import cn.cubegarden.icbm.caching.protocol.InvalidateReason;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.source.backend.BackendControlServer;
import cn.cubegarden.icbm.caching.source.backend.BackendControlSession;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制服务远端 sink（里程碑 3：源服 velocity 授信模式，master 主动连接）。
 *
 * <p>源服侧监听 backend 端口（{@link BackendControlServer}，PSK = velocity forwarding
 * secret）；master 连接并 AUTH 后，源服发 HELLO（role=SOURCE + 指纹）→ HELLO_ACK →
 * ACTIVE，按 CACHE_PROTOCOL.md 快照消息顺序发送 WORLD_EPOCH → INVALIDATE → PUT_SNAPSHOT，
 * 并以 master 的 PUT_SNAPSHOT_ACK 驱动提交回调（服务端在校验并持久化后原子写
 * committedRevision）。
 *
 * <p>一致性（草稿 backend-velocity-trust §3 / phase2-control-protocol §4.3）：
 * <ul>
 *   <li>单 outbox 线程保序发送，连接中断时队列保留、重连后自动重发（INVALIDATE 幂等
 *       max、PUT 由服务端 required/hash 重放守卫保证幂等）；</li>
 *   <li>{@link #available()} == 会话已握手；不可用时发布器 begin 失败（fail closed）；</li>
 *   <li>put/commit 回调在 backend 会话线程触发（发布器状态机为线程安全显式状态机）。</li>
 * </ul>
 */
public final class RemoteSnapshotSink implements SnapshotSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteSnapshotSink.class);

    private final BackendControlServer server;
    private final Map<ChunkKey, Long> pendingRequired = new ConcurrentHashMap<>();     // 未确认的最高 required（重发用）
    /** 未 ack 的 put（保序重发）。ArrayDeque 由 outbox 线程与 backend reader 线程并发访问
     *  （A7 审计修复：putSnapshot 主线程入队 / handlePutAck reader 出队——锁保护）。 */
    private final Map<ChunkKey, ArrayDeque<PendingPut>> pendingPuts = new ConcurrentHashMap<>();
    private final Map<Long, PendingPut> awaitingAck = new ConcurrentHashMap<>();       // requestId → put
    private final Map<ChunkKey, Long> serverCommitted = new ConcurrentHashMap<>();     // 服务端已确认 committed
    /** 等待 committed 确认的回调（主线程 add / reader 线程 drain——锁保护，A7 审计修复）。 */
    private final Map<ChunkKey, List<CommitCallback>> waitingCommits = new ConcurrentHashMap<>();
    private final Map<DimensionRef, Long> knownEpochs = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong(1);
    private final LinkedBlockingQueue<Runnable> outbox = new LinkedBlockingQueue<>();
    private final Thread outboxThread;

    private volatile BackendControlSession session;
    private volatile boolean connected;
    private volatile boolean closed;

    private record PendingPut(ChunkSnapshot snapshot, PutCallback done) {
    }

    public RemoteSnapshotSink(
            String bindHost,
            int bindPort,
            String forwardingSecret,
            String sourceServerId,
            String serverName,
            String minecraftVersion,
            int runtimeProtocol,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String modpackFingerprint,
            String registryAndDatapackFingerprint
    ) {
        BackendControlSession.HelloConfig helloConfig = new BackendControlSession.HelloConfig(
                sourceServerId, serverName, minecraftVersion, runtimeProtocol,
                fabricLoaderVersion, fabricApiVersion, modpackFingerprint,
                registryAndDatapackFingerprint);
        this.server = new BackendControlServer(bindHost, bindPort, forwardingSecret, helloConfig,
                new ConnectionListener());
        this.outboxThread = new Thread(this::outboxLoop, "icbm-source-outbox");
        this.outboxThread.setDaemon(true);
    }

    public void start() throws Exception {
        this.server.start();
        this.outboxThread.start();
    }

    /** backend 实际监听端口（配置 0 时用于测试）。 */
    public int port() {
        return this.server.port();
    }

    @Override
    public boolean available() {
        return this.connected;
    }

    @Override
    public void raiseRequiredRevision(ChunkKey key, long revision) {
        this.pendingRequired.merge(key, revision, Math::max);
        this.outbox.add(() -> this.sendInvalidate(key, revision));
    }

    @Override
    public void putSnapshot(ChunkSnapshot snapshot, PutCallback done) {
        PendingPut pending = new PendingPut(snapshot, done);
        synchronized (this.pendingPuts) {
            this.pendingPuts.computeIfAbsent(snapshot.key(), ignored -> new ArrayDeque<>()).addLast(pending);
        }
        this.outbox.add(() -> this.sendPut(pending));
    }

    @Override
    public void commitRevision(ChunkKey key, long revision, CommitCallback done) {
        Long committed = this.serverCommitted.get(key);
        if (committed != null && committed >= revision) {
            done.onCommitted(key, revision, true); // 服务端已随 PUT 原子提交
            return;
        }
        synchronized (this.waitingCommits) {
            this.waitingCommits.computeIfAbsent(key, ignored -> new ArrayList<>()).add(done);
        }
    }

    @Override
    public void announceEpoch(DimensionRef dimension, long epoch, String reason, String registryFingerprint,
                              Messages.SpawnPoint spawn) {
        this.knownEpochs.put(dimension, epoch);
        this.outbox.add(() -> this.sendWorldEpoch(dimension, epoch, reason, registryFingerprint, spawn));
    }

    @Override
    public boolean sendOperationResult(cn.cubegarden.icbm.caching.protocol.MessageType type, byte[] body) {
        BackendControlSession s = this.session;
        if (s == null || !s.isActive()) {
            return false;
        }
        s.send(type, 0, 0, body);
        return true;
    }

    @Override
    public void close() {
        this.closed = true;
        this.server.close();
        BackendControlSession s = this.session;
        if (s != null) {
            s.close();
        }
        this.outboxThread.interrupt();
    }

    // ---- 发送（outbox 线程串行，保证顺序） ----

    private void sendInvalidate(ChunkKey key, long revision) {
        BackendControlSession s = this.session;
        if (s == null) {
            return;
        }
        long worldEpoch = this.knownEpochs.getOrDefault(
                new DimensionRef(key.dimension().getNamespace(), key.dimension().getPath()), 0L);
        s.send(MessageType.INVALIDATE, 0, worldEpoch,
                cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.encode(
                        MessageType.INVALIDATE,
                        new Messages.Invalidate(
                                new DimensionRef(key.dimension().getNamespace(), key.dimension().getPath()),
                                key.x(), key.z(), revision, InvalidateReason.BLOCK_STATE)));
    }

    private void sendPut(PendingPut pending) {
        BackendControlSession s = this.session;
        if (s == null) {
            return;
        }
        ChunkSnapshot snapshot = pending.snapshot();
        long requestId = this.requestIds.incrementAndGet();
        this.awaitingAck.put(requestId, pending);
        DimensionRef dimension = new DimensionRef(
                snapshot.key().dimension().getNamespace(), snapshot.key().dimension().getPath());
        s.send(MessageType.PUT_SNAPSHOT, requestId, snapshot.epoch(),
                cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.encode(
                        MessageType.PUT_SNAPSHOT,
                        new Messages.PutSnapshot(dimension, snapshot.key().x(), snapshot.key().z(),
                                snapshot.revision(), snapshot.snapshotFormat(), snapshot.compression(),
                                snapshot.uncompressedLength(), snapshot.payloadHash(), snapshot.payload())));
    }

    private void sendWorldEpoch(DimensionRef dimension, long epoch, String reason, String fingerprint,
                                Messages.SpawnPoint spawn) {
        BackendControlSession s = this.session;
        if (s == null) {
            return;
        }
        s.send(MessageType.WORLD_EPOCH, 0, epoch,
                cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.encode(
                        MessageType.WORLD_EPOCH,
                        new Messages.WorldEpoch(dimension, epoch, mapReason(reason), fingerprint, spawn)));
    }

    private static EpochReason mapReason(String reason) {
        try {
            return EpochReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return EpochReason.ADMIN;
        }
    }

    private void outboxLoop() {
        while (!this.closed) {
            try {
                Runnable op = this.outbox.take();
                // 会话就绪才发送；中断时队列保留（重连后自动补发）
                while (!this.connected && !this.closed) {
                    Thread.sleep(50);
                }
                if (this.closed) {
                    return;
                }
                op.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- backend 连接回调（BackendControlServer 线程） ----

    /** 新会话（AUTH 通过，未握手）：替换旧会话并注入回调。 */
    private final class ConnectionListener implements BackendControlServer.ConnectionListener {
        @Override
        public void onSession(BackendControlSession newSession) {
            BackendControlSession old = RemoteSnapshotSink.this.session;
            if (old != null) {
                old.close();
            }
            RemoteSnapshotSink.this.session = newSession;
            RemoteSnapshotSink.this.connected = false;
            newSession.setListener(new SessionListener());
        }

        @Override
        public void onAuthRejected(String reason) {
            RemoteSnapshotSink.this.connected = false;
            LOGGER.error("[icbm-source] backend AUTH 被拒（fail closed）: {}", reason);
        }
    }

    private final class SessionListener implements BackendControlSession.Listener {
        @Override
        public void onConnected() {
            RemoteSnapshotSink.this.connected = true;
            // 重连后补发（首次连接时 pending 为空，无副作用）：
            // INVALIDATE 幂等（required 仅增），PUT 由服务端 required/hash 重放守卫幂等
            RemoteSnapshotSink.this.awaitingAck.clear();
            for (Map.Entry<ChunkKey, Long> entry : RemoteSnapshotSink.this.pendingRequired.entrySet()) {
                RemoteSnapshotSink.this.outbox.add(
                        () -> RemoteSnapshotSink.this.sendInvalidate(entry.getKey(), entry.getValue()));
            }
            for (Map.Entry<ChunkKey, ArrayDeque<PendingPut>> entry : RemoteSnapshotSink.this.pendingPuts.entrySet()) {
                for (PendingPut pending : entry.getValue()) {
                    RemoteSnapshotSink.this.outbox.add(() -> RemoteSnapshotSink.this.sendPut(pending));
                }
            }
            LOGGER.info("[icbm-source] backend 已连接（HELLO_ACK accepted）");
        }

        @Override
        public void onRejected(String reason) {
            RemoteSnapshotSink.this.connected = false;
            LOGGER.error("[icbm-source] backend HELLO 被拒（fail closed）: {}", reason);
        }

        @Override
        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
            if (type == MessageType.PUT_SNAPSHOT_ACK) {
                RemoteSnapshotSink.this.handlePutAck(envelope, body);
            } else if (type == MessageType.ERROR) {
                LOGGER.error("[icbm-source] backend 错误: {}",
                        cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.<Messages.ErrorMessage>decode(
                                MessageType.ERROR, body).reason());
            } else if (type == MessageType.SUBMIT_OPERATION) {
                cn.cubegarden.icbm.caching.source.SourceRuntime runtime =
                        cn.cubegarden.icbm.caching.source.SourceRuntime.current();
                if (runtime != null) {
                    runtime.operationQueue().enqueue(
                            cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.decode(
                                    MessageType.SUBMIT_OPERATION, body));
                } else {
                    LOGGER.error("[icbm-source] 操作丢弃（运行时未装配，fail closed）");
                }
            } else if (type == MessageType.GET_OPERATION_STATUS) {
                cn.cubegarden.icbm.caching.source.SourceRuntime runtime =
                        cn.cubegarden.icbm.caching.source.SourceRuntime.current();
                if (runtime != null) {
                    Messages.OperationStatusRes res = runtime.operationQueue().query(
                            cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.decode(
                                    MessageType.GET_OPERATION_STATUS, body));
                    RemoteSnapshotSink.this.sendOperationResult(MessageType.OPERATION_STATUS_RES,
                            cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.encode(
                                    MessageType.OPERATION_STATUS_RES, res));
                }
            } else if (type == MessageType.CHUNK_MATERIALIZE_REQ) {
                // 8.2 按需物化：slave 请求 → 源服主线程生成并发布（结果经快照管道回流）
                cn.cubegarden.icbm.caching.source.SourceRuntime runtime =
                        cn.cubegarden.icbm.caching.source.SourceRuntime.current();
                if (runtime != null) {
                    runtime.materialize().requestFromChannel(
                            cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.decode(
                                    MessageType.CHUNK_MATERIALIZE_REQ, body));
                } else {
                    LOGGER.error("[icbm-source] 物化请求丢弃（运行时未装配，fail closed）");
                }
            } else if (type == MessageType.CHAT_RELAY) {
                // 9.3 聊天直传：slave 玩家聊天 → 主线程以玩家身份权威广播（源服控制台/玩家
                // 可见）→ 回传 CHAT_BROADCAST 供 master 分发所有 slave。
                cn.cubegarden.icbm.caching.source.SourceRuntime runtime =
                        cn.cubegarden.icbm.caching.source.SourceRuntime.current();
                if (runtime == null) {
                    LOGGER.error("[icbm-source] 聊天丢弃（运行时未装配，fail closed）");
                    return;
                }
                net.minecraft.server.MinecraftServer server = runtime.materialize().server();
                if (server == null) {
                    LOGGER.warn("[icbm-source] 聊天丢弃（服务器未就绪，fail closed）");
                    return;
                }
                Messages.ChatRelay relay = cn.cubegarden.icbm.caching.protocol.client
                        .EncapsulatedChannelClient.decode(MessageType.CHAT_RELAY, body);
                server.execute(() -> this.broadcastRelay(server, relay));
            }
        }

        /** 源服权威广播（Server thread）：unsigned 消息（v1 不保留签名链，secure chat parity）
         *  + ChatType.bind(ChatType.CHAT, registryAccess, name)（ChatType.java:75，源服无
         *  slave 玩家实体，用纯文本显示名）+ broadcastChatMessage(CommandSourceStack 版，
         *  PlayerList.java:734，sender.getPlayer()=null 无副作用）；广播后回传 CHAT_BROADCAST。 */
        private void broadcastRelay(net.minecraft.server.MinecraftServer server, Messages.ChatRelay relay) {
            java.util.UUID senderUuid = new java.util.UUID(
                    java.nio.ByteBuffer.wrap(relay.senderUuid()).getLong(),
                    java.nio.ByteBuffer.wrap(relay.senderUuid()).getLong(8));
            net.minecraft.network.chat.PlayerChatMessage pm =
                    net.minecraft.network.chat.PlayerChatMessage.unsigned(senderUuid, relay.message());
            net.minecraft.network.chat.ChatType.Bound bound = net.minecraft.network.chat.ChatType.bind(
                    net.minecraft.network.chat.ChatType.CHAT,
                    server.registryAccess(),
                    net.minecraft.network.chat.Component.literal(relay.senderName()));
            server.getPlayerList().broadcastChatMessage(pm, server.createCommandSourceStack(), bound);
            RemoteSnapshotSink.this.sendOperationResult(MessageType.CHAT_BROADCAST,
                    cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.encode(
                            MessageType.CHAT_BROADCAST,
                            new Messages.ChatBroadcast(relay.senderUuid(), relay.senderName(), relay.message())));
            LOGGER.info("[icbm-source] 权威聊天广播: {}: {}", relay.senderName(), relay.message());
        }

        @Override
        public void onDisconnected(Throwable cause) {
            RemoteSnapshotSink.this.connected = false;
            LOGGER.warn("[icbm-source] backend 断开（outbox 保留重发）: {}",
                    cause == null ? "?" : cause.getMessage());
        }
    }

    private void handlePutAck(Envelope envelope, byte[] body) {
        Messages.PutSnapshotAck ack = cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient.decode(
                MessageType.PUT_SNAPSHOT_ACK, body);
        PendingPut pending = this.awaitingAck.remove(envelope.requestId());
        if (pending == null) {
            LOGGER.warn("[icbm-source] 收到未知 PUT_SNAPSHOT_ACK: requestId={}", envelope.requestId());
            return;
        }
        ChunkKey key = pending.snapshot().key();
        if (ack.result() == cn.cubegarden.icbm.caching.protocol.PutResult.OK) {
            this.serverCommitted.put(key, ack.revision());
            synchronized (this.pendingPuts) {
                ArrayDeque<PendingPut> puts = this.pendingPuts.get(key);
                if (puts != null) {
                    puts.removeFirstOccurrence(pending); // 已提交的出队（后续快照保序）
                }
            }
            pending.done().onResult(key, ack.revision(), SnapshotSink.PutResult.OK);
            List<CommitCallback> waiters;
            synchronized (this.waitingCommits) {
                waiters = this.waitingCommits.remove(key);
            }
            if (waiters != null) {
                for (CommitCallback cb : waiters) {
                    cb.onCommitted(key, ack.revision(), true);
                }
            }
        } else {
            pending.done().onResult(key, ack.revision(), map(ack.result()));
        }
    }

    private static SnapshotSink.PutResult map(cn.cubegarden.icbm.caching.protocol.PutResult result) {
        return switch (result) {
            case OK -> SnapshotSink.PutResult.OK;
            case REJECTED_OUT_OF_ORDER -> SnapshotSink.PutResult.REJECTED_OUT_OF_ORDER;
            case REJECTED_EPOCH_MISMATCH -> SnapshotSink.PutResult.REJECTED_EPOCH_MISMATCH;
            case REJECTED_HASH_MISMATCH -> SnapshotSink.PutResult.REJECTED_HASH_MISMATCH;
            case REJECTED_SIZE_MISMATCH -> SnapshotSink.PutResult.REJECTED_SIZE_MISMATCH;
            case REJECTED_UNSUPPORTED -> SnapshotSink.PutResult.REJECTED_UNSUPPORTED;
            case REJECTED_UNAVAILABLE -> SnapshotSink.PutResult.UNAVAILABLE;
        };
    }
}

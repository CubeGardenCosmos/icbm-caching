package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.EpochReason;
import cn.cubegarden.icbm.caching.protocol.InvalidateReason;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.ControlChannel;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制服务远端 sink（阶段2 默认落点）：按 CACHE_PROTOCOL.md 快照消息顺序发送
 * WORLD_EPOCH → INVALIDATE → PUT_SNAPSHOT，并以服务端 PUT_SNAPSHOT_ACK 驱动
 * 提交回调（服务端在校验并持久化后原子写 committedRevision）。
 *
 * <p>一致性（草稿 phase2-control-protocol §4.3）：
 * <ul>
 *   <li>单 outbox 线程保序发送，连接中断时队列保留、重连后自动重发（INVALIDATE 幂等
 *       max、PUT 由服务端 required/hash 重放守卫保证幂等）；</li>
 *   <li>{@link #available()} == 连接已握手；不可用时发布器 begin 失败（fail closed）；</li>
 *   <li>put/commit 回调在控制通道线程触发（发布器状态机为线程安全显式状态机）。</li>
 * </ul>
 */
public final class RemoteSnapshotSink implements SnapshotSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteSnapshotSink.class);

    private final ControlChannel channel;
    private final Map<ChunkKey, Long> pendingRequired = new ConcurrentHashMap<>();     // 未确认的最高 required（重发用）
    private final Map<ChunkKey, ArrayDeque<PendingPut>> pendingPuts = new ConcurrentHashMap<>(); // 未 ack 的 put（保序重发）
    private final Map<Long, PendingPut> awaitingAck = new ConcurrentHashMap<>();       // requestId → put
    private final Map<ChunkKey, Long> serverCommitted = new ConcurrentHashMap<>();     // 服务端已确认 committed
    private final Map<ChunkKey, List<CommitCallback>> waitingCommits = new ConcurrentHashMap<>();
    private final Map<DimensionRef, Long> knownEpochs = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong(1);
    private final LinkedBlockingQueue<Runnable> outbox = new LinkedBlockingQueue<>();
    private final Thread outboxThread;
    private volatile boolean connected;
    private volatile boolean closed;

    private record PendingPut(ChunkSnapshot snapshot, PutCallback done) {
    }

    public RemoteSnapshotSink(
            String host,
            int port,
            String sourceServerId,
            String minecraftVersion,
            int runtimeProtocol,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String modpackFingerprint,
            String registryAndDatapackFingerprint,
            SSLContext sslContext,
            long readTimeoutMillis,
            long reconnectBackoffMillis
    ) {
        this.channel = new ControlChannel(host, port, sourceServerId,
                new ControlChannel.HelloConfig(Role.SOURCE, minecraftVersion, runtimeProtocol,
                        fabricLoaderVersion, fabricApiVersion, modpackFingerprint,
                        registryAndDatapackFingerprint, (byte) 0),
                sslContext, readTimeoutMillis, reconnectBackoffMillis, true, new ChannelListener());
        this.outboxThread = new Thread(this::outboxLoop, "icbm-source-outbox");
        this.outboxThread.setDaemon(true);
    }

    public void start() {
        this.channel.start();
        this.outboxThread.start();
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
        this.pendingPuts.computeIfAbsent(snapshot.key(), ignored -> new ArrayDeque<>()).addLast(pending);
        this.outbox.add(() -> this.sendPut(pending));
    }

    @Override
    public void commitRevision(ChunkKey key, long revision, CommitCallback done) {
        Long committed = this.serverCommitted.get(key);
        if (committed != null && committed >= revision) {
            done.onCommitted(key, revision, true); // 服务端已随 PUT 原子提交
            return;
        }
        this.waitingCommits.computeIfAbsent(key, ignored -> new ArrayList<>()).add(done);
    }

    @Override
    public void announceEpoch(DimensionRef dimension, long epoch, String reason, String registryFingerprint) {
        this.knownEpochs.put(dimension, epoch);
        this.outbox.add(() -> this.sendWorldEpoch(dimension, epoch, reason, registryFingerprint));
    }

    @Override
    public void close() {
        this.closed = true;
        this.channel.close();
        this.outboxThread.interrupt();
    }

    // ---- 发送（outbox 线程串行，保证顺序） ----

    private void sendInvalidate(ChunkKey key, long revision) {
        long worldEpoch = this.knownEpochs.getOrDefault(
                new DimensionRef(key.dimension().getNamespace(), key.dimension().getPath()), 0L);
        this.channel.send(MessageType.INVALIDATE, 0, worldEpoch,
                ControlChannel.encode(MessageType.INVALIDATE,
                        new Messages.Invalidate(
                                new DimensionRef(key.dimension().getNamespace(), key.dimension().getPath()),
                                key.x(), key.z(), revision, InvalidateReason.BLOCK_STATE)));
    }

    private void sendPut(PendingPut pending) {
        ChunkSnapshot snapshot = pending.snapshot();
        long requestId = this.requestIds.incrementAndGet();
        this.awaitingAck.put(requestId, pending);
        DimensionRef dimension = new DimensionRef(
                snapshot.key().dimension().getNamespace(), snapshot.key().dimension().getPath());
        this.channel.send(MessageType.PUT_SNAPSHOT, requestId, snapshot.epoch(),
                ControlChannel.encode(MessageType.PUT_SNAPSHOT,
                        new Messages.PutSnapshot(dimension, snapshot.key().x(), snapshot.key().z(),
                                snapshot.revision(), snapshot.snapshotFormat(), snapshot.compression(),
                                snapshot.uncompressedLength(), snapshot.payloadHash(), snapshot.payload())));
    }

    private void sendWorldEpoch(DimensionRef dimension, long epoch, String reason, String fingerprint) {
        this.channel.send(MessageType.WORLD_EPOCH, 0, epoch,
                ControlChannel.encode(MessageType.WORLD_EPOCH,
                        new Messages.WorldEpoch(dimension, epoch, mapReason(reason), fingerprint)));
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
                // 连接就绪才发送；中断时队列保留（重连后自动补发）
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

    // ---- 入站（控制通道线程） ----

    private final class ChannelListener implements ControlChannel.Listener {
        @Override
        public void onConnected(String serverId) {
            RemoteSnapshotSink.this.connected = true;
            // 重连后补发（首次连接时 pending 为空，无副作用）：
            // INVALIDATE 幂等（required 仅增），PUT 由服务端 required/hash 重放守卫幂等
            RemoteSnapshotSink.this.awaitingAck.clear();
            for (Map.Entry<ChunkKey, Long> entry : RemoteSnapshotSink.this.pendingRequired.entrySet()) {
                RemoteSnapshotSink.this.outbox.add(() -> RemoteSnapshotSink.this.sendInvalidate(entry.getKey(), entry.getValue()));
            }
            for (Map.Entry<ChunkKey, ArrayDeque<PendingPut>> entry : RemoteSnapshotSink.this.pendingPuts.entrySet()) {
                for (PendingPut pending : entry.getValue()) {
                    RemoteSnapshotSink.this.outbox.add(() -> RemoteSnapshotSink.this.sendPut(pending));
                }
            }
            LOGGER.info("[icbm-source] 控制通道已连接: {}", serverId);
        }

        @Override
        public void onRejected(String reason) {
            RemoteSnapshotSink.this.connected = false;
            LOGGER.error("[icbm-source] HELLO 被拒（fail closed）: {}", reason);
        }

        @Override
        public void onDisconnected(Throwable cause) {
            RemoteSnapshotSink.this.connected = false;
            LOGGER.warn("[icbm-source] 控制通道断开（outbox 保留重发）: {}", cause == null ? "?" : cause.getMessage());
        }

        @Override
        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
            if (type == MessageType.PUT_SNAPSHOT_ACK) {
                handlePutAck(envelope, body);
            } else if (type == MessageType.ERROR) {
                LOGGER.error("[icbm-source] 控制服务错误: {}",
                        ControlChannel.<Messages.ErrorMessage>decode(MessageType.ERROR, body).reason());
            }
        }
    }

    private void handlePutAck(Envelope envelope, byte[] body) {
        Messages.PutSnapshotAck ack = ControlChannel.decode(MessageType.PUT_SNAPSHOT_ACK, body);
        PendingPut pending = this.awaitingAck.remove(envelope.requestId());
        if (pending == null) {
            LOGGER.warn("[icbm-source] 收到未知 PUT_SNAPSHOT_ACK: requestId={}", envelope.requestId());
            return;
        }
        ChunkKey key = pending.snapshot().key();
        if (ack.result() == cn.cubegarden.icbm.caching.protocol.PutResult.OK) {
            this.serverCommitted.put(key, ack.revision());
            ArrayDeque<PendingPut> puts = this.pendingPuts.get(key);
            if (puts != null) {
                puts.removeFirstOccurrence(pending); // 已提交的出队（后续快照保序）
            }
            pending.done().onResult(key, ack.revision(), SnapshotSink.PutResult.OK);
            List<CommitCallback> waiters = this.waitingCommits.remove(key);
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

package cn.cubegarden.icbm.caching.slave.control;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.slave.config.SlaveConfig;
import cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader;
import cn.cubegarden.icbm.caching.slave.world.SpawnRegistry;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * slave 控制平面客户端（ReplicationClient 迁移，草稿 slave-fabric-backend §2.3）：以 SLAVE
 * 角色连接 master 封装通道 → HELLO（指纹 + serverName）→ SUBSCRIBE（ackedSequence）→
 * 接收 WORLD_EPOCH/INVALIDATE/SNAPSHOT_PUSH 应用到本地对象库 → ACK 推进流控窗口。
 *
 * <p>一致性：断线重连以 ackedSequence 重订阅（master 事件日志重放补发）；对象库单调守卫
 * 拒绝乱序/重放；连接状态驱动 {@link SnapshotChunkLoader}（断开即 fail closed）。
 *
 * <p>LOGIN_VERIFY：玩家登录验证材料经 LOGIN_VERIFY 流递交 master，阻塞等 RES
 * （验证在登录线程，不阻塞主线程 tick）；master 返回前不放行。
 */
public final class SlaveControlClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlaveControlClient.class);

    private final EncapsulatedChannelClient channel;
    private final SnapshotObjectStore store;
    private final SnapshotChunkLoader loader;
    private final SpawnRegistry spawnRegistry;
    private final Set<DimensionRef> subscribed;
    private final int ackBatchSize;
    private final AtomicLong requestIds = new AtomicLong(1);
    private final String minecraftVersion;
    private final int runtimeProtocol;
    private final String fabricLoaderVersion;
    private final String fabricApiVersion;
    private final String modpackFingerprint;
    private final String registryAndDatapackFingerprint;

    private volatile long lastAckedSequence;
    private int receivedSinceAck;
    private final Object verifyLock = new Object();
    private CountDownLatch verifyPending;
    private Messages.LoginVerifyRes verifyResult;

    public SlaveControlClient(
            SlaveConfig config,
            SnapshotObjectStore store,
            SnapshotChunkLoader loader,
            SpawnRegistry spawnRegistry,
            String minecraftVersion,
            int runtimeProtocol,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String modpackFingerprint,
            String registryAndDatapackFingerprint
    ) {
        this.store = store;
        this.loader = loader;
        this.spawnRegistry = spawnRegistry;
        this.subscribed = Set.copyOf(config.dimensions().stream()
                .map(id -> {
                    String[] parts = id.split(":", 2);
                    return DimensionRef.of(parts[0], parts.length > 1 ? parts[1] : id);
                }).toList());
        this.ackBatchSize = 16;
        this.minecraftVersion = minecraftVersion;
        this.runtimeProtocol = runtimeProtocol;
        this.fabricLoaderVersion = fabricLoaderVersion;
        this.fabricApiVersion = fabricApiVersion;
        this.modpackFingerprint = modpackFingerprint;
        this.registryAndDatapackFingerprint = registryAndDatapackFingerprint;
        this.channel = new EncapsulatedChannelClient(
                config.masterHost(), config.masterPort(),
                config.serverName(), config.uuidHex(), config.psk(),
                new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, config.serverName(),
                        minecraftVersion, runtimeProtocol, fabricLoaderVersion, fabricApiVersion,
                        modpackFingerprint, registryAndDatapackFingerprint, (byte) 0),
                config.readTimeoutMillis(), config.reconnectBackoffMillis(), true, new ChannelListener());
    }

    public void start() {
        this.channel.start();
    }

    public boolean connected() {
        return this.channel.isConnected();
    }

    /**
     * 登录验证：递交材料（LOGIN_VERIFY_REQ）并阻塞等 master RES（超时/断连 → null，拒登录）。
     * 在登录验证线程调用（不阻塞主线程 tick）。
     */
    public Messages.LoginVerifyRes verifyLogin(Messages.LoginVerifyReq req) {
        CountDownLatch latch;
        synchronized (this.verifyLock) {
            if (!this.channel.sendLoginVerify(EncapsulatedChannelClient.encode(
                    MessageType.LOGIN_VERIFY_REQ, req))) {
                return null; // 未连接：无法证明身份 → 拒登录
            }
            this.verifyResult = null;
            this.verifyPending = new CountDownLatch(1);
            latch = this.verifyPending;
        }
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                LOGGER.error("[icbm-slave] LOGIN_VERIFY 超时（fail closed，拒登录）");
                return null;
            }
            return this.verifyResult;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 玩家登出上报：SESSION_CLOSED（CONTROL 流；master 注销记账）。 */
    /**
     * 权威会话：递交明文 MC 报文（MC_PASSTHROUGH 流，字节透传；CACHE_PROTOCOL §1.1）。
     * 玩家报文捕获/注入随阶段 6/7 端到端（本方法为协议能力，供桥接层调用）。
     */
    public boolean sendMcPassthrough(byte[] body) {
        return this.channel.sendMcPassthrough(body);
    }

    public boolean sendSessionClosed(byte[] playerUuid, String sessionId, String reason) {
        return this.channel.send(MessageType.SESSION_CLOSED, this.requestIds.incrementAndGet(), 0L,
                EncapsulatedChannelClient.encode(MessageType.SESSION_CLOSED,
                        new Messages.SessionClosed(playerUuid, sessionId, reason)));
    }

    private void subscribe() {
        this.channel.send(MessageType.SUBSCRIBE, this.requestIds.incrementAndGet(), 0,
                EncapsulatedChannelClient.encode(MessageType.SUBSCRIBE,
                        new Messages.Subscribe(this.subscribed.toArray(DimensionRef[]::new),
                                true, this.lastAckedSequence)));
        LOGGER.info("[icbm-slave] 订阅: dims={} ackedSeq={}", this.subscribed, this.lastAckedSequence);
    }

    private void ack(long sequence) {
        this.channel.send(MessageType.ACK, this.requestIds.incrementAndGet(), 0,
                EncapsulatedChannelClient.encode(MessageType.ACK, new Messages.Ack(sequence)));
        this.lastAckedSequence = Math.max(this.lastAckedSequence, sequence);
    }

    private void handleInvalidate(Messages.Invalidate msg, long sequence) {
        this.store.raiseRequired(ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ()), msg.revision());
        this.maybeAck(sequence);
    }

    private void handleWorldEpoch(Messages.WorldEpoch msg, long sequence) {
        this.store.announceEpoch(msg.dimension(), msg.worldEpoch());
        this.spawnRegistry.onWorldEpoch(msg.dimension(), msg.spawn());
        this.maybeAck(sequence);
    }

    private void handleSnapshotPush(Messages.SnapshotPush push, Envelope envelope, long sequence) {
        Messages.PutSnapshot snapshot = push.snapshot();
        ChunkRef key = ChunkRef.of(snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ());
        long epoch = envelope.worldEpoch() != 0 ? envelope.worldEpoch()
                : this.store.dimensionEpoch(snapshot.dimension());
        PutResult result = this.store.put(key, snapshot.revision(), epoch,
                snapshot.snapshotFormat(), snapshot.compression(), snapshot.uncompressedLength(),
                snapshot.payloadHash(), snapshot.payload());
        if (result == PutResult.OK) {
            try {
                this.store.commit(key, snapshot.revision());
                LOGGER.debug("[icbm-slave] 已装载快照: {} rev={} epoch={}", key, snapshot.revision(), epoch);
            } catch (IllegalStateException e) {
                LOGGER.error("[icbm-slave] committed 失败（fail closed）: {}: {}", key, e.getMessage());
            }
        } else {
            LOGGER.warn("[icbm-slave] 快照推送被拒（保持 required，不覆盖）: {} rev={} result={}",
                    key, snapshot.revision(), result);
        }
        this.maybeAck(sequence);
    }

    private void maybeAck(long sequence) {
        this.receivedSinceAck++;
        if (this.receivedSinceAck >= this.ackBatchSize) {
            this.receivedSinceAck = 0;
            this.ack(sequence);
        }
    }

    @Override
    public void close() {
        this.channel.close();
        this.loader.setMasterConnected(false);
    }

    private final class ChannelListener implements EncapsulatedChannelClient.Listener {
        @Override
        public void onConnected(String serverId) {
            SlaveControlClient.this.loader.setMasterConnected(true);
            SlaveControlClient.this.subscribe();
            LOGGER.info("[icbm-slave] master 已连接: {}", serverId);
        }

        @Override
        public void onRejected(String reason) {
            SlaveControlClient.this.loader.setMasterConnected(false);
            LOGGER.error("[icbm-slave] HELLO 被拒（fail closed）: {}", reason);
        }

        @Override
        public void onDisconnected(Throwable cause) {
            SlaveControlClient.this.loader.setMasterConnected(false);
            SlaveControlClient.this.wakeVerify(null);
            LOGGER.warn("[icbm-slave] master 断开（服务闸门关闭，重连后重订阅）: {}",
                    cause == null ? "?" : cause.getMessage());
        }

        @Override
        public void onLoginVerifyResponse(byte[] body) {
            try {
                Messages.LoginVerifyRes res = EncapsulatedChannelClient.decode(
                        MessageType.LOGIN_VERIFY_RES, body);
                SlaveControlClient.this.wakeVerify(res);
            } catch (Exception e) {
                LOGGER.error("[icbm-slave] LOGIN_VERIFY_RES 解码失败（fail closed，拒登录）: {}", e.toString());
                SlaveControlClient.this.wakeVerify(null);
            }
        }

        @Override
        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
            try {
                switch (type) {
                    case INVALIDATE -> SlaveControlClient.this.handleInvalidate(
                            EncapsulatedChannelClient.decode(MessageType.INVALIDATE, body), envelope.sequence());
                    case WORLD_EPOCH -> SlaveControlClient.this.handleWorldEpoch(
                            EncapsulatedChannelClient.decode(MessageType.WORLD_EPOCH, body), envelope.sequence());
                    case SNAPSHOT_PUSH -> SlaveControlClient.this.handleSnapshotPush(
                            EncapsulatedChannelClient.decode(MessageType.SNAPSHOT_PUSH, body), envelope, envelope.sequence());
                    case HEARTBEAT -> SlaveControlClient.this.ack(envelope.sequence());
                    case ERROR -> LOGGER.error("[icbm-slave] master 错误: {}",
                            EncapsulatedChannelClient.<Messages.ErrorMessage>decode(MessageType.ERROR, body).reason());
                    default -> LOGGER.warn("[icbm-slave] 忽略类型: {}", type);
                }
            } catch (Exception e) {
                LOGGER.error("[icbm-slave] 处理推送失败（fail closed，保持现有状态）: {}", e.toString());
            }
        }
    }

    private void wakeVerify(Messages.LoginVerifyRes res) {
        CountDownLatch latch;
        synchronized (this.verifyLock) {
            this.verifyResult = res;
            latch = this.verifyPending;
        }
        if (latch != null) {
            latch.countDown();
        }
    }
}

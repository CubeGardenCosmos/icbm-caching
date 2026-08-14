package cn.cubegarden.icbm.caching.virtual.control;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.ControlChannel;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.virtual.world.SnapshotRepository;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 虚拟后端控制平面客户端（CACHE_PROTOCOL.md，阶段2 消息复用）：以 VIRTUAL_BACKEND 角色
 * 连接 master → HELLO（源指纹）→ SUBSCRIBE（ackedSequence）→ 接收 WORLD_EPOCH /
 * INVALIDATE / SNAPSHOT_PUSH 应用到本地对象库 → ACK 推进流控窗口。
 *
 * <p>一致性：断线重连以 ackedSequence 重订阅（master 事件日志重放补发）；对象库单调守卫
 * 拒绝乱序/重放；连接状态驱动 {@link SnapshotRepository}（断开即 fail closed）。
 */
public final class ReplicationClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationClient.class);

    private final ControlChannel channel;
    private final SnapshotObjectStore store;
    private final SnapshotRepository repository;
    private final Set<DimensionRef> subscribed;
    private final int ackBatchSize;
    private final AtomicLong requestIds = new AtomicLong(1);

    private volatile long lastAckedSequence;
    private int receivedSinceAck;

    public ReplicationClient(
            String host,
            int port,
            String serverId,
            String minecraftVersion,
            int runtimeProtocol,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String modpackFingerprint,
            String registryAndDatapackFingerprint,
            SSLContext sslContext,
            long readTimeoutMillis,
            long reconnectBackoffMillis,
            SnapshotObjectStore store,
            SnapshotRepository repository,
            DimensionRef[] dimensions,
            int ackBatchSize
    ) {
        this.store = store;
        this.repository = repository;
        this.subscribed = Set.copyOf(Arrays.asList(dimensions));
        this.ackBatchSize = Math.max(1, ackBatchSize);
        this.channel = new ControlChannel(host, port, serverId,
                new ControlChannel.HelloConfig(Role.VIRTUAL_BACKEND, minecraftVersion, runtimeProtocol,
                        fabricLoaderVersion, fabricApiVersion, modpackFingerprint,
                        registryAndDatapackFingerprint, (byte) 0),
                sslContext, readTimeoutMillis, reconnectBackoffMillis, true, new ChannelListener());
    }

    public void start() {
        this.channel.start();
    }

    public boolean connected() {
        return this.channel.isConnected();
    }

    @Override
    public void close() {
        this.channel.close();
        this.repository.setMasterConnected(false);
    }

    private void subscribe() {
        this.channel.send(MessageType.SUBSCRIBE, this.requestIds.incrementAndGet(), 0,
                ControlChannel.encode(MessageType.SUBSCRIBE,
                        new Messages.Subscribe(this.subscribed.toArray(DimensionRef[]::new),
                                true, this.lastAckedSequence)));
        LOGGER.info("[vb] 订阅: dims={} ackedSeq={}", this.subscribed, this.lastAckedSequence);
    }

    private void ack(long sequence) {
        this.channel.send(MessageType.ACK, this.requestIds.incrementAndGet(), 0,
                ControlChannel.encode(MessageType.ACK, new Messages.Ack(sequence)));
        this.lastAckedSequence = Math.max(this.lastAckedSequence, sequence);
    }

    private void handleInvalidate(Messages.Invalidate msg, long sequence) {
        this.store.raiseRequired(ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ()), msg.revision());
        this.maybeAck(sequence);
    }

    private void handleWorldEpoch(Messages.WorldEpoch msg, long sequence) {
        this.store.announceEpoch(msg.dimension(), msg.worldEpoch());
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
                LOGGER.debug("[vb] 已装载快照: {} rev={} epoch={}", key, snapshot.revision(), epoch);
            } catch (IllegalStateException e) {
                LOGGER.error("[vb] committed 失败（fail closed）: {}: {}", key, e.getMessage());
            }
        } else {
            LOGGER.warn("[vb] 快照推送被拒（保持 required，不覆盖）: {} rev={} result={}",
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

    private final class ChannelListener implements ControlChannel.Listener {
        @Override
        public void onConnected(String serverId) {
            ReplicationClient.this.repository.setMasterConnected(true);
            ReplicationClient.this.subscribe();
            LOGGER.info("[vb] master 已连接: {}", serverId);
        }

        @Override
        public void onRejected(String reason) {
            ReplicationClient.this.repository.setMasterConnected(false);
            LOGGER.error("[vb] HELLO 被拒（fail closed）: {}", reason);
        }

        @Override
        public void onDisconnected(Throwable cause) {
            ReplicationClient.this.repository.setMasterConnected(false);
            LOGGER.warn("[vb] master 断开（服务闸门关闭，重连后重订阅）: {}",
                    cause == null ? "?" : cause.getMessage());
        }

        @Override
        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
            try {
                switch (type) {
                    case INVALIDATE -> ReplicationClient.this.handleInvalidate(
                            ControlChannel.decode(MessageType.INVALIDATE, body), envelope.sequence());
                    case WORLD_EPOCH -> ReplicationClient.this.handleWorldEpoch(
                            ControlChannel.decode(MessageType.WORLD_EPOCH, body), envelope.sequence());
                    case SNAPSHOT_PUSH -> ReplicationClient.this.handleSnapshotPush(
                            ControlChannel.decode(MessageType.SNAPSHOT_PUSH, body), envelope, envelope.sequence());
                    case HEARTBEAT -> ReplicationClient.this.ack(envelope.sequence());
                    case ERROR -> LOGGER.error("[vb] master 错误: {}",
                            ControlChannel.<Messages.ErrorMessage>decode(MessageType.ERROR, body).reason());
                    default -> LOGGER.warn("[vb] 忽略类型: {}", type);
                }
            } catch (Exception e) {
                LOGGER.error("[vb] 处理推送失败（fail closed，保持现有状态）: {}", e.toString());
            }
        }
    }
}

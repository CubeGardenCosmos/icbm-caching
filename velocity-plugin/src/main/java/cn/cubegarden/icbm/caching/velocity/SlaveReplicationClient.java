package cn.cubegarden.icbm.caching.velocity;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.InvalidateReason;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.ControlChannel;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * slave 复制客户端（HIERARCHY.md §5，阶段2 控制平面；Velocity 路由接线为阶段4）：
 * 连接 master → HELLO（SLAVE 角色 + 源指纹）→ SUBSCRIBE（ackedSequence）→
 * 接收 WORLD_EPOCH/INVALIDATE/SNAPSHOT_PUSH 并应用到本地对象库 → ACK 推进流控窗口。
 *
 * <p>一致性：
 * <ul>
 *   <li>断线重连后以已确认的 ackedSequence 重订阅（master 按事件日志重放补发）；</li>
 *   <li>推送乱序/重放由对象库单调守卫拒绝（不降低 revision、不覆盖新对象）；</li>
 *   <li>连接状态驱动 {@link ServeGate}（断开即拒绝服务，fail closed）。</li>
 * </ul>
 */
public final class SlaveReplicationClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlaveReplicationClient.class);

    private final ControlChannel channel;
    private final SnapshotObjectStore store;
    private final ServeGate gate;
    private final Set<DimensionRef> subscribed;
    private final int ackBatchSize;
    private final AtomicLong requestIds = new AtomicLong(1);

    private volatile long lastAckedSequence;
    private int receivedSinceAck;

    public SlaveReplicationClient(
            String host,
            int port,
            String slaveServerId,
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
            ServeGate gate,
            DimensionRef[] dimensions,
            int ackBatchSize
    ) {
        this.store = store;
        this.gate = gate;
        this.subscribed = Set.copyOf(Arrays.asList(dimensions));
        this.ackBatchSize = Math.max(1, ackBatchSize);
        this.channel = new ControlChannel(host, port, slaveServerId,
                new ControlChannel.HelloConfig(Role.SLAVE, slaveServerId, minecraftVersion, runtimeProtocol,
                        fabricLoaderVersion, fabricApiVersion, modpackFingerprint,
                        registryAndDatapackFingerprint, (byte) 0),
                sslContext, readTimeoutMillis, reconnectBackoffMillis, true, new ChannelListener());
    }

    public void start() {
        this.channel.start();
    }

    public long lastAckedSequence() {
        return this.lastAckedSequence;
    }

    public boolean connected() {
        return this.channel.isConnected();
    }

    @Override
    public void close() {
        this.channel.close();
        this.gate.setMasterConnected(false);
    }

    private void subscribe() {
        this.channel.send(MessageType.SUBSCRIBE, this.requestIds.incrementAndGet(), 0,
                ControlChannel.encode(MessageType.SUBSCRIBE,
                        new Messages.Subscribe(this.subscribed.toArray(DimensionRef[]::new),
                                true, this.lastAckedSequence)));
        LOGGER.info("[icbm-slave] 订阅: dims={} ackedSeq={}", this.subscribed, this.lastAckedSequence);
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
        long epoch = envelope.worldEpoch() != 0 ? envelope.worldEpoch() : this.store.dimensionEpoch(snapshot.dimension());
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

    private final class ChannelListener implements ControlChannel.Listener {
        @Override
        public void onConnected(String serverId) {
            SlaveReplicationClient.this.gate.setMasterConnected(true);
            SlaveReplicationClient.this.subscribe();
            LOGGER.info("[icbm-slave] master 已连接: {}", serverId);
        }

        @Override
        public void onRejected(String reason) {
            SlaveReplicationClient.this.gate.setMasterConnected(false);
            LOGGER.error("[icbm-slave] HELLO 被拒（fail closed）: {}", reason);
        }

        @Override
        public void onDisconnected(Throwable cause) {
            SlaveReplicationClient.this.gate.setMasterConnected(false);
            LOGGER.warn("[icbm-slave] master 断开（服务闸门关闭，重连后重订阅）: {}",
                    cause == null ? "?" : cause.getMessage());
        }

        @Override
        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
            try {
                switch (type) {
                    case INVALIDATE -> SlaveReplicationClient.this.handleInvalidate(
                            ControlChannel.decode(MessageType.INVALIDATE, body), envelope.sequence());
                    case WORLD_EPOCH -> SlaveReplicationClient.this.handleWorldEpoch(
                            ControlChannel.decode(MessageType.WORLD_EPOCH, body), envelope.sequence());
                    case SNAPSHOT_PUSH -> SlaveReplicationClient.this.handleSnapshotPush(
                            ControlChannel.decode(MessageType.SNAPSHOT_PUSH, body), envelope, envelope.sequence());
                    case HEARTBEAT -> SlaveReplicationClient.this.ack(envelope.sequence()); // 保活应答
                    case ERROR -> LOGGER.error("[icbm-slave] master 错误: {}",
                            ControlChannel.<Messages.ErrorMessage>decode(MessageType.ERROR, body).reason());
                    default -> LOGGER.warn("[icbm-slave] 忽略类型: {}", type);
                }
            } catch (Exception e) {
                LOGGER.error("[icbm-slave] 处理推送失败（fail closed，保持现有状态）: {}", e.toString());
            }
        }
    }
}

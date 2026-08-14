package cn.cubegarden.icbm.caching.control.replication;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.PeerSession;
import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.EpochReason;
import cn.cubegarden.icbm.caching.protocol.InvalidateReason;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * master→slave 复制器（HIERARCHY.md §5；草稿 phase2-control-protocol §4.2）：
 * 订阅、事件日志重放（断线补发）、窗口 ACK 流控、滞后检测。
 *
 * <p>推送序号 = EventLog 事件序号（跨连接单调）；slave 以 ACK(ackedSequence) 推进窗口；
 * 滞后判定：lastAckTime 超过 lagThreshold → 标记 LAGGING（记录日志，推送自然被窗口限流）。
 */
public final class Replicator {

    private static final Logger LOGGER = LoggerFactory.getLogger(Replicator.class);

    /** 单个 slave 订阅状态（随连接生命周期，无全局可变状态）。 */
    public static final class SlaveSubscription {
        final PeerSession session;
        final Set<DimensionRef> dimensions;
        volatile long ackedSequence;
        volatile long lastReceivedSequence;
        volatile long lastAckTime;
        volatile boolean lagging;
        final ArrayDeque<EventLog.Event> pending = new ArrayDeque<>();

        SlaveSubscription(PeerSession session, Set<DimensionRef> dimensions, long ackedSequence) {
            this.session = session;
            this.dimensions = dimensions;
            this.ackedSequence = ackedSequence;
            this.lastReceivedSequence = ackedSequence;
            this.lastAckTime = System.currentTimeMillis();
        }

        boolean interested(EventLog.Event event) {
            return this.dimensions.isEmpty() || this.dimensions.contains(event.dimension());
        }
    }

    private final ConcurrentMap<String, SlaveSubscription> subscriptions = new ConcurrentHashMap<>();
    private final SnapshotObjectStore store;
    private final EventLog eventLog;
    private final ServiceConfig config;

    public Replicator(SnapshotObjectStore store, EventLog eventLog, ServiceConfig config) {
        this.store = store;
        this.eventLog = eventLog;
        this.config = config;
    }

    /** SUBSCRIBE：注册订阅并重放 ackedSequence 之后的事件（断线补发/epoch/失效全部经日志）。 */
    public void subscribe(PeerSession session, Messages.Subscribe msg) {
        Set<DimensionRef> dimensions = new HashSet<>(Arrays.asList(msg.dimensions()));
        SlaveSubscription sub = new SlaveSubscription(session, dimensions, msg.ackedSequence());
        this.subscriptions.put(session.sourceServerId(), sub);
        if (msg.pushEnabled()) {
            List<EventLog.Event> replay = this.eventLog.replayAfter(msg.ackedSequence());
            for (EventLog.Event event : replay) {
                this.sendEvent(sub, event);
            }
            LOGGER.info("[control] slave 订阅: {} dims={} ackedSeq={} 重放 {} 条",
                    session.sourceServerId(), dimensions, msg.ackedSequence(), replay.size());
        }
    }

    public void onAck(PeerSession session, Messages.Ack ack) {
        SlaveSubscription sub = this.subscriptions.get(session.sourceServerId());
        if (sub == null) {
            return;
        }
        sub.ackedSequence = Math.max(sub.ackedSequence, ack.ackedSequence());
        sub.lastAckTime = System.currentTimeMillis();
        if (sub.lagging && sub.ackedSequence >= sub.lastReceivedSequence) {
            sub.lagging = false;
            LOGGER.info("[control] slave {} 恢复健康", session.sourceServerId());
        }
        this.flushPending(sub);
    }

    public void onWorldEpoch(Messages.WorldEpoch msg, long seq) {
        this.broadcast(new EventLog.Event(seq, EventLog.EventType.WORLD_EPOCH, msg.dimension(), null,
                msg.worldEpoch(), "", msg.registryAndDatapackFingerprint()));
    }

    public void onInvalidate(Messages.Invalidate msg, long seq) {
        this.broadcast(new EventLog.Event(seq, EventLog.EventType.INVALIDATE, msg.dimension(),
                ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ()), msg.revision(), "", ""));
    }

    /** PUT_SNAPSHOT 提交后：向订阅 slave 推送已提交对象（窗口流控）。 */
    public void onCommitted(ChunkRef key, long revision, String payloadHash, long seq) {
        this.broadcast(new EventLog.Event(seq, EventLog.EventType.SNAPSHOT_COMMITTED, key.dimension(),
                key, revision, payloadHash, ""));
    }

    /** 周期心跳：空闲连接保活 + 滞后检测（超过 lagThreshold 未收到 ACK → LAGGING）。 */
    public void heartbeatTick() {
        long now = System.currentTimeMillis();
        for (SlaveSubscription sub : this.subscriptions.values()) {
            PeerSession session = sub.session;
            if (!session.isActive()) {
                continue;
            }
            if (!sub.lagging && now - sub.lastAckTime > this.config.lagThresholdMillis()) {
                sub.lagging = true;
                LOGGER.warn("[control] slave {} 滞后：acked={} lastSent={}（超过 {}ms 无 ACK）",
                        session.sourceServerId(), sub.ackedSequence, sub.lastReceivedSequence,
                        this.config.lagThresholdMillis());
            }
            session.sendHeartbeat();
        }
    }

    public void onDisconnected(PeerSession session) {
        this.subscriptions.remove(session.sourceServerId());
    }

    public Map<String, SlaveSubscription> subscriptions() {
        return this.subscriptions;
    }

    private void broadcast(EventLog.Event event) {
        for (SlaveSubscription sub : this.subscriptions.values()) {
            if (sub.interested(event)) {
                this.sendEvent(sub, event);
            }
        }
    }

    private void sendEvent(SlaveSubscription sub, EventLog.Event event) {
        if (!sub.session.isActive()) {
            return;
        }
        long inFlight = sub.lastReceivedSequence - sub.ackedSequence;
        if (inFlight >= this.config.pushWindow()) {
            sub.pending.addLast(event); // 窗口满：排队等待 ACK 推进（流控）
            return;
        }
        this.writeEvent(sub, event);
    }

    private void flushPending(SlaveSubscription sub) {
        while (!sub.pending.isEmpty()) {
            long inFlight = sub.lastReceivedSequence - sub.ackedSequence;
            if (inFlight >= this.config.pushWindow()) {
                return;
            }
            this.writeEvent(sub, sub.pending.removeFirst());
        }
    }

    private void writeEvent(SlaveSubscription sub, EventLog.Event event) {
        PeerSession session = sub.session;
        MessageType type;
        Object body;
        switch (event.type()) {
            case WORLD_EPOCH -> {
                type = MessageType.WORLD_EPOCH;
                body = new Messages.WorldEpoch(event.dimension(), event.revision(),
                        EpochReason.ADMIN, event.fingerprint());
            }
            case INVALIDATE -> {
                type = MessageType.INVALIDATE;
                body = new Messages.Invalidate(event.dimension(), event.chunk().x(),
                        event.chunk().z(), event.revision(), InvalidateReason.UNKNOWN);
            }
            case SNAPSHOT_COMMITTED -> {
                byte[] payload = this.store.loadCommittedPayload(event.chunk());
                if (payload == null) {
                    LOGGER.error("[control] 推送失败：对象缺失 {} rev={}", event.chunk(), event.revision());
                    return;
                }
                type = MessageType.SNAPSHOT_PUSH;
                body = new Messages.SnapshotPush(new Messages.PutSnapshot(
                        event.dimension(), event.chunk().x(), event.chunk().z(), event.revision(),
                        ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                        ProtocolConstants.COMPRESSION_GZIP,
                        this.store.committedUncompressedLength(event.chunk()),
                        event.payloadHash(), payload
                ));
            }
            default -> {
                return;
            }
        }
        session.sendWithSequence(type, 0, event.seq(), encode(type, body));
        sub.lastReceivedSequence = Math.max(sub.lastReceivedSequence, event.seq());
    }

    private static byte[] encode(MessageType type, Object message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            MessageBodyCodec.encode(type, out, message);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("push encode failed", e);
        }
    }
}

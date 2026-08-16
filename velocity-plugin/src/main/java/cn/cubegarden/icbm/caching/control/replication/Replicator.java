package cn.cubegarden.icbm.caching.control.replication;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.ControlSession;
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
        /** 流控排队上限（A7 审计修复：slave 卡死不 ACK → pending 无限增长；超限视为失联
         *  断开由调用方处理——fail closed，不静默丢事件）。 */
        static final int PENDING_LIMIT = 4096;
        final ControlSession session;

        /** 订阅会话（服务层广播聊天下行用；ControlSession 接口发送）。 */
        public ControlSession session() {
            return this.session;
        }
        final Set<DimensionRef> dimensions;
        volatile long ackedSequence;
        volatile long lastReceivedSequence;
        volatile long lastAckTime;
        volatile boolean lagging;
        /** 并发安全队列（源服 reader broadcast 写 / slave reader onAck 读；A7 审计修复）。 */
        final java.util.concurrent.ConcurrentLinkedDeque<EventLog.Event> pending =
                new java.util.concurrent.ConcurrentLinkedDeque<>();

        SlaveSubscription(ControlSession session, Set<DimensionRef> dimensions, long ackedSequence) {
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
    public void subscribe(ControlSession session, Messages.Subscribe msg) {
        Set<DimensionRef> dimensions = new HashSet<>(Arrays.asList(msg.dimensions()));
        SlaveSubscription sub = new SlaveSubscription(session, dimensions, msg.ackedSequence());
        this.subscriptions.put(session.sourceServerId(), sub);
        if (msg.pushEnabled()) {
            List<EventLog.Event> replay = this.eventLog.replayAfter(msg.ackedSequence());
            for (EventLog.Event event : replay) {
                this.sendEvent(sub, event);
            }
            LOGGER.debug("[control] slave 订阅: {} dims={} ackedSeq={} 重放 {} 条",
                    session.sourceServerId(), dimensions, msg.ackedSequence(), replay.size());
        }
    }

    public void onAck(ControlSession session, Messages.Ack ack) {
        SlaveSubscription sub = this.subscriptions.get(session.sourceServerId());
        if (sub == null) {
            return;
        }
        sub.ackedSequence = Math.max(sub.ackedSequence, ack.ackedSequence());
        sub.lastAckTime = System.currentTimeMillis();
        if (sub.lagging && sub.ackedSequence >= sub.lastReceivedSequence) {
            sub.lagging = false;
            LOGGER.debug("[control] slave {} 恢复健康", session.sourceServerId());
        }
        this.flushPending(sub);
    }

    public void onWorldEpoch(Messages.WorldEpoch msg, long seq) {
        this.broadcast(new EventLog.Event(seq, EventLog.EventType.WORLD_EPOCH, msg.dimension(), null,
                msg.worldEpoch(), "", msg.registryAndDatapackFingerprint(), msg.spawn()));
    }

    public void onInvalidate(Messages.Invalidate msg, long seq) {
        this.broadcast(new EventLog.Event(seq, EventLog.EventType.INVALIDATE, msg.dimension(),
                ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ()), msg.revision(), "", "",
                Messages.SpawnPoint.DEFAULT));
    }

    /** PUT_SNAPSHOT 提交后：向订阅 slave 推送已提交对象（窗口流控）。 */
    public void onCommitted(ChunkRef key, long revision, String payloadHash, long seq) {
        this.broadcast(new EventLog.Event(seq, EventLog.EventType.SNAPSHOT_COMMITTED, key.dimension(),
                key, revision, payloadHash, "", Messages.SpawnPoint.DEFAULT));
    }

    /** 周期心跳：空闲连接保活 + 滞后检测（超过 lagThreshold 未收到 ACK → LAGGING）。 */
    public void heartbeatTick() {
        long now = System.currentTimeMillis();
        for (SlaveSubscription sub : this.subscriptions.values()) {
            ControlSession session = sub.session;
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

    public void onDisconnected(ControlSession session) {
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
            if (sub.pending.size() >= SlaveSubscription.PENDING_LIMIT) {
                // 流控排队超限：slave 长时间不 ACK → fail closed（断开，重连重订阅补齐）
                LOGGER.error("[control] slave {} 流控排队超限（{}），断开重订阅（fail closed）",
                        sub.session.sourceServerId(), SlaveSubscription.PENDING_LIMIT);
                sub.session.fail("push window overflow");
                return;
            }
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
        ControlSession session = sub.session;
        MessageType type;
        Object body;
        switch (event.type()) {
            case WORLD_EPOCH -> {
                type = MessageType.WORLD_EPOCH;
                body = new Messages.WorldEpoch(event.dimension(), event.revision(),
                        EpochReason.ADMIN, event.fingerprint(), event.spawn());
            }
            case INVALIDATE -> {
                type = MessageType.INVALIDATE;
                body = new Messages.Invalidate(event.dimension(), event.chunk().x(),
                        event.chunk().z(), event.revision(), InvalidateReason.UNKNOWN);
            }
            case SNAPSHOT_COMMITTED -> {
                // 8.2 重放缺陷修复：必须按事件 revision 取 payload（loadPayload），不能用
                // loadCommittedPayload（当前最新）配旧事件的 event.payloadHash() —— 旧 hash 配
                // 新 payload → slave REJECTED_HASH_MISMATCH 风暴，只有恰好等于最新 revision 的
                // 事件能推送成功。对象库每 revision payload 均落盘（CACHE_PROTOCOL §2）。
                SnapshotObjectStore.Payload payload = this.store.loadPayload(event.chunk(), event.revision());
                if (payload == null) {
                    LOGGER.error("[control] 推送失败：对象缺失 {} rev={}（fail closed，不推旧 hash 新 payload）",
                            event.chunk(), event.revision());
                    return;
                }
                if (!payload.payloadHash().equals(event.payloadHash())) {
                    LOGGER.error("[control] 推送失败：对象哈希与事件不符 {} rev={}（对象库损坏，fail closed）",
                            event.chunk(), event.revision());
                    return;
                }
                type = MessageType.SNAPSHOT_PUSH;
                body = new Messages.SnapshotPush(new Messages.PutSnapshot(
                        event.dimension(), event.chunk().x(), event.chunk().z(), event.revision(),
                        ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                        ProtocolConstants.COMPRESSION_GZIP,
                        payload.uncompressedLength(),
                        payload.payloadHash(), payload.bytes()
                ));
            }
            default -> {
                return;
            }
        }
        session.sendWithSequence(type, 0, epochFor(event), event.seq(), encode(type, body));
        sub.lastReceivedSequence = Math.max(sub.lastReceivedSequence, event.seq());
    }

    /** 事件所属维度的当前 epoch（WORLD_EPOCH 推送携带新 epoch 本身）。 */
    private long epochFor(EventLog.Event event) {
        if (event.type() == EventLog.EventType.WORLD_EPOCH) {
            return event.revision();
        }
        return this.store.dimensionEpoch(event.dimension());
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

package cn.cubegarden.icbm.caching.source.operation;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 源服主线程幂等操作队列（里程碑 7 阶段 5）。
 *
 * <p>约束（AGENTS.md/ARCHITECTURE §5.2）：操作只在源服主线程裁决（END_SERVER_TICK）；
 * operationId 去重幂等（重复提交返回已有终态，不二次执行）；有界终态历史（近似 LRU）
 * 供 GET_OPERATION_STATUS（操作超时不等于失败）；裁决结果回发由
 * {@link cn.cubegarden.icbm.caching.source.snapshot.SnapshotSink#sendOperationResult} 完成
 * （REMOTE=backend 通道；LOCAL=no-op，终态在本地可查询）。
 *
 * <p>成功裁决后：脏标记 + revision 递增（快照管道自然发布新状态，slave 无需 overlay 长久
 * 保留——拒绝/超时才需要 RESYNC 纠正）。
 */
public final class OperationQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationQueue.class);

    /** 每 tick 最多裁决的操作数（不阻塞 tick 热路径；慢操作排队）。 */
    private static final int MAX_PER_TICK = 16;

    /** 终态历史容量（近似 LRU；超限移除最旧，仅影响迟到查询）。 */
    private static final int HISTORY_LIMIT = 1024;

    public interface ResultSender {
        /** 回发操作终态（OPERATION_COMMITTED/REJECTED/STATUS_RES）；返回 false 表示未送达。 */
        boolean send(MessageType type, byte[] body);
    }

    /** 主线程裁决器（可注入：测试用 fake，生产用 {@link OperationAdjudicator}）。 */
    public interface Adjudicator {
        /** 主线程裁决并标记脏。返回终态（Committed 含 canonical state + chunk revision）。 */
        OperationAdjudicator.Result adjudicate(MinecraftServer server, Messages.SubmitOperation op);
    }

    private final Adjudicator adjudicator;
    private final ResultSender sender;

    /** pending（未裁决）按提交序；pending 与 history 都按 operationId 去重。 */
    private final Deque<Messages.SubmitOperation> pending = new ArrayDeque<>();
    private final Map<String, Messages.SubmitOperation> pendingById = new ConcurrentHashMap<>();
    /** 有界近似 LRU（A7 审计修复：主线程写 / backend 会话线程读——LinkedHashMap 非线程安全，
     *  全部访问经 synchronized(history)）。 */
    private final Map<String, Messages.OperationStatusRes> history = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Messages.OperationStatusRes> eldest) {
            return size() > HISTORY_LIMIT;
        }
    };

    public OperationQueue(Adjudicator adjudicator, ResultSender sender) {
        this.adjudicator = adjudicator;
        this.sender = sender;
    }

    /** 入队（backend 会话线程调用；裁决在主线程）。幂等：同一 operationId 重复 → 忽略。 */
    public void enqueue(Messages.SubmitOperation op) {
        String id = hex(op.operationId());
        synchronized (this.history) {
            if (this.history.containsKey(id)) {
                LOGGER.warn("[icbm-source] 重复操作提交（幂等忽略）: op={}", id);
                return;
            }
        }
        if (this.pendingById.putIfAbsent(id, op) != null) {
            LOGGER.warn("[icbm-source] 重复操作提交（已排队）: op={}", id);
            return;
        }
        synchronized (this.pending) {
            this.pending.addLast(op);
        }
    }

    /** 状态查询（任意线程；backend 会话线程）。 */
    public Messages.OperationStatusRes query(Messages.GetOperationStatus req) {
        String id = hex(req.operationId());
        Messages.OperationStatusRes terminal;
        synchronized (this.history) {
            terminal = this.history.get(id);
        }
        if (terminal != null) {
            return terminal;
        }
        if (this.pendingById.containsKey(id)) {
            return new Messages.OperationStatusRes(req.operationId(),
                    Messages.OperationStatusRes.OperationStatus.PENDING, "", 0, "",
                    DimensionRef.of("minecraft", "overworld"), new int[0], new int[0]);
        }
        return new Messages.OperationStatusRes(req.operationId(),
                Messages.OperationStatusRes.OperationStatus.UNKNOWN, "", 0, "unknown-operation",
                DimensionRef.of("minecraft", "overworld"), new int[0], new int[0]);
    }

    /** 主线程裁决（END_SERVER_TICK；每 tick 限 MAX_PER_TICK，不阻塞 tick 热路径）。 */
    public void onEndServerTick(MinecraftServer server) {
        int processed = 0;
        Messages.SubmitOperation op;
        while (processed < MAX_PER_TICK && (op = this.poll()) != null) {
            processed++;
            this.adjudicateAndSend(server, op);
        }
    }

    private Messages.SubmitOperation poll() {
        synchronized (this.pending) {
            Messages.SubmitOperation op = this.pending.pollFirst();
            if (op != null) {
                this.pendingById.remove(hex(op.operationId()));
            }
            return op;
        }
    }

    private void adjudicateAndSend(MinecraftServer server, Messages.SubmitOperation op) {
        String id = hex(op.operationId());
        OperationAdjudicator.Result result = this.adjudicator.adjudicate(server, op);
        Messages.OperationStatusRes terminal;
        if (result instanceof OperationAdjudicator.Committed committed) {
            long chunkRevision = committed.chunkRevision();
            Messages.OperationCommitted committedMsg = new Messages.OperationCommitted(
                    op.operationId(), committed.canonicalState(), chunkRevision,
                    committed.epoch(), System.currentTimeMillis());
            terminal = new Messages.OperationStatusRes(op.operationId(),
                    Messages.OperationStatusRes.OperationStatus.COMMITTED,
                    committed.canonicalState(), chunkRevision, "",
                    op.dimension(), new int[0], new int[0]);
            this.send(MessageType.OPERATION_COMMITTED, committedMsg);
            LOGGER.info("[icbm-source] 操作已提交: op={} type={} pos={},{},{} state={} rev={}",
                    id, op.operationType(), op.posX(), op.posY(), op.posZ(),
                    committed.canonicalState(), chunkRevision);
        } else if (result instanceof OperationAdjudicator.Rejected rejected) {
            int cx = Math.floorDiv(op.posX(), 16);
            int cz = Math.floorDiv(op.posZ(), 16);
            Messages.OperationRejected rejectedMsg = new Messages.OperationRejected(
                    op.operationId(), rejected.reason(), op.dimension(),
                    new int[] {cx}, new int[] {cz}, System.currentTimeMillis());
            terminal = new Messages.OperationStatusRes(op.operationId(),
                    Messages.OperationStatusRes.OperationStatus.REJECTED, "", 0,
                    rejected.reason(), op.dimension(), new int[] {cx}, new int[] {cz});
            this.send(MessageType.OPERATION_REJECTED, rejectedMsg);
            LOGGER.info("[icbm-source] 操作被拒: op={} type={} pos={},{},{} reason={}",
                    id, op.operationType(), op.posX(), op.posY(), op.posZ(), rejected.reason());
        } else {
            throw new IllegalStateException("未知裁决结果: " + result);
        }
        synchronized (this.history) {
            this.history.put(id, terminal);
        }
    }

    private void send(MessageType type, Object body) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.encode(
                    type, new java.io.DataOutputStream(bytes), body);
            this.sender.send(type, bytes.toByteArray());
        } catch (java.io.IOException e) {
            LOGGER.error("[icbm-source] 操作结果编码失败（fail closed，终态保留可查询）: {}", e.toString());
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}

package cn.cubegarden.icbm.caching.source.operation;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.source.materialize.MaterializeService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 源服主线程幂等操作队列（里程碑 7 阶段 5 + 阶段 9.2 快路径，草稿 fast-operation-sync）。
 *
 * <p>约束（AGENTS.md/ARCHITECTURE §5.2）：操作只在源服主线程裁决（END_SERVER_TICK /
 * FULL 回调）；operationId 去重幂等（重复提交返回已有终态，不二次执行）；有界终态历史
 * （近似 LRU）供 GET_OPERATION_STATUS（操作超时不等于失败）；裁决结果回发由
 * {@link cn.cubegarden.icbm.caching.source.snapshot.SnapshotSink#sendOperationResult} 完成。
 *
 * <p>阶段 9.2 快路径：
 * <ul>
 *   <li>F2：终态（Committed/Rejected）后目标 chunk 不再立即释放 force ticket，委托
 *       {@link MaterializeService#hold} 热驻留 30s（PUBLISHED_HOLD）——连续破坏/放置不
 *       再逐 op 重载（NeedsChunk 只发生在卸载期间）；</li>
 *   <li>F3：NeedsChunk 重试从「Deque 每 tick 弹一个」改为「按 chunk 索引 + FULL 加载完成
 *       主线程回调（ChunkMap.onFullChunkStatusChange，ChunkMap.java:1302）同 tick 续裁」；
 *       保留 {@link #MAX_CHUNK_RETRY_TICKS} 上限：END_SERVER_TICK 逐 tick 递增重试计数，
 *       加载迟迟不完成 → chunk-not-loaded 拒绝（fail closed，不无限驻留）；</li>
 *   <li>F4：{@link #pendingChunks} 供快照发布器优先发布含未决操作的区块。</li>
 * </ul>
 */
public final class OperationQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationQueue.class);

    /** 每 tick 最多裁决的操作数（不阻塞 tick 热路径；慢操作排队）。 */
    private static final int MAX_PER_TICK = 16;

    /** 终态历史容量（近似 LRU；超限移除最旧，仅影响迟到查询）。 */
    private static final int HISTORY_LIMIT = 1024;

    /** chunk-not-loaded 重试上限（tick；20s，与物化 GENERATING 超时对齐）——
     *  加载迟迟不完成（物化失败等）→ 拒绝（fail closed，不无限驻留）。 */
    static final int MAX_CHUNK_RETRY_TICKS = 400;

    /** 排队中的操作（带重试计数；NeedsChunk 重试用）。 */
    private record PendingOp(Messages.SubmitOperation op, int retryTicks) {
        PendingOp tick() {
            return new PendingOp(this.op, this.retryTicks + 1);
        }
    }

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
    /** 热驻留服务（F2；null = 测试无物化 → 终态后立即释放，旧语义）。 */
    private final MaterializeService materialize;

    /** pending（未裁决）按提交序；pending 与 history 都按 operationId 去重。 */
    private final Deque<PendingOp> pending = new ArrayDeque<>();
    /** NeedsChunk 重试按 chunk 索引（主线程专用；F3：FULL 回调按 chunk 取出同 tick 续裁；
     *  保留超时上限由 END_SERVER_TICK 递增计数）。 */
    private final Map<Long, List<PendingOp>> retryByChunk = new HashMap<>();
    private final Map<String, PendingOp> pendingById = new ConcurrentHashMap<>();
    /** 本队列为 chunk-not-loaded 重试加过的 setChunkForced ticket（主线程专用；
     *  终态后记录清除——ticket 生命周期移交 MaterializeService 热驻留统一释放）。 */
    private final java.util.Set<Long> forcedTickets = new java.util.HashSet<>();
    /** 含未决（含重试中）操作的 chunk（F4：快照发布优先）；enqueue 线程写 / 主线程终态移除。 */
    private final Set<Long> pendingChunks = ConcurrentHashMap.newKeySet();
    /** 有界近似 LRU（A7 审计修复：主线程写 / backend 会话线程读——LinkedHashMap 非线程安全，
     *  全部访问经 synchronized(history)）。 */
    private final Map<String, Messages.OperationStatusRes> history = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Messages.OperationStatusRes> eldest) {
            return size() > HISTORY_LIMIT;
        }
    };

    public OperationQueue(Adjudicator adjudicator, ResultSender sender) {
        this(adjudicator, sender, null);
    }

    public OperationQueue(Adjudicator adjudicator, ResultSender sender, MaterializeService materialize) {
        this.adjudicator = adjudicator;
        this.sender = sender;
        this.materialize = materialize;
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
        if (this.pendingById.putIfAbsent(id, new PendingOp(op, 0)) != null) {
            LOGGER.warn("[icbm-source] 重复操作提交（已排队）: op={}", id);
            return;
        }
        this.pendingChunks.add(pack(Math.floorDiv(op.posX(), 16), Math.floorDiv(op.posZ(), 16)));
        synchronized (this.pending) {
            this.pending.addLast(new PendingOp(op, 0));
        }
    }

    /** 含未决操作的区块集合（F4：快照发布器优先发布；任意线程读，快照为参考不保证实时）。 */
    public Set<Long> pendingChunks() {
        return this.pendingChunks;
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

    /** 主线程裁决（END_SERVER_TICK；每 tick 限 MAX_PER_TICK，不阻塞 tick 热路径）。
     *  先递增重试计数并超时拒绝（F3 兜底），再处理新入队操作。 */
    public void onEndServerTick(MinecraftServer server) {
        this.lastServer = server;
        this.expireStalledRetries(server);
        int processed = 0;
        PendingOp item;
        while (processed < MAX_PER_TICK && (item = this.poll()) != null) {
            processed++;
            this.adjudicateAndSend(server, item);
        }
    }

    /** NeedsChunk 加载完成回调（F3；ChunkMapFullLoadMixin 经 ChunkMap.onFullChunkStatusChange
     *  FULL 达成时调用，主线程）：取出该 chunk 的全部重试操作**同 tick 续裁**（不再 1 tick/次
     *  轮询；多个操作同 tick 全部裁决）。无重试时 no-op（降级回调等，无害）。 */
    public void onChunkFullLoaded(int chunkX, int chunkZ) {
        List<PendingOp> retries;
        synchronized (this.retryByChunk) {
            retries = this.retryByChunk.remove(pack(chunkX, chunkZ));
        }
        if (retries == null) {
            return;
        }
        // 主线程回调（ChunkMap.onFullChunkStatusChange，mainThreadExecutor）：直接裁决；
        // lastServer 由 onEndServerTick 设置（生产路径服务器运行中恒非 null）
        for (PendingOp item : retries) {
            this.pendingById.remove(hex(item.op().operationId()));
            this.adjudicateAndSend(this.lastServer, item);
        }
    }

    /** 主线程引用（onEndServerTick 设置；FULL 回调在同服务器生命周期内使用）。 */
    private volatile MinecraftServer lastServer;

    private PendingOp poll() {
        synchronized (this.pending) {
            PendingOp item = this.pending.pollFirst();
            if (item != null) {
                this.pendingById.remove(hex(item.op().operationId()));
            }
            return item;
        }
    }

    /** F3 超时兜底：重试项逐 tick 递增计数，≥ MAX 拒绝（fail closed；加载迟迟不完成仍拒绝，
     *  与物化 GENERATING 超时对齐）。 */
    private void expireStalledRetries(MinecraftServer server) {
        if (this.retryByChunk.isEmpty()) {
            return;
        }
        List<Map.Entry<Long, PendingOp>> toReject = new ArrayList<>();
        synchronized (this.retryByChunk) {
            java.util.Iterator<Map.Entry<Long, List<PendingOp>>> it =
                    this.retryByChunk.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, List<PendingOp>> entry = it.next();
                List<PendingOp> updated = new ArrayList<>();
                for (PendingOp item : entry.getValue()) {
                    PendingOp next = item.tick();
                    if (next.retryTicks() >= MAX_CHUNK_RETRY_TICKS) {
                        toReject.add(Map.entry(entry.getKey(), item));
                    } else {
                        updated.add(next);
                    }
                }
                if (updated.isEmpty()) {
                    it.remove();
                } else {
                    entry.setValue(updated);
                }
            }
        }
        for (Map.Entry<Long, PendingOp> rejected : toReject) {
            this.pendingById.remove(hex(rejected.getValue().op().operationId()));
            int cx = Math.floorDiv(rejected.getValue().op().posX(), 16);
            int cz = Math.floorDiv(rejected.getValue().op().posZ(), 16);
            LOGGER.error("[icbm-source] 操作区块加载超时（拒绝，覆盖缺口 fail closed）: op={} chunk={},{}",
                    hex(rejected.getValue().op().operationId()), cx, cz);
            this.terminalRejected(server, rejected.getValue().op(), "chunk-not-loaded");
        }
    }

    private void adjudicateAndSend(MinecraftServer server, PendingOp item) {
        Messages.SubmitOperation op = item.op();
        String id = hex(op.operationId());
        OperationAdjudicator.Result result = this.adjudicator.adjudicate(server, op);
        if (result instanceof OperationAdjudicator.NeedsChunk needs) {
            this.handleNeedsChunk(server, item, needs);
            return;
        }
        // 终态（Committed/Rejected）：热驻留目标 chunk（F2），再回发
        this.warmHold(server, op);
        this.pendingChunks.remove(pack(Math.floorDiv(op.posX(), 16), Math.floorDiv(op.posZ(), 16)));
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

    /** NeedsChunk（目标区块未加载）：setChunkForced 加载；FULL 达成回调（onChunkFullLoaded）
     *  同 tick 续裁。超时上限由 expireStalledRetries 兜底（fail closed）。 */
    private void handleNeedsChunk(MinecraftServer server, PendingOp item,
                                  OperationAdjudicator.NeedsChunk needs) {
        String id = hex(item.op().operationId());
        ServerLevel level = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (level != null) {
            // 生产路径（server 恒非 null）：加载目标区块并记录 ticket（终态后移交热驻留）
            level.setChunkForced(needs.chunkX(), needs.chunkZ(), true);
            this.forcedTickets.add(pack(needs.chunkX(), needs.chunkZ()));
        }
        // 重试：进按 chunk 索引的重试队列（FULL 回调同 tick 取出裁决），保持 pendingById
        // 去重一致（重复提交在重试期间仍被忽略）
        PendingOp retry = new PendingOp(item.op(), item.retryTicks() + 1);
        this.pendingById.put(id, retry);
        long chunkKey = pack(needs.chunkX(), needs.chunkZ());
        synchronized (this.retryByChunk) {
            this.retryByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(retry);
        }
        this.pendingChunks.add(chunkKey);
    }

    /** 拒绝终态回发 + 历史记录 + 热驻留。 */
    private void terminalRejected(MinecraftServer server, Messages.SubmitOperation op, String reason) {
        this.warmHold(server, op);
        this.pendingChunks.remove(pack(Math.floorDiv(op.posX(), 16), Math.floorDiv(op.posZ(), 16)));
        int cx = Math.floorDiv(op.posX(), 16);
        int cz = Math.floorDiv(op.posZ(), 16);
        Messages.OperationRejected rejectedMsg = new Messages.OperationRejected(
                op.operationId(), reason, op.dimension(),
                new int[] {cx}, new int[] {cz}, System.currentTimeMillis());
        this.send(MessageType.OPERATION_REJECTED, rejectedMsg);
        Messages.OperationStatusRes terminal = new Messages.OperationStatusRes(op.operationId(),
                Messages.OperationStatusRes.OperationStatus.REJECTED, "", 0,
                reason, op.dimension(), new int[] {cx}, new int[] {cz});
        synchronized (this.history) {
            this.history.put(hex(op.operationId()), terminal);
        }
        LOGGER.info("[icbm-source] 操作被拒: op={} type={} pos={},{},{} reason={}",
                hex(op.operationId()), op.operationType(), op.posX(), op.posY(), op.posZ(), reason);
    }

    /** 终态后热驻留（F2）：force ticket 生命周期移交 MaterializeService.hold（30s 无操作
     *  自动释放）；无物化服务（测试）→ 立即释放（旧语义）。 */
    private void warmHold(MinecraftServer server, Messages.SubmitOperation op) {
        ServerLevel level = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        int cx = Math.floorDiv(op.posX(), 16);
        int cz = Math.floorDiv(op.posZ(), 16);
        this.forcedTickets.remove(pack(cx, cz)); // 自己的记录清除（ticket 生命周期移交）
        MaterializeService service = this.materialize;
        if (service != null) {
            service.hold(level, cx, cz);
        } else {
            level.setChunkForced(cx, cz, false);
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
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

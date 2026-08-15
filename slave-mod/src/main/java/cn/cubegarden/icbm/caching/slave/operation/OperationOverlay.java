package cn.cubegarden.icbm.caching.slave.operation;

import cn.cubegarden.icbm.caching.protocol.Messages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 可丢弃乐观预测 overlay（里程碑 7 阶段 5；ARCHITECTURE §5.2「只在可丢弃 overlay 中应用
 * 可逆预测，不得改写 committed snapshot」）。
 *
 * <p>约束（256M 最小增量表示）：
 * <ul>
 *   <li>内存 Map（BlockPos → 预测 BlockState），不落盘、不写 committed snapshot；</li>
 *   <li>每玩家每 chunk 单 pending（架构 §6）：相交操作排队直到前一操作达到终态——
 *       v1 由调用方（未来玩家网络层）负责排队，本类记录状态机供其判断；</li>
 *   <li>终态后：COMMITTED → 清除该 pos 预测（真实快照随后经快照管道覆盖，不保留幽灵状态）；
 *       REJECTED → 清除预测并给出 resync 范围（完整纠正，不做单方块包）；</li>
 *   <li>超时未达终态 → 查询 GET_OPERATION_STATUS（操作超时不等于失败），终态不确定前
 *       预测不得视为权威。</li>
 * </ul>
 */
public final class OperationOverlay {

    /** 预测状态（待终态）。 */
    public static final class Pending {
        public final String playerIdHex;
        public final int posX;
        public final int posY;
        public final int posZ;
        public final int chunkX;
        public final int chunkZ;
        public final long clientInteractionSequence;
        public final Messages.OperationType type;
        public final String predictedState; // PLACE 的放置状态 / BREAK 的 "minecraft:air"
        public final long submittedAtMillis;

        Pending(String playerIdHex, int posX, int posY, int posZ, long clientInteractionSequence,
                Messages.OperationType type, String predictedState, long submittedAtMillis) {
            this.playerIdHex = playerIdHex;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.chunkX = Math.floorDiv(posX, 16);
            this.chunkZ = Math.floorDiv(posZ, 16);
            this.clientInteractionSequence = clientInteractionSequence;
            this.type = type;
            this.predictedState = predictedState;
            this.submittedAtMillis = submittedAtMillis;
        }
    }

    /** 位置（x/y/z 打包为 long 键）。 */
    public record Pos(int x, int y, int z) {
    }

    private final Map<Long, String> predicted = new HashMap<>();          // Pos 键 → 预测状态
    private final Map<String, Pending> pendingByOp = new HashMap<>();     // operationId hex → pending
    private final Map<Long, String> opByPos = new HashMap<>();            // Pos 键 → operationId hex

    /** 预测状态查询（未预测返回 null——调用方回落到 committed snapshot）。 */
    public synchronized String predictedStateAt(int x, int y, int z) {
        return this.predicted.get(key(x, y, z));
    }

    /** 该位置是否有未决操作（相交操作排队判定）。 */
    public synchronized boolean hasPendingAt(int x, int y, int z) {
        return this.opByPos.containsKey(key(x, y, z));
    }

    /** 玩家在某 chunk 是否有未决操作（单 pending 约束）。 */
    public synchronized boolean hasPendingInChunk(String playerIdHex, int chunkX, int chunkZ) {
        for (Pending pending : this.pendingByOp.values()) {
            if (pending.playerIdHex.equals(playerIdHex) && pending.chunkX == chunkX && pending.chunkZ == chunkZ) {
                return true;
            }
        }
        return false;
    }

    /** 某 chunk 的全部未决操作（阶段 8.1：刷新/重载后重应用预测——LevelChunk = committed + overlay）。 */
    public synchronized List<Pending> pendingInChunk(int chunkX, int chunkZ) {
        List<Pending> found = new ArrayList<>();
        for (Pending pending : this.pendingByOp.values()) {
            if (pending.chunkX == chunkX && pending.chunkZ == chunkZ) {
                found.add(pending);
            }
        }
        return found;
    }

    /** 提交时登记预测（调用方已校验可预测性与单 pending 约束）。 */
    public synchronized void submit(String operationIdHex, String playerIdHex,
                                    Messages.SubmitOperation op, String predictedState) {
        long posKey = key(op.posX(), op.posY(), op.posZ());
        Pending pending = new Pending(playerIdHex, op.posX(), op.posY(), op.posZ(),
                op.clientInteractionSequence(), op.operationType(), predictedState,
                System.currentTimeMillis());
        this.pendingByOp.put(operationIdHex, pending);
        this.opByPos.put(posKey, operationIdHex);
        this.predicted.put(posKey, predictedState);
    }

    /** COMMITTED：按 operationId 反查 pending → 清除该 pos 预测（真实快照随后覆盖；不保留幽灵状态）。 */
    public synchronized void onCommitted(byte[] operationId) {
        String id = hex(operationId);
        Pending pending = this.pendingByOp.remove(id);
        if (pending != null) {
            long posKey = key(pending.posX, pending.posY, pending.posZ);
            this.opByPos.remove(posKey);
            this.predicted.remove(posKey);
        }
    }

    /** REJECTED：清除预测并返回完整纠正范围（RESYNC chunk；不做单方块包）。 */
    public synchronized List<int[]> onRejected(byte[] operationId) {
        String id = hex(operationId);
        Pending pending = this.pendingByOp.remove(id);
        List<int[]> resync = new ArrayList<>();
        if (pending != null) {
            resync.add(new int[] {pending.chunkX, pending.chunkZ});
            long posKey = key(pending.posX, pending.posY, pending.posZ);
            this.opByPos.remove(posKey);
            this.predicted.remove(posKey);
        }
        return resync;
    }

    /** 丢弃全部预测（断线/epoch 切换/会话失效：fail closed，不展示不确定数据）。 */
    public synchronized int discardAll() {
        int count = this.predicted.size();
        this.predicted.clear();
        this.pendingByOp.clear();
        this.opByPos.clear();
        return count;
    }

    /** 未决操作列表（按提交序；供超时检查与状态查询）。 */
    public synchronized List<Pending> pendingSnapshot() {
        List<Pending> all = new ArrayList<>(this.pendingByOp.values());
        all.sort(Comparator.comparingLong(p -> p.submittedAtMillis));
        return all;
    }

    /** 该操作是否仍在等待终态。 */
    public synchronized boolean isPending(String operationIdHex) {
        return this.pendingByOp.containsKey(operationIdHex);
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 42) ^ ((long) y << 21) ^ (long) z;
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}

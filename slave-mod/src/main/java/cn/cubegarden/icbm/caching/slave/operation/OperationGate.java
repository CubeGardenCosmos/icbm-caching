package cn.cubegarden.icbm.caching.slave.operation;

/**
 * 真实玩家操作预检验（阶段 8.1；纯逻辑、MC 无关输入，可单测）。
 *
 * <p>映射：调用方（PlayerOperationHandler）把 26.3 世界/玩家对象折叠为布尔输入，本类只做
 * 判定——目标区块可服务（与 PlayerBounds 同源，fail closed）、v1 创造模式先行（instabuild，
 * 生存挖掘动画延后）、距离内、目标状态合法（BREAK 非 air/非不可破坏/非 GameMasterBlock；
 * PLACE 目标 air/可替换 + 非复杂放置）、overlay 单 pending（相交 pos + 每玩家每 chunk）。
 *
 * <p>返回 null = 放行；否则为拒绝原因（调用方回显撤销 + 记录日志，不提交操作）。
 */
public final class OperationGate {

    private OperationGate() {
    }

    /** BREAK 预检验。 */
    public static String breakIntent(
            boolean chunkServiceable,
            boolean instabuild,
            boolean inRange,
            boolean viewAir,
            boolean unbreakable,
            boolean gameMasterBlock,
            boolean pendingAtPos,
            boolean pendingInChunk
    ) {
        if (!chunkServiceable) {
            return "chunk-not-serviceable";
        }
        if (!instabuild) {
            return "survival-mode-deferred";
        }
        if (!inRange) {
            return "too-far";
        }
        if (viewAir) {
            return "already-air";
        }
        if (unbreakable) {
            return "unbreakable-block";
        }
        if (gameMasterBlock) {
            return "game-master-block";
        }
        if (pendingAtPos || pendingInChunk) {
            return "pending-operation";
        }
        return null;
    }

    /** PLACE 预检验。 */
    public static String placeIntent(
            boolean chunkServiceable,
            boolean instabuild,
            boolean inRange,
            boolean targetAirOrReplaceable,
            boolean complexPlacement,
            boolean pendingAtPos,
            boolean pendingInChunk
    ) {
        if (!chunkServiceable) {
            return "chunk-not-serviceable";
        }
        if (!instabuild) {
            return "survival-mode-deferred";
        }
        if (!inRange) {
            return "too-far";
        }
        if (!targetAirOrReplaceable) {
            return "target-not-replaceable";
        }
        if (complexPlacement) {
            return "complex-placement-block";
        }
        if (pendingAtPos || pendingInChunk) {
            return "pending-operation";
        }
        return null;
    }
}

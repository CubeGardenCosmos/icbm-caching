package cn.cubegarden.icbm.caching.slave.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 真实玩家操作预检验纯逻辑测试（阶段 8.1）：
 * <ul>
 *   <li>BREAK：可服务性/创造模式/距离/目标状态/单 pending 各拒绝分支；</li>
 *   <li>PLACE：可替换性/复杂放置/单 pending 各拒绝分支；</li>
 *   <li>放行返回 null（调用方提交操作）。</li>
 * </ul>
 */
class OperationGateTest {

    @Test
    void breakIntentPassesWhenAllChecksOk() {
        assertNull(OperationGate.breakIntent(
                true, true, true, false, false, false, false, false));
    }

    @Test
    void breakIntentRejectsUnserviceableChunkFirst() {
        assertEquals("chunk-not-serviceable", OperationGate.breakIntent(
                false, true, true, false, false, false, false, false));
    }

    @Test
    void breakIntentSurvivalDeferred() {
        assertEquals("survival-mode-deferred", OperationGate.breakIntent(
                true, false, true, false, false, false, false, false));
    }

    @Test
    void breakIntentTooFar() {
        assertEquals("too-far", OperationGate.breakIntent(
                true, true, false, false, false, false, false, false));
    }

    @Test
    void breakIntentAlreadyAir() {
        assertEquals("already-air", OperationGate.breakIntent(
                true, true, true, true, false, false, false, false));
    }

    @Test
    void breakIntentUnbreakableBlock() {
        assertEquals("unbreakable-block", OperationGate.breakIntent(
                true, true, true, false, true, false, false, false));
    }

    @Test
    void breakIntentGameMasterBlock() {
        assertEquals("game-master-block", OperationGate.breakIntent(
                true, true, true, false, false, true, false, false));
    }

    @Test
    void breakIntentPendingGates() {
        assertEquals("pending-operation", OperationGate.breakIntent(
                true, true, true, false, false, false, true, false));
        assertEquals("pending-operation", OperationGate.breakIntent(
                true, true, true, false, false, false, false, true));
    }

    @Test
    void placeIntentPassesWhenAllChecksOk() {
        assertNull(OperationGate.placeIntent(
                true, true, true, true, false, false, false));
    }

    @Test
    void placeIntentTargetNotReplaceable() {
        assertEquals("target-not-replaceable", OperationGate.placeIntent(
                true, true, true, false, false, false, false));
    }

    @Test
    void placeIntentComplexPlacementRejected() {
        assertEquals("complex-placement-block", OperationGate.placeIntent(
                true, true, true, true, true, false, false));
    }

    @Test
    void placeIntentPendingGates() {
        assertEquals("pending-operation", OperationGate.placeIntent(
                true, true, true, true, false, true, false));
        assertEquals("pending-operation", OperationGate.placeIntent(
                true, true, true, true, false, false, true));
    }

    @Test
    void placeIntentSurvivalDeferred() {
        assertEquals("survival-mode-deferred", OperationGate.placeIntent(
                true, false, true, true, false, false, false));
    }
}

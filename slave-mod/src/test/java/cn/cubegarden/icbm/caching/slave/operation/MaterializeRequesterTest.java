package cn.cubegarden.icbm.caching.slave.operation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.Messages;
import org.junit.jupiter.api.Test;

/**
 * 物化请求节流/去重判定（纯逻辑；8.2 草稿 §3.5）：冷却期内同 chunk 拒绝（防快速移动
 * 风暴）；PLAYER_PROXIMITY 每 tick 限量；PREFETCH 不受限量约束（低频周期已限频）。
 */
class MaterializeRequesterTest {

    @Test
    void cooldownRejectsSameChunkWithinWindow() {
        // tick 100 请求过 → tick 150（差 50 < 100）冷却中
        assertFalse(MaterializeRequester.shouldSend(150, 100, 0,
                Messages.MaterializeReason.PLAYER_PROXIMITY, 100, 4));
        // tick 200（差 100 == 冷却）允许
        assertTrue(MaterializeRequester.shouldSend(200, 100, 0,
                Messages.MaterializeReason.PLAYER_PROXIMITY, 100, 4));
    }

    @Test
    void neverRequestedChunkAllowed() {
        assertTrue(MaterializeRequester.shouldSend(5, Integer.MIN_VALUE / 2, 0,
                Messages.MaterializeReason.PLAYER_PROXIMITY, 100, 4));
    }

    @Test
    void proximityLimitedPerTick() {
        assertTrue(MaterializeRequester.shouldSend(1, Integer.MIN_VALUE / 2, 3,
                Messages.MaterializeReason.PLAYER_PROXIMITY, 100, 4));
        assertFalse(MaterializeRequester.shouldSend(1, Integer.MIN_VALUE / 2, 4,
                Messages.MaterializeReason.PLAYER_PROXIMITY, 100, 4));
        assertFalse(MaterializeRequester.shouldSend(1, Integer.MIN_VALUE / 2, 9,
                Messages.MaterializeReason.PLAYER_PROXIMITY, 100, 4));
    }

    @Test
    void prefetchNotLimitedByProximityBudget() {
        // 即使 proximityThisTick 已满，PREFETCH 仍允许（低频周期限频已足够）
        assertTrue(MaterializeRequester.shouldSend(1, Integer.MIN_VALUE / 2, 4,
                Messages.MaterializeReason.PREFETCH, 100, 4));
        assertTrue(MaterializeRequester.shouldSend(1, Integer.MIN_VALUE / 2, 100,
                Messages.MaterializeReason.PREFETCH, 100, 4));
    }

    @Test
    void prefetchStillSubjectToCooldown() {
        // PREFETCH 也受同 chunk 冷却约束（避免每周期重复刷同一区块）
        assertFalse(MaterializeRequester.shouldSend(150, 100, 0,
                Messages.MaterializeReason.PREFETCH, 100, 4));
    }
}

package cn.cubegarden.icbm.caching.slave.selfcheck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 世界计算 gamerule 常量自检（阶段 9.1，草稿 edge-no-world-sim §3.6）：26.3 GameRules
 * 命名与 1.21 完全不同（GameRules.java，草稿 §2.5）——random_tick_speed/spawn_mobs/
 * advance_time/advance_weather/raids，不存在旧 doRandomTick/doMobSpawning/
 * doDaylightCycle/doWeatherCycle。用 {@code initialize=false} 反射验证常量存在（避免
 * 触发 BuiltInRegistries 静态 bootstrap），与 MixinTargetVerifier 启动自检互补：常量漂移
 * 立即红灯，而非等到真机启动。
 */
class WorldSimGateTest {

    @Test
    void gameRuleConstantsExistOn26_3() throws Exception {
        Class<?> gameRules = Class.forName(
                "net.minecraft.world.level.gamerules.GameRules", false,
                WorldSimGateTest.class.getClassLoader());
        assertDoesNotThrow(() -> gameRules.getField("RANDOM_TICK_SPEED"), "26.3 random_tick_speed 常量缺失");
        assertDoesNotThrow(() -> gameRules.getField("SPAWN_MOBS"), "26.3 spawn_mobs 常量缺失");
        assertDoesNotThrow(() -> gameRules.getField("ADVANCE_TIME"), "26.3 advance_time 常量缺失");
        assertDoesNotThrow(() -> gameRules.getField("ADVANCE_WEATHER"), "26.3 advance_weather 常量缺失");
        assertDoesNotThrow(() -> gameRules.getField("RAIDS"), "26.3 raids 常量缺失");
        assertTrue(true);
    }
}

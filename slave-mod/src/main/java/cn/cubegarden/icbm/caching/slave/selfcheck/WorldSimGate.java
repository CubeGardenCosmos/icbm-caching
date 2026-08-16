package cn.cubegarden.icbm.caching.slave.selfcheck;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 边缘世界计算禁用门（阶段 9.1，草稿 edge-no-world-sim §3）：slave 是 CDN 边缘节点，
 * 不做任何地图演化——随机刻/生物生成/昼夜/天气/突袭先以 gamerule 兜底，其余不可由
 * gamerule 覆盖的路径（计划刻/红石、流体 tick、方块实体 tick、方块事件、非玩家实体 tick、
 * 闪电、时间推进）由 {@code icbm_slave.mixins.*WorldSim*} 一组 Mixin 禁用。
 *
 * <p>gamerule 是代码强制而非配置项（禁用世界计算是 slave 恒定行为，不提供"忘了开"的
 * 可能）；26.3 命名与 1.21 完全不同（GameRules.java，草稿 §2.5）：random_tick_speed /
 * spawn_mobs / advance_time / advance_weather / raids——不存在 doRandomTick/
 * doMobSpawning/doDaylightCycle/doWeatherCycle 旧名。设置后 {@link #verify} 读回验证，
 * 任何一项未生效 → 装配 fail closed（控制通道不启动、玩家登录被拒）。
 */
public final class WorldSimGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldSimGate.class);

    private WorldSimGate() {
    }

    /** 应用 gamerule 兜底（Server thread；world 装载后调用）。 */
    public static void apply(MinecraftServer server) {
        GameRules rules = server.getGameRules();
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.RAIDS, false, server);
    }

    /** 读回验证 gamerule 兜底生效；任一未生效返回 false（fail closed）。 */
    public static boolean verify(MinecraftServer server) {
        GameRules rules = server.getGameRules();
        return check(rules.get(GameRules.RANDOM_TICK_SPEED) == 0, "random_tick_speed=0")
                && check(!rules.get(GameRules.SPAWN_MOBS), "spawn_mobs=false")
                && check(!rules.get(GameRules.ADVANCE_TIME), "advance_time=false")
                && check(!rules.get(GameRules.ADVANCE_WEATHER), "advance_weather=false")
                && check(!rules.get(GameRules.RAIDS), "raids=false");
    }

    private static boolean check(boolean ok, String expectation) {
        if (!ok) {
            LOGGER.error("[icbm-slave] 世界计算 gamerule 兜底未生效: 期望 {}（禁用缓存命中，fail closed）",
                    expectation);
        }
        return ok;
    }
}

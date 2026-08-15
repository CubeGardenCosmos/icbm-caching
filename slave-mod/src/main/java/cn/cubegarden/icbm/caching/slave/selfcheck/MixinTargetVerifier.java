package cn.cubegarden.icbm.caching.slave.selfcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin 目标自检（复用 fabric-source 模式，AGENTS.md「Mixin 目标缺失时必须禁用而不是
 * 静默降级」）：启动时验证全部 26.3 依赖的类/方法存在，任何缺失 → slave 控制通道
 * 不启动、玩家登录被拒（fail closed）。
 *
 * <p>同时检查堆上限约束（256M 设计约束，ARCHITECTURE §1.2/§3.1）：超过上限告警但不
 * 拒绝（运维可配置更大堆做压测）。
 */
public final class MixinTargetVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(MixinTargetVerifier.class);

    private MixinTargetVerifier() {
    }

    /** 验证本模组依赖的全部 Mixin 目标与方法。 */
    public static boolean verify() {
        boolean ok = checkMethod("net.minecraft.server.level.ChunkMap", "scheduleChunkLoad")
                && checkMethod("net.minecraft.server.level.ChunkMap", "applyStep")
                && checkClass("net.minecraft.world.level.chunk.storage.SerializableChunkData")
                && checkMethod("net.minecraft.server.network.ServerLoginPacketListenerImpl", "handleKey")
                && checkMethod("net.minecraft.server.network.ServerGamePacketListenerImpl", "handleMovePlayer");
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 256L * 1024 * 1024) {
            LOGGER.warn("[icbm-slave] 堆上限 {} MiB 超过 256M 设计约束（观测/压测环境可接受）",
                    maxMemory / 1024 / 1024);
        }
        return ok;
    }

    private static boolean checkMethod(String className, String methodName) {
        try {
            Class<?> clazz = Class.forName(className);
            for (var method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return true;
                }
            }
            LOGGER.error("[icbm-slave] Mixin 目标方法缺失: {}.{}（禁用缓存命中，fail closed）",
                    className, methodName);
            return false;
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("[icbm-slave] Mixin 目标缺失: {}（禁用缓存命中，fail closed）", className, e);
            return false;
        }
    }

    private static boolean checkClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("[icbm-slave] 类缺失: {}（禁用缓存命中，fail closed）", className, e);
            return false;
        }
    }
}

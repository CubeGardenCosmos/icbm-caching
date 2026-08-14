package cn.cubegarden.icbm.caching.source.selfcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin 目标启动自检（ARCHITECTURE.md：「Mixin 的精确目标必须以 26.3 mappings 和实际字节码
 * 验证。任何目标缺失都要在启动时禁用缓存命中，而不是静默降级」）。
 *
 * <p>反射检查 26.3 官方 mojmap 名称（与反编译源码 minecraft-assets 对照）。缺失即
 * {@link #verify()} 返回 false，由组合根调用 {@code CacheGate.disable}（fail closed）。
 */
public final class MixinTargetVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(MixinTargetVerifier.class);

    private MixinTargetVerifier() {
    }

    /** 验证本模组依赖的全部 Mixin 目标与方法。 */
    public static boolean verify() {
        return checkMethod("net.minecraft.world.level.chunk.ChunkAccess", "markUnsaved")
                && checkClass("net.minecraft.world.level.chunk.storage.SerializableChunkData");
    }

    private static boolean checkMethod(String className, String methodName) {
        try {
            Class<?> clazz = Class.forName(className);
            clazz.getDeclaredMethod(methodName);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("[icbm-source] Mixin 目标缺失: {}.{}（禁用缓存命中，fail closed）", className, methodName, e);
            return false;
        }
    }

    private static boolean checkClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("[icbm-source] 类缺失: {}（禁用缓存命中，fail closed）", className, e);
            return false;
        }
    }
}

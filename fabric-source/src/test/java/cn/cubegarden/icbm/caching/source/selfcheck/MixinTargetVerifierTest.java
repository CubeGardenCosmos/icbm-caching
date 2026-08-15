package cn.cubegarden.icbm.caching.source.selfcheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Mixin 目标启动自检（里程碑 6 复验）：参数化 verify 对存在/缺失目标两类行为。
 * 存在目标用 JDK 类（测试 classpath 无 Minecraft jar，不在此断言 26.3 真实目标——
 * 真实目标由端到端源服启动验证，AGENTS.md「Mixin 目标启动自检测试」）。
 */
class MixinTargetVerifierTest {

    @Test
    void existingTargetsPass() {
        assertTrue(MixinTargetVerifier.verify(new MixinTargetVerifier.TargetSet(
                Set.of("java.lang.String"), Set.of("java.lang.String#length"))),
                "存在的类与方法必须通过");
    }

    @Test
    void missingClassFailsClosed() {
        assertFalse(MixinTargetVerifier.verify(new MixinTargetVerifier.TargetSet(
                Set.of("net.minecraft.nonexistent.Class"), Set.of())),
                "缺失类 → false（禁用缓存命中，fail closed）");
    }

    @Test
    void missingMethodFailsClosed() {
        assertFalse(MixinTargetVerifier.verify(new MixinTargetVerifier.TargetSet(
                Set.of(), Set.of("java.lang.String#noSuchMethod"))),
                "缺失方法 → false（禁用缓存命中，fail closed）");
    }

    @Test
    void malformedMethodSpecFailsClosed() {
        assertFalse(MixinTargetVerifier.verify(new MixinTargetVerifier.TargetSet(
                        Set.of(), Set.of("no-separator"))),
                "非法目标格式 → false（不猜测）");
    }
}

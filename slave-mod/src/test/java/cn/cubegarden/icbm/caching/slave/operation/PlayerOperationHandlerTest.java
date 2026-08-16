package cn.cubegarden.icbm.caching.slave.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 预条件哈希一致性测试（阶段 8.1）：slave 与源服 OperationAdjudicator.hash 必须同算法
 * （sha256hex(BlockState.toString)），否则源服预条件比对恒失败。此处锁定已知向量，并
 * 与源服侧 golden 常量（见 OperationQueueTest.op 的 hash("minecraft:air") 用法）对齐。
 *
 * <p>阶段 9.2 F1：canonicalState 解析（{@code BlockStateParser.parseForBlock}，26.3 命名，
 * 1.21 的 BlockArgumentParser 已改名）依赖 BuiltInRegistries 完整 bootstrap（unit classpath
 * 缺 datafixers 资源，无法真实解析）→ 用 {@code initialize=false} 反射验证目标存在（防
 * 26.3 改名漂移），真实解析行为由端到端真机验证（e2e 有完整 bootstrap）。
 */
class PlayerOperationHandlerTest {

    @Test
    void hashMatchesKnownVector() {
        // sha256("minecraft:air") hex（与源服 OperationAdjudicator.hash 同算法同输入）
        assertEquals("eb4900226496a481b74579b44bba0ab5a12d08a56a5d5a8082229a54f30521ec",
                PlayerOperationHandler.hash("minecraft:air"));
    }

    @Test
    void hashIsStableAndDistinct() {
        String air = PlayerOperationHandler.hash("minecraft:air");
        assertEquals(air, PlayerOperationHandler.hash("minecraft:air"), "同输入同哈希");
        String stone = PlayerOperationHandler.hash("minecraft:stone");
        assertEquals(64, stone.length(), "sha256 hex 长度 64");
        org.junit.jupiter.api.Assertions.assertNotEquals(air, stone, "不同状态不同哈希");
    }

    @Test
    void normalizeCanonicalStripsBlockWrapper() {
        // 26.3 BlockState.toString() = Block{id}[prop=v,...]（StateHolder.java:42 / Block.java:576）
        assertEquals("minecraft:air",
                PlayerOperationHandler.normalizeCanonical("Block{minecraft:air}"));
        assertEquals("minecraft:oak_log[axis=y]",
                PlayerOperationHandler.normalizeCanonical("Block{minecraft:oak_log}[axis=y]"));
        assertEquals("minecraft:stone",
                PlayerOperationHandler.normalizeCanonical("minecraft:stone"), "非 Block{} 前缀原样返回");
        assertNull(PlayerOperationHandler.normalizeCanonical("Block{minecraft:air"), "未闭合 → null");
        assertNull(PlayerOperationHandler.normalizeCanonical(null));
    }

    @Test
    void parseCanonicalNullAndEmptyFailClosed() {
        // 纯逻辑路径（不触 BuiltInRegistries）：null/空串 → null（调用方等快照纠正，fail closed）
        assertNull(PlayerOperationHandler.parseCanonical(null));
        assertNull(PlayerOperationHandler.parseCanonical(""));
    }

    @Test
    void parseForBlockTargetExistsOn26_3() throws Exception {
        // F1：BlockStateParser.parseForBlock 目标自检（26.3 命名，1.21 已改名）——常量漂移
        // 立即红灯；真实解析行为由真机 e2e 验证（unit 环境无 datafixers 无法 bootstrap）
        Class<?> parser = Class.forName(
                "net.minecraft.commands.arguments.blocks.BlockStateParser", false,
                PlayerOperationHandlerTest.class.getClassLoader());
        Class<?> holderLookup = Class.forName(
                "net.minecraft.core.HolderLookup", false, PlayerOperationHandlerTest.class.getClassLoader());
        assertDoesNotThrow(() -> parser.getMethod("parseForBlock", holderLookup, String.class, boolean.class),
                "26.3 BlockStateParser.parseForBlock(HolderLookup, String, boolean) 缺失");
    }
}

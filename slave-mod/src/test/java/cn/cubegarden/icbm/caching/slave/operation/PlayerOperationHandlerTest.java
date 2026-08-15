package cn.cubegarden.icbm.caching.slave.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 预条件哈希一致性测试（阶段 8.1）：slave 与源服 OperationAdjudicator.hash 必须同算法
 * （sha256hex(BlockState.toString)），否则源服预条件比对恒失败。此处锁定已知向量，并
 * 与源服侧 golden 常量（见 OperationQueueTest.op 的 hash("minecraft:air") 用法）对齐。
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
}

package cn.cubegarden.icbm.caching.slave.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 可丢弃预测 overlay 测试（里程碑 7 阶段 5；验收「重复、乱序和超时消息不会造成
 * 二次写入或幽灵 overlay」）：
 * <ul>
 *   <li>提交登记预测 + 查询优先；</li>
 *   <li>COMMITTED 清除该 pos 预测（不保留幽灵状态）；</li>
 *   <li>REJECTED 清除预测并返回 resync 范围（落回 committed snapshot）；</li>
 *   <li>discardAll（断线/epoch 切换 fail closed）；</li>
 *   <li>每玩家每 chunk 单 pending 约束；相交位置 pending 判定。</li>
 * </ul>
 */
class OperationOverlayTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");

    private static Messages.SubmitOperation op(int posX, int posZ) {
        return new Messages.SubmitOperation(
                toBytes(UUID.randomUUID()), toBytes(UUID.randomUUID()), "sess-1", 1,
                Messages.OperationType.PLACE, DIM, posX, 64, posZ, 5,
                "sha256-air", "minecraft:diamond_block", System.currentTimeMillis());
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return bytes;
    }

    private static String hexOf(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    @Test
    void submitPredictsAndQueries() {
        OperationOverlay overlay = new OperationOverlay();
        Messages.SubmitOperation op = op(10, 20);
        overlay.submit(hexOf(op.operationId()), "player-a", op, "minecraft:diamond_block");
        assertEquals("minecraft:diamond_block", overlay.predictedStateAt(10, 64, 20));
        assertTrue(overlay.hasPendingAt(10, 64, 20));
        assertTrue(overlay.hasPendingInChunk("player-a", 0, 1));
        assertFalse(overlay.hasPendingInChunk("player-b", 0, 1));
    }

    @Test
    void committedClearsPredictionNoGhost() {
        OperationOverlay overlay = new OperationOverlay();
        Messages.SubmitOperation op = op(10, 20);
        overlay.submit(hexOf(op.operationId()), "player-a", op, "minecraft:diamond_block");
        overlay.onCommitted(op.operationId());
        assertNull(overlay.predictedStateAt(10, 64, 20), "COMMITTED 后不得保留幽灵预测");
        assertFalse(overlay.hasPendingAt(10, 64, 20));
        assertTrue(overlay.pendingSnapshot().isEmpty());
    }

    @Test
    void rejectedClearsPredictionAndReturnsResyncScope() {
        OperationOverlay overlay = new OperationOverlay();
        Messages.SubmitOperation op = op(10, 20);
        overlay.submit(hexOf(op.operationId()), "player-a", op, "minecraft:diamond_block");
        List<int[]> resync = overlay.onRejected(op.operationId());
        assertNull(overlay.predictedStateAt(10, 64, 20));
        assertEquals(1, resync.size());
        assertEquals(0, resync.get(0)[0], "resync chunkX");
        assertEquals(1, resync.get(0)[1], "resync chunkZ");
    }

    @Test
    void discardAllFailsClosed() {
        OperationOverlay overlay = new OperationOverlay();
        Messages.SubmitOperation op1 = op(10, 20);
        Messages.SubmitOperation op2 = op(30, 40);
        overlay.submit(hexOf(op1.operationId()), "player-a", op1, "minecraft:diamond_block");
        overlay.submit(hexOf(op2.operationId()), "player-a", op2, "minecraft:stone");
        assertEquals(2, overlay.discardAll());
        assertNull(overlay.predictedStateAt(10, 64, 20));
        assertNull(overlay.predictedStateAt(30, 64, 40));
        assertTrue(overlay.pendingSnapshot().isEmpty());
    }

    @Test
    void duplicateOperationIdDoesNotDoubleRecord() {
        OperationOverlay overlay = new OperationOverlay();
        Messages.SubmitOperation op = op(10, 20);
        overlay.submit(hexOf(op.operationId()), "player-a", op, "minecraft:diamond_block");
        overlay.submit(hexOf(op.operationId()), "player-a", op, "minecraft:diamond_block"); // 重复
        assertEquals(1, overlay.pendingSnapshot().size(), "同一 operationId 只记一个 pending");
        overlay.onCommitted(op.operationId());
        assertTrue(overlay.pendingSnapshot().isEmpty());
    }

    @Test
    void overlappingPositionsPendingGate() {
        OperationOverlay overlay = new OperationOverlay();
        Messages.SubmitOperation op = op(10, 20);
        overlay.submit(hexOf(op.operationId()), "player-a", op, "minecraft:diamond_block");
        assertTrue(overlay.hasPendingAt(10, 64, 20), "相交位置操作必须排队（单 pending）");
        assertFalse(overlay.hasPendingAt(11, 64, 20), "相邻位置不受影响");
    }
}

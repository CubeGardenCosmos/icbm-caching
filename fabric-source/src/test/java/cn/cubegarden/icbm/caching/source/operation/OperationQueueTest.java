package cn.cubegarden.icbm.caching.source.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 操作队列测试（里程碑 7 阶段 5）：
 * <ul>
 *   <li>幂等：同一 operationId 重复提交不二次入队/二次执行（验收「重复消息不会造成二次写入」）；</li>
 *   <li>终态历史：COMMITTED/REJECTED 记录可查询；重复查询返回同一终态；</li>
 *   <li>状态查询：PENDING（排队中）/ UNKNOWN（未知）；</li>
 *   <li>竞争：同 pos 两个不同操作，第一个 COMMITTED 后第二个被真实前置条件拒绝
 *       （fake adjudicator 模拟：第二次 preconditions 不匹配 → REJECTED——源服只提交一个）。</li>
 * </ul>
 */
class OperationQueueTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");

    /** 记录每次裁决（证明同 operationId 只执行一次）。 */
    private static final class RecordingAdjudicator implements OperationQueue.Adjudicator {
        final List<Messages.SubmitOperation> calls = new ArrayList<>();
        final List<OperationAdjudicator.Result> results = new ArrayList<>();

        @Override
        public OperationAdjudicator.Result adjudicate(
                net.minecraft.server.MinecraftServer server, Messages.SubmitOperation op) {
            this.calls.add(op);
            OperationAdjudicator.Result result;
            if (!this.results.isEmpty()) {
                result = this.results.remove(0);
            } else {
                result = new OperationAdjudicator.Committed("minecraft:diamond_block", 5, 1);
            }
            return result;
        }
    }

    private static final class RecordingSender implements OperationQueue.ResultSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(MessageType type, byte[] body) {
            this.sent.add(type + ":" + body.length);
            return true;
        }
    }

    private static Messages.SubmitOperation op(String id, int posX) {
        return new Messages.SubmitOperation(
                UUID.fromString(id) instanceof UUID u
                        ? toBytes(u) : new byte[16],
                new byte[16], "sess-1", 1, Messages.OperationType.PLACE, DIM,
                posX, 64, 0, 5,
                OperationAdjudicator.hash("minecraft:air"), "minecraft:diamond_block",
                System.currentTimeMillis());
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return bytes;
    }

    @Test
    void duplicateSubmitIsIdempotent() {
        RecordingAdjudicator adjudicator = new RecordingAdjudicator();
        RecordingSender sender = new RecordingSender();
        OperationQueue queue = new OperationQueue(adjudicator, sender);
        Messages.SubmitOperation op = op("00000000-0000-0000-0000-000000000001", 10);

        queue.enqueue(op);
        queue.enqueue(op); // 重复（乱序/重放）
        queue.enqueue(op);

        // 每次 tick 只能裁决一次该 operationId（无二次执行）
        queue.onEndServerTick(null);
        queue.onEndServerTick(null);
        queue.onEndServerTick(null);
        assertEquals(1, adjudicator.calls.size(), "重复提交不得二次执行");
        assertEquals(1, sender.sent.size(), "只回发一个终态");
        assertTrue(sender.sent.get(0).startsWith("OPERATION_COMMITTED"));
    }

    @Test
    void committedTerminalIsQueryableAndStable() {
        RecordingAdjudicator adjudicator = new RecordingAdjudicator();
        OperationQueue queue = new OperationQueue(adjudicator, new RecordingSender());
        Messages.SubmitOperation op = op("00000000-0000-0000-0000-000000000002", 10);
        queue.enqueue(op);
        queue.onEndServerTick(null);

        Messages.OperationStatusRes first = queue.query(new Messages.GetOperationStatus(op.operationId()));
        Messages.OperationStatusRes second = queue.query(new Messages.GetOperationStatus(op.operationId()));
        assertEquals(Messages.OperationStatusRes.OperationStatus.COMMITTED, first.status());
        assertEquals("minecraft:diamond_block", first.canonicalState());
        assertEquals(5, first.chunkRevision());
        assertEquals(first, second, "重复查询返回同一终态（幂等）");
    }

    @Test
    void rejectedTerminalIsQueryable() {
        RecordingAdjudicator adjudicator = new RecordingAdjudicator();
        adjudicator.results.add(new OperationAdjudicator.Rejected("precondition-mismatch"));
        OperationQueue queue = new OperationQueue(adjudicator, new RecordingSender());
        Messages.SubmitOperation op = op("00000000-0000-0000-0000-000000000003", 10);
        queue.enqueue(op);
        queue.onEndServerTick(null);

        Messages.OperationStatusRes res = queue.query(new Messages.GetOperationStatus(op.operationId()));
        assertEquals(Messages.OperationStatusRes.OperationStatus.REJECTED, res.status());
        assertEquals("precondition-mismatch", res.rejectReason());
    }

    @Test
    void pendingAndUnknownStatus() {
        OperationQueue queue = new OperationQueue(new RecordingAdjudicator(), new RecordingSender());
        Messages.SubmitOperation op = op("00000000-0000-0000-0000-000000000004", 10);
        queue.enqueue(op);
        // 未裁决 → PENDING
        Messages.OperationStatusRes pending =
                queue.query(new Messages.GetOperationStatus(op.operationId()));
        assertEquals(Messages.OperationStatusRes.OperationStatus.PENDING, pending.status());
        // 未知操作 → UNKNOWN
        Messages.OperationStatusRes unknown = queue.query(
                new Messages.GetOperationStatus(toBytes(UUID.randomUUID())));
        assertEquals(Messages.OperationStatusRes.OperationStatus.UNKNOWN, unknown.status());
    }

    @Test
    void samePositionTwoOperationsOnlyOneCommits() {
        // 竞争同一方块：第一个操作 COMMITTED（diamond 放置成功）；第二个操作（同 pos）
        // 前置条件失败 → REJECTED（目标已是 diamond，preconditions 哈希不匹配）
        RecordingAdjudicator adjudicator = new RecordingAdjudicator();
        adjudicator.results.add(new OperationAdjudicator.Committed("minecraft:diamond_block", 5, 1));
        adjudicator.results.add(new OperationAdjudicator.Rejected("precondition-mismatch"));
        OperationQueue queue = new OperationQueue(adjudicator, new RecordingSender());
        Messages.SubmitOperation first = op("00000000-0000-0000-0000-000000000005", 10);
        Messages.SubmitOperation second = op("00000000-0000-0000-0000-000000000006", 10);
        queue.enqueue(first);
        queue.enqueue(second);
        queue.onEndServerTick(null);
        queue.onEndServerTick(null);

        assertEquals(Messages.OperationStatusRes.OperationStatus.COMMITTED,
                queue.query(new Messages.GetOperationStatus(first.operationId())).status());
        assertEquals(Messages.OperationStatusRes.OperationStatus.REJECTED,
                queue.query(new Messages.GetOperationStatus(second.operationId())).status());
        assertEquals(2, adjudicator.calls.size(), "两个不同 operationId 各裁决一次（源服主线程顺序）");
    }

    @Test
    void needsChunkRetriesThenCommits() {
        // 目标区块未加载：第一次 NeedsChunk（排队重试），加载完成后第二次裁决成功 →
        // 终态 COMMITTED（不再直接 chunk-not-loaded 拒绝——玩家操作不因物化 ticket
        // 释放而全部失败，真机日志修复目标）
        RecordingAdjudicator adjudicator = new RecordingAdjudicator();
        adjudicator.results.add(new OperationAdjudicator.NeedsChunk(0, 4));
        adjudicator.results.add(new OperationAdjudicator.Committed("minecraft:diamond_block", 5, 1));
        RecordingSender sender = new RecordingSender();
        OperationQueue queue = new OperationQueue(adjudicator, sender);
        Messages.SubmitOperation op = op("00000000-0000-0000-0000-000000000007", 10);
        queue.enqueue(op);

        queue.onEndServerTick(null); // NeedsChunk → 重入队（retry=1）
        assertEquals(Messages.OperationStatusRes.OperationStatus.PENDING,
                queue.query(new Messages.GetOperationStatus(op.operationId())).status(),
                "区块加载中保持 PENDING（不产生错误终态）");
        queue.onEndServerTick(null); // 第二次：Committed

        assertEquals(Messages.OperationStatusRes.OperationStatus.COMMITTED,
                queue.query(new Messages.GetOperationStatus(op.operationId())).status());
        assertEquals(2, adjudicator.calls.size(), "NeedsChunk 后必须重试裁决（不吞操作）");
        assertEquals(1, sender.sent.size());
        assertTrue(sender.sent.get(0).startsWith("OPERATION_COMMITTED"),
                "只回发一个终态（重试过程不产生中间终态）");
    }

    @Test
    void needsChunkTimeoutRejectsFailClosed() {
        // 区块加载迟迟不完成（物化失败等）：超时后拒绝（fail closed，不无限驻留）
        RecordingAdjudicator adjudicator = new RecordingAdjudicator();
        for (int i = 0; i < OperationQueue.MAX_CHUNK_RETRY_TICKS + 2; i++) {
            adjudicator.results.add(new OperationAdjudicator.NeedsChunk(0, 4)); // 一直未加载
        }
        RecordingSender sender = new RecordingSender();
        OperationQueue queue = new OperationQueue(adjudicator, sender);
        Messages.SubmitOperation op = op("00000000-0000-0000-0000-000000000008", 10);
        queue.enqueue(op);

        for (int i = 0; i < OperationQueue.MAX_CHUNK_RETRY_TICKS + 2; i++) {
            queue.onEndServerTick(null);
        }

        Messages.OperationStatusRes res = queue.query(new Messages.GetOperationStatus(op.operationId()));
        assertEquals(Messages.OperationStatusRes.OperationStatus.REJECTED, res.status());
        assertEquals("chunk-not-loaded", res.rejectReason());
        assertEquals(OperationQueue.MAX_CHUNK_RETRY_TICKS + 1, adjudicator.calls.size(),
                "重试至上限后拒绝（第 MAX+1 次裁决触发超时终态）");
        assertTrue(sender.sent.get(sender.sent.size() - 1).startsWith("OPERATION_REJECTED"));
    }

    @Test
    void historyIsBounded() {
        RecordingAdjudicator adjudicator = new RecordingAdjudicator();
        OperationQueue queue = new OperationQueue(adjudicator, new RecordingSender());
        // 注入超过上限的终态（直接调 private 不可行；用真实裁决：每个操作一次 tick）
        for (int i = 0; i < 1100; i++) {
            Messages.SubmitOperation op = op(String.format("00000000-0000-0000-0000-%012d", i), 10);
            queue.enqueue(op);
            queue.onEndServerTick(null);
        }
        // 有界历史：最旧已逐出（UNKNOWN），最新可查询
        Messages.SubmitOperation latest = op("00000000-0000-0000-0000-000000001100", 10);
        queue.enqueue(latest);
        queue.onEndServerTick(null);
        assertEquals(Messages.OperationStatusRes.OperationStatus.COMMITTED,
                queue.query(new Messages.GetOperationStatus(latest.operationId())).status());
    }
}

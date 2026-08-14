package cn.cubegarden.icbm.caching.source.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.source.CacheGate;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** 提交流程状态机测试：required → put（校验）→ committed，任意失败不得 commit（fail closed）。 */
class SnapshotPublisherTest {

    private static final ChunkKey KEY = ChunkKey.of(Identifier.fromNamespaceAndPath("minecraft", "overworld"), 1, 2);

    /** 内存 sink：记录调用序列，可注入失败结果。 */
    private static final class RecordingSink implements SnapshotSink {
        final List<String> calls = new ArrayList<>();
        SnapshotSink.PutResult putResult = SnapshotSink.PutResult.OK;
        boolean available = true;

        @Override
        public boolean available() {
            return this.available;
        }

        @Override
        public void raiseRequiredRevision(ChunkKey key, long revision) {
            this.calls.add("raise:" + revision);
        }

        @Override
        public void putSnapshot(ChunkSnapshot snapshot, PutCallback done) {
            this.calls.add("put:" + snapshot.revision());
            done.onResult(snapshot.key(), snapshot.revision(), this.putResult);
        }

        @Override
        public void commitRevision(ChunkKey key, long revision, CommitCallback done) {
            this.calls.add("commit:" + revision);
            done.onCommitted(key, revision, true);
        }

        @Override
        public void announceEpoch(cn.cubegarden.icbm.caching.protocol.DimensionRef dimension,
                                  long epoch, String reason, String registryFingerprint) {
            this.calls.add("epoch:" + epoch);
        }

        @Override
        public void close() {
        }
    }

    private static ChunkSnapshot snapshot(long revision) {
        return ChunkSnapshot.of(KEY, revision, 1L, new byte[] {1, 2, 3}, 3);
    }

    @Test
    void happyPathOrderingRequiredPutCommit() {
        RecordingSink sink = new RecordingSink();
        SnapshotPublisher publisher = new SnapshotPublisher(sink, new CacheGate());

        assertTrue(publisher.begin(KEY, 5));
        publisher.put(snapshot(5));
        publisher.commit(KEY, 5);
        publisher.endTick(KEY);

        assertEquals(List.of("raise:5", "put:5", "commit:5"), sink.calls);
        assertTrue(publisher.begin(KEY, 6), "上一轮终态后可开始下一轮");
    }

    @Test
    void gateDisabledFailsClosed() {
        RecordingSink sink = new RecordingSink();
        CacheGate gate = new CacheGate();
        gate.disable("Mixin 目标缺失");
        SnapshotPublisher publisher = new SnapshotPublisher(sink, gate);

        assertFalse(publisher.begin(KEY, 5));
        publisher.put(snapshot(5));
        publisher.commit(KEY, 5);
        assertTrue(sink.calls.isEmpty(), "闸门关闭时不得有任何 sink 调用");
    }

    @Test
    void sinkUnavailableFailsClosedAndNoSinkCalls() {
        RecordingSink sink = new RecordingSink();
        sink.available = false;
        SnapshotPublisher publisher = new SnapshotPublisher(sink, new CacheGate());

        assertFalse(publisher.begin(KEY, 5), "控制服务不可用时不得 begin（fail closed）");
        assertTrue(sink.calls.isEmpty(), "sink 不可用时不得发出任何调用");
    }

    @Test
    void rejectedSnapshotNeverCommits() {
        RecordingSink sink = new RecordingSink();
        sink.putResult = SnapshotSink.PutResult.REJECTED_HASH_MISMATCH;
        SnapshotPublisher publisher = new SnapshotPublisher(sink, new CacheGate());

        assertTrue(publisher.begin(KEY, 5));
        publisher.put(snapshot(5));
        publisher.commit(KEY, 5); // 应为 no-op
        publisher.endTick(KEY);

        assertEquals(List.of("raise:5", "put:5"), sink.calls, "失败不得 commit");
        assertTrue(publisher.begin(KEY, 6), "fail closed 后下一轮以更高 revision 重试");
    }

    @Test
    void pendingPublishRejectsConcurrentBegin() {
        RecordingSink sink = new RecordingSink();
        SnapshotPublisher publisher = new SnapshotPublisher(sink, new CacheGate());

        assertTrue(publisher.begin(KEY, 5));
        assertFalse(publisher.begin(KEY, 6), "上一轮未终态时拒绝新 begin（每 chunk 一个 pending）");
        publisher.put(snapshot(5));
        publisher.commit(KEY, 5);
        publisher.endTick(KEY);
        assertTrue(publisher.begin(KEY, 6));
    }
}

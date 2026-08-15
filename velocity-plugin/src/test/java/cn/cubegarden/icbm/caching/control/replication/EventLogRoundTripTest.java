package cn.cubegarden.icbm.caching.control.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * EventLog 持久化 round-trip：append → 新实例 recover 后字段完整（含 WORLD_EPOCH 的
 * spawn——读者一直读取 spawn 而写者此前漏写，master 重启恢复非空 events.log 时
 * 记录错位 → 「未知事件类型: 0」崩溃；debug-run 重启暴露，修复写入侧）。
 */
class EventLogRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void worldEpochSpawnSurvivesAppendAndRecover() throws Exception {
        Path file = this.tempDir.resolve("events.log");
        EventLog log = new EventLog(file, 100_000);
        log.recover();
        Messages.SpawnPoint spawn = new Messages.SpawnPoint(1.5, 64.25, -3.0, 90.0f, 0.0f);
        log.append(EventLog.EventType.WORLD_EPOCH, DimensionRef.of("minecraft", "overworld"),
                null, 7, "", "fp123", spawn);
        log.close();

        EventLog recovered = new EventLog(file, 100_000);
        recovered.recover();
        List<EventLog.Event> events = recovered.replayAfter(0);
        assertEquals(1, events.size());
        EventLog.Event event = events.get(0);
        assertEquals(EventLog.EventType.WORLD_EPOCH, event.type());
        assertEquals(7, event.revision());
        assertEquals("fp123", event.fingerprint());
        assertEquals(spawn.x(), event.spawn().x(), 1e-9);
        assertEquals(spawn.y(), event.spawn().y(), 1e-9);
        assertEquals(spawn.z(), event.spawn().z(), 1e-9);
        assertEquals(spawn.yaw(), event.spawn().yaw(), 1e-6);
        assertEquals(spawn.pitch(), event.spawn().pitch(), 1e-6);
    }

    @Test
    void invalidateAndSnapshotCommittedRoundTrip() throws Exception {
        Path file = this.tempDir.resolve("events2.log");
        DimensionRef dim = DimensionRef.of("minecraft", "overworld");
        EventLog log = new EventLog(file, 100_000);
        log.recover();
        log.append(EventLog.EventType.INVALIDATE, dim,
                new ChunkRef(dim, 3, -4), 12, "", "", Messages.SpawnPoint.DEFAULT);
        log.append(EventLog.EventType.SNAPSHOT_COMMITTED, dim,
                new ChunkRef(dim, 1, 2), 5, "deadbeef", "", Messages.SpawnPoint.DEFAULT);
        log.close();

        EventLog recovered = new EventLog(file, 100_000);
        recovered.recover();
        List<EventLog.Event> events = recovered.replayAfter(0);
        assertEquals(2, events.size());
        assertEquals(3, events.get(0).chunk().x());
        assertEquals(-4, events.get(0).chunk().z());
        assertEquals(12, events.get(0).revision());
        assertEquals("deadbeef", events.get(1).payloadHash());
        assertEquals(2, events.get(1).chunk().z());
    }
}

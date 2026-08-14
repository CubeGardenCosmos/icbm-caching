package cn.cubegarden.icbm.caching.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 服务闸门测试（验收：落后的 slave 不服务低于 master requiredRevision 的区块；
 * 控制服务不可用时拒绝不确定基线）。
 */
class ServeGateTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final ChunkRef KEY = ChunkRef.of(DIM, 1, 2);

    @TempDir
    Path root;

    private SnapshotObjectStore store;
    private ServeGate gate;

    @BeforeEach
    void setUp() throws Exception {
        this.store = new SnapshotObjectStore(this.root);
        this.store.recover();
        this.gate = new ServeGate(this.store);
    }

    private static byte[] gzip(String content) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private void commit(String content, long revision) throws Exception {
        byte[] payload = gzip(content);
        this.store.raiseRequired(KEY, revision);
        assertEquals(PutResult.OK, this.store.put(KEY, revision, 1,
                "NBT_CHUNK_V1", "GZIP", content.length(), sha(payload), payload));
        this.store.commit(KEY, revision);
    }

    private static String sha(byte[] payload) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload));
    }

    @Test
    void servableWhenConnectedAndCommittedMatchesRequired() throws Exception {
        this.commit("data", 5);
        this.gate.setMasterConnected(true);
        assertEquals(ServeGate.Status.SERVABLE, this.gate.status(KEY));
    }

    @Test
    void laggingSlaveRefusesBelowMasterRequired() throws Exception {
        this.commit("data", 5);
        this.gate.setMasterConnected(true);
        // master 已失效（required=6）但快照未到 → 落后，不得服务
        this.store.raiseRequired(KEY, 6);
        assertEquals(ServeGate.Status.STALE, this.gate.status(KEY),
                "committed < required 期间不得服务（验收 4）");
    }

    @Test
    void disconnectedRefusesEverything() throws Exception {
        this.commit("data", 5);
        this.gate.setMasterConnected(false); // 控制服务不可用
        assertEquals(ServeGate.Status.UNAVAILABLE, this.gate.status(KEY),
                "控制服务不可用时拒绝不确定基线（fail closed）");
    }

    @Test
    void noBaselineOrEpochMismatchUnavailable() throws Exception {
        this.gate.setMasterConnected(true);
        assertEquals(ServeGate.Status.UNAVAILABLE, this.gate.status(KEY), "无 committed 基线");

        this.commit("data", 5);
        this.store.announceEpoch(DIM, 2); // 维度 epoch 切换：旧 epoch 对象不得装载
        assertEquals(ServeGate.Status.UNAVAILABLE, this.gate.status(KEY));
    }
}

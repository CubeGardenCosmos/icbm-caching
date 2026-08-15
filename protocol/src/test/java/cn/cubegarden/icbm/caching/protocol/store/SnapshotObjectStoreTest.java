package cn.cubegarden.icbm.caching.protocol.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore.ResolveResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 对象库一致性测试（验收：INVALIDATE→committed 窗口不返回旧 HIT；乱序/重放/损坏
 * 不降低 revision、不覆盖新对象；重启后 required/committed 持久恢复）。
 */
class SnapshotObjectStoreTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final ChunkRef KEY = ChunkRef.of(DIM, 3, -1);

    @TempDir
    Path root;

    private SnapshotObjectStore store;

    @BeforeEach
    void setUp() throws Exception {
        this.store = new SnapshotObjectStore(this.root);
        this.store.recover();
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] gzipPayload(String content) throws Exception {
        return gzip(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha(byte[] payload) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private PutResult put(long revision, long epoch, byte[] payload) throws Exception {
        return this.store.put(KEY, revision, epoch, "NBT_CHUNK_V1", "GZIP",
                decompressedLength(payload), sha(payload), payload);
    }

    private static int decompressedLength(byte[] gzipPayload) throws Exception {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(gzipPayload))) {
            return in.readAllBytes().length;
        }
    }

    @Test
    void happyPathInvalidatePutCommitResolve() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);

        ResolveResult result = this.store.resolve(KEY, 0);
        assertEquals(ResolveResult.Status.HIT, result.status());
        assertEquals(7, result.committedRevision());
        assertFalse(result.objectHash().isEmpty());
    }

    /** 8.2 重放修复：loadPayload 按事件 revision 取 payload（旧 revision 不被最新覆盖）。 */
    @Test
    void loadPayloadReturnsExactRevisionNotLatest() throws Exception {
        byte[] data7 = gzipPayload("data-7");
        byte[] data8 = gzipPayload("data-8");
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, data7));
        this.store.commit(KEY, 7);
        this.store.raiseRequired(KEY, 8);
        assertEquals(PutResult.OK, this.put(8, 1, data8));
        this.store.commit(KEY, 8);

        SnapshotObjectStore.Payload p7 = this.store.loadPayload(KEY, 7);
        assertNotNull(p7);
        assertEquals(7, p7.revision());
        assertEquals(sha(data7), p7.payloadHash());
        assertEquals(decompressedLength(data7), p7.uncompressedLength());
        assertArrayEquals(data7, p7.bytes());

        SnapshotObjectStore.Payload p8 = this.store.loadPayload(KEY, 8);
        assertNotNull(p8);
        assertEquals(8, p8.revision());
        assertArrayEquals(data8, p8.bytes());

        // 未存在 revision / 非法参数 → null（fail closed）
        assertNull(this.store.loadPayload(KEY, 6));
        assertNull(this.store.loadPayload(KEY, 0));
        assertNull(this.store.loadPayload(KEY, -1));
    }

    @Test
    void invalidateToCommitWindowNeverReturnsOldHit() throws Exception {
        // rev 7 提交后，INVALIDATE 到新快照提交之间 RESOLVE 必须返回 STALE（不得旧 HIT）
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);
        assertEquals(ResolveResult.Status.HIT, this.store.resolve(KEY, 0).status());

        this.store.raiseRequired(KEY, 8); // INVALIDATE
        assertEquals(ResolveResult.Status.STALE, this.store.resolve(KEY, 0).status(),
                "committed < required 期间不得返回旧 HIT");
        assertEquals(ResolveResult.Status.STALE, this.store.resolve(KEY, 7).status());

        assertEquals(PutResult.OK, this.put(8, 1, gzipPayload("data-8")));
        this.store.commit(KEY, 8);
        assertEquals(ResolveResult.Status.HIT, this.store.resolve(KEY, 8).status());
    }

    @Test
    void outOfOrderSnapshotCannotLowerRevision() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.REJECTED_OUT_OF_ORDER, this.put(6, 1, gzipPayload("old")));
        assertEquals(PutResult.REJECTED_OUT_OF_ORDER, this.put(8, 1, gzipPayload("future")));
        assertTrue(this.store.resolve(KEY, 0).status() == ResolveResult.Status.MISS
                        || this.store.resolve(KEY, 0).status() == ResolveResult.Status.STALE,
                "非法 put 不得产生可读对象");
    }

    @Test
    void replayWithDifferentContentRejectedAndDoesNotOverwrite() throws Exception {
        this.store.raiseRequired(KEY, 7);
        byte[] first = gzipPayload("canonical");
        assertEquals(PutResult.OK, this.put(7, 1, first));
        this.store.commit(KEY, 7);

        // 同 revision 重放但内容不同：拒收，且不覆盖已提交对象
        this.store.raiseRequired(KEY, 8);
        assertEquals(PutResult.OK, this.put(8, 1, gzipPayload("newer")));
        this.store.commit(KEY, 8);

        byte[] tampered = gzipPayload("corrupted-replay");
        assertEquals(PutResult.REJECTED_OUT_OF_ORDER, this.put(7, 1, tampered),
                "低 revision 重放必须被拒");
        assertEquals(8, this.store.resolve(KEY, 0).committedRevision(),
                "已提交 revision 不得被重放降低");
    }

    @Test
    void idempotentRetryAfterLostAck() throws Exception {
        // ack 丢失后的重发：同 revision 同内容 → 幂等 OK；同 revision 不同内容 → 拒收不覆盖
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("canonical")));
        this.store.commit(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("canonical")),
                "同 revision 同 hash 重发必须幂等");
        assertEquals(PutResult.REJECTED_OUT_OF_ORDER, this.put(7, 1, gzipPayload("tampered")),
                "同 revision 异 hash 必须拒收");
        assertEquals(ResolveResult.Status.HIT, this.store.resolve(KEY, 7).status());
    }

    @Test
    void hashMismatchRejectedWithoutFiles() throws Exception {
        this.store.raiseRequired(KEY, 7);
        byte[] payload = gzipPayload("data");
        assertEquals(PutResult.REJECTED_HASH_MISMATCH,
                this.store.put(KEY, 7, 1, "NBT_CHUNK_V1", "GZIP", 4, "deadbeef", payload));
        assertEquals(ResolveResult.Status.MISS, this.store.resolve(KEY, 0).status());
    }

    @Test
    void sizeMismatchRejected() throws Exception {
        this.store.raiseRequired(KEY, 7);
        byte[] payload = new byte[] {1, 2, 3}; // 非 GZIP
        assertEquals(PutResult.REJECTED_SIZE_MISMATCH,
                this.store.put(KEY, 7, 1, "NBT_CHUNK_V1", "GZIP", 999, sha(payload), payload));
    }

    @Test
    void unsupportedFormatOrCompressionRejected() throws Exception {
        this.store.raiseRequired(KEY, 7);
        byte[] payload = gzipPayload("x");
        assertEquals(PutResult.REJECTED_UNSUPPORTED,
                this.store.put(KEY, 7, 1, "BOGUS_FORMAT", "GZIP", 1, sha(payload), payload));
        assertEquals(PutResult.REJECTED_UNSUPPORTED,
                this.store.put(KEY, 7, 1, "NBT_CHUNK_V1", "LZ4", 1, sha(payload), payload));
    }

    @Test
    void epochMismatchRejectedAndEpochSwitchInvalidates() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);
        // 维度 epoch 未切换时，跨 epoch 快照必须被拒
        this.store.raiseRequired(KEY, 8);
        assertEquals(PutResult.REJECTED_EPOCH_MISMATCH, this.put(8, 2, gzipPayload("data-8")));

        // 维度 epoch 切换（WORLD_EPOCH）后：required(8)>committed(7) → STALE（旧对象不可读）
        this.store.announceEpoch(DIM, 2);
        assertEquals(ResolveResult.Status.STALE, this.store.resolve(KEY, 0).status());
        assertEquals(PutResult.OK, this.put(8, 2, gzipPayload("data-8")));
        this.store.commit(KEY, 8);
        assertEquals(ResolveResult.Status.HIT, this.store.resolve(KEY, 8).status());
    }

    @Test
    void epochSwitchMakesOldObjectsIncompatible() throws Exception {
        // required==committed 时 epoch 不匹配 → INCOMPATIBLE（指纹/epoch 不一致不得装载）
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);
        this.store.announceEpoch(DIM, 2);
        assertEquals(ResolveResult.Status.INCOMPATIBLE, this.store.resolve(KEY, 0).status());
    }

    @Test
    void commitBelowRequiredThrows() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertThrows(IllegalStateException.class, () -> this.store.commit(KEY, 6));
    }

    @Test
    void recoveryRestoresRequiredAndCommitted() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);
        this.store.raiseRequired(KEY, 8); // INVALIDATE 未提交

        SnapshotObjectStore reopened = new SnapshotObjectStore(this.root);
        reopened.recover();
        assertTrue(reopened.available());
        assertEquals(8, reopened.indexOf(KEY).required());
        assertEquals(7, reopened.indexOf(KEY).committed());
        // required(8) > committed(7) → STALE（重启后不倒退，窗口语义保留）
        assertEquals(ResolveResult.Status.STALE, reopened.resolve(KEY, 0).status());
        assertNotNull(reopened.loadCommittedPayload(KEY));
    }

    @Test
    void recoveryDetectsCorruptCommittedAboveRequired() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);
        // 人为破坏：committed 高于 required（模拟损坏）
        Path committedFile = this.root.resolve("default").resolve("minecraft").resolve("overworld")
                .resolve("3").resolve("-1").resolve("committed.rev");
        Files.writeString(committedFile, "9");

        SnapshotObjectStore reopened = new SnapshotObjectStore(this.root);
        reopened.recover();
        assertEquals(ResolveResult.Status.UNAVAILABLE, reopened.resolve(KEY, 0).status(),
                "损坏区块 fail closed，不猜测修复");
    }

    @Test
    void recoveryDetectsMissingCommittedObject() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);
        // 删除已提交对象文件
        Path snapshotFile = this.root.resolve("default").resolve("minecraft").resolve("overworld")
                .resolve("3").resolve("-1").resolve("7").resolve("snapshot.nbt.gz");
        Files.delete(snapshotFile);

        SnapshotObjectStore reopened = new SnapshotObjectStore(this.root);
        reopened.recover();
        assertEquals(ResolveResult.Status.UNAVAILABLE, reopened.resolve(KEY, 0).status());
        assertNull(reopened.loadCommittedPayload(KEY));
    }

    @Test
    void minRevisionResolveStale() throws Exception {
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("data-7")));
        this.store.commit(KEY, 7);
        assertEquals(ResolveResult.Status.HIT, this.store.resolve(KEY, 7).status());
        assertEquals(ResolveResult.Status.STALE, this.store.resolve(KEY, 8).status());
    }

    @Test
    void regionEnumerationForAdminInvalidate() throws Exception {
        ChunkRef other = ChunkRef.of(DIM, 33, -1); // region 1,-1
        this.store.raiseRequired(KEY, 1);
        this.store.raiseRequired(other, 1);
        assertEquals(1, this.store.chunksInRegion(DIM, 0, -1).size());
        assertEquals(1, this.store.chunksInRegion(DIM, 1, -1).size());
        assertEquals(2, this.store.chunksInDimension(DIM).size());
    }

    @Test
    void serverNameScopesDataIsolation() throws Exception {
        // 同一 root 下两个 serverName 的对象库互不可见（缓存隔离键，CACHE_PROTOCOL §1）
        this.store.raiseRequired(KEY, 7);
        assertEquals(PutResult.OK, this.put(7, 1, gzipPayload("lobby-1-data")));
        this.store.commit(KEY, 7);
        assertEquals(ResolveResult.Status.HIT, this.store.resolve(KEY, 0).status());

        SnapshotObjectStore other = new SnapshotObjectStore(this.root, "lobby-2");
        other.recover();
        assertEquals(ResolveResult.Status.MISS, other.resolve(KEY, 0).status());
        assertNull(other.indexOf(KEY));

        // 目录布局：<root>/<serverName>/<namespace>/<path>/...（setUp 的 store 使用缺省 serverName）
        assertTrue(Files.isDirectory(this.root.resolve(SnapshotObjectStore.DEFAULT_SERVER_NAME)
                .resolve("minecraft").resolve("overworld")));
        assertFalse(Files.exists(this.root.resolve("minecraft").resolve("overworld")));
        assertFalse(Files.exists(this.root.resolve("lobby-2").resolve("minecraft").resolve("overworld")));
    }

    @Test
    void serverNameRejectBlank() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotObjectStore(this.root, " "));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotObjectStore(this.root, null));
    }
}

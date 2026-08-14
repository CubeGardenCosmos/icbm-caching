package cn.cubegarden.icbm.caching.source.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import cn.cubegarden.icbm.caching.source.revision.ModpackFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 本地对象库校验测试：required/committed 顺序、哈希/大小/epoch 校验、文件布局。 */
class LocalSnapshotSinkTest {

    private static final ChunkKey KEY = ChunkKey.of(Identifier.fromNamespaceAndPath("minecraft", "overworld"), 3, -1);

    @TempDir
    Path root;

    private LocalSnapshotSink sink;

    @BeforeEach
    void setUp() {
        this.sink = new LocalSnapshotSink(this.root);
    }

    private Path chunkDir() {
        return this.root.resolve("minecraft").resolve("overworld").resolve("3").resolve("-1");
    }

    /** 构造有效 GZIP payload（未压缩长度为 raw 长度）。 */
    private static byte[] gzip(byte[] raw) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static ChunkSnapshot snapshot(long revision, long epoch, byte[] rawPayload) throws Exception {
        return ChunkSnapshot.of(KEY, revision, epoch, gzip(rawPayload), rawPayload.length);
    }

    @Test
    void happyPathWritesRequiredSnapshotAndCommitted() throws Exception {
        this.sink.raiseRequiredRevision(KEY, 7);
        assertEquals(SnapshotSink.PutResult.OK, this.sink.putSnapshot(snapshot(7, 1, new byte[] {1, 2, 3, 4})));
        this.sink.commitRevision(KEY, 7);

        Path dir = this.chunkDir();
        assertEquals("7", Files.readString(dir.resolve("required.rev")).trim());
        assertEquals("7", Files.readString(dir.resolve("committed.rev")).trim());
        assertEquals("1", Files.readString(dir.resolve("epoch")).trim());
        assertTrue(Files.exists(dir.resolve("7/snapshot.nbt.gz")));
        assertTrue(Files.exists(dir.resolve("7/metadata.json")));
    }

    @Test
    void outOfOrderSnapshotRejected() throws Exception {
        this.sink.raiseRequiredRevision(KEY, 7);
        assertEquals(SnapshotSink.PutResult.REJECTED_OUT_OF_ORDER,
                this.sink.putSnapshot(snapshot(8, 1, new byte[] {1})));
        assertFalse(Files.exists(this.chunkDir().resolve("8")));
    }

    @Test
    void hashMismatchRejectedWithoutFiles() {
        byte[] payload = new byte[] {1, 2, 3};
        this.sink.raiseRequiredRevision(KEY, 7);
        ChunkSnapshot tampered = new ChunkSnapshot(
                KEY, 7, 1, ChunkSnapshot.FORMAT_NBT_CHUNK_V1, ChunkSnapshot.COMPRESSION_GZIP,
                payload.length, "deadbeef", payload);
        assertEquals(SnapshotSink.PutResult.REJECTED_HASH_MISMATCH, this.sink.putSnapshot(tampered));
        assertFalse(Files.exists(this.chunkDir().resolve("7")));
    }

    @Test
    void sizeMismatchRejected() {
        byte[] payload = new byte[] {1, 2, 3};
        this.sink.raiseRequiredRevision(KEY, 7);
        // 未压缩长度声明与实际解压长度不符（payload 本身非 GZIP，解压失败即视为不一致）
        ChunkSnapshot badSize = new ChunkSnapshot(
                KEY, 7, 1, ChunkSnapshot.FORMAT_NBT_CHUNK_V1, ChunkSnapshot.COMPRESSION_GZIP,
                999, ModpackFingerprint.sha256Hex(payload), payload);
        assertEquals(SnapshotSink.PutResult.REJECTED_SIZE_MISMATCH, this.sink.putSnapshot(badSize));
        assertFalse(Files.exists(this.chunkDir().resolve("7")));
    }

    @Test
    void epochMismatchRejected() throws Exception {
        byte[] payload = new byte[] {1, 2, 3};
        this.sink.raiseRequiredRevision(KEY, 7);
        assertEquals(SnapshotSink.PutResult.OK, this.sink.putSnapshot(snapshot(7, 1, payload)));
        this.sink.raiseRequiredRevision(KEY, 8);
        assertEquals(SnapshotSink.PutResult.REJECTED_EPOCH_MISMATCH,
                this.sink.putSnapshot(snapshot(8, 2, payload)));
    }

    @Test
    void commitBelowRequiredIsRejected() {
        this.sink.raiseRequiredRevision(KEY, 7);
        assertThrows(IllegalStateException.class, () -> this.sink.commitRevision(KEY, 6));
    }
}

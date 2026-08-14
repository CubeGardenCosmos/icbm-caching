package cn.cubegarden.icbm.caching.virtual.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * committed snapshot 装载 fail-closed 测试（验收：缓存未命中或 epoch/fingerprint 不匹配时
 * 不展示旧区块；虚拟后端不写对象库或源服世界——装载只读）。
 */
class SnapshotRepositoryTest {

    private static final DimensionRef DIMENSION = new DimensionRef("minecraft", "overworld");

    private Path storeRoot;
    private SnapshotObjectStore store;
    private SnapshotRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        this.storeRoot = Files.createTempDirectory("icbm-vb-store");
        this.store = new SnapshotObjectStore(this.storeRoot);
        this.store.recover();
        this.repository = new SnapshotRepository(this.store, DIMENSION, RegistryBundle.minimal(), -4, 19);
    }

    @AfterEach
    void tearDown() {
        this.repository.close();
        this.store.close();
    }

    private byte[] snapshotPayload() {
        return Nbt.toGzipBytes(Nbt.compound());
    }

    private int snapshotUncompressedLength() {
        return 4; // 空 compound 文件格式 = type(1) + 根名(2) + END(1)
    }

    @Test
    void masterDisconnectedFailsClosed() {
        this.repository.setMasterConnected(false);
        assertNull(this.repository.resolveAndEncode(0, 0));
    }

    @Test
    void noCommittedObjectReturnsNull() {
        this.repository.setMasterConnected(true);
        assertNull(this.repository.resolveAndEncode(0, 0));
    }

    @Test
    void staleChunkReturnsNull() {
        // INVALIDATE（required=2）后 committed=1 尚未提交 → 不展示旧区块
        ChunkRef key = ChunkRef.of(DIMENSION, 0, 0);
        this.store.announceEpoch(DIMENSION, 7);
        this.store.raiseRequired(key, 2);
        byte[] payload = this.snapshotPayload();
        int uncompressedLength = this.snapshotUncompressedLength();
        assertEquals(PutResult.OK, this.store.put(key, 2, 7, "NBT_CHUNK_V1", "GZIP",
                uncompressedLength, sha256(payload), payload));
        // committed 未提交：required=2 committed=0 → null
        this.repository.setMasterConnected(true);
        assertNull(this.repository.resolveAndEncode(0, 0));
        this.store.commit(key, 2);
        assertNotNull(this.repository.resolveAndEncode(0, 0));
    }

    @Test
    void epochMismatchFailsClosed() {
        ChunkRef key = ChunkRef.of(DIMENSION, 1, 1);
        this.store.announceEpoch(DIMENSION, 7);
        this.store.raiseRequired(key, 1); // PUT 要求 revision == required（源提交流程）
        byte[] payload = this.snapshotPayload();
        this.store.put(key, 1, 7, "NBT_CHUNK_V1", "GZIP",
                this.snapshotUncompressedLength(), sha256(payload), payload);
        this.store.commit(key, 1);
        this.store.announceEpoch(DIMENSION, 8); // epoch 切换 → 旧对象不可读
        this.repository.setMasterConnected(true);
        assertNull(this.repository.resolveAndEncode(1, 1));
    }

    @Test
    void servableEncodesAndCaches() {
        ChunkRef key = ChunkRef.of(DIMENSION, 2, 3);
        this.store.announceEpoch(DIMENSION, 7);
        this.store.raiseRequired(key, 1); // PUT 要求 revision == required（源提交流程）
        byte[] payload = this.snapshotPayload();
        this.store.put(key, 1, 7, "NBT_CHUNK_V1", "GZIP",
                this.snapshotUncompressedLength(), sha256(payload), payload);
        this.store.commit(key, 1);
        this.repository.setMasterConnected(true);

        SnapshotRepository.EncodedChunk first = this.repository.resolveAndEncode(2, 3);
        assertNotNull(first);
        assertSame(first, this.repository.resolveAndEncode(2, 3)); // 缓存命中（同 revision）
        assertNotNull(first.chunkDataPayload());
        assertNotNull(first.lightDataPayload());
    }

    private static String sha256(byte[] data) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

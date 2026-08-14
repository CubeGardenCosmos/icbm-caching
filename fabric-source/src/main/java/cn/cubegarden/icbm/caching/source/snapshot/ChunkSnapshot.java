package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import cn.cubegarden.icbm.caching.source.revision.ModpackFingerprint;

/**
 * 版本化区块 NBT 快照（对应 CACHE_PROTOCOL.md PUT_SNAPSHOT 的字段：
 * dimensionId/chunkX/chunkZ/revision/snapshotFormat/compression/uncompressedLength/payloadHash/payload）。
 */
public record ChunkSnapshot(
        ChunkKey key,
        long revision,
        long epoch,
        String snapshotFormat,
        String compression,
        int uncompressedLength,
        String payloadHash,
        byte[] payload
) {

    /** 阶段1 快照格式：vanilla 区块 NBT（SerializableChunkData.write() 输出），GZIP 压缩。 */
    public static final String FORMAT_NBT_CHUNK_V1 = "NBT_CHUNK_V1";
    public static final String COMPRESSION_GZIP = "GZIP";

    public static ChunkSnapshot of(ChunkKey key, long revision, long epoch, byte[] payload, int uncompressedLength) {
        return new ChunkSnapshot(
                key, revision, epoch,
                FORMAT_NBT_CHUNK_V1, COMPRESSION_GZIP,
                uncompressedLength, ModpackFingerprint.sha256Hex(payload), payload
        );
    }
}

package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import cn.cubegarden.icbm.caching.source.revision.ModpackFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 阶段1 本地对象库 sink：按 CACHE_PROTOCOL 顺序实现 required → put（校验）→ committed。
 *
 * <p>布局（{@code <root>/<dimension>/<x>/<z>/}）：
 * <ul>
 *   <li>{@code required.rev}、{@code committed.rev}：原子写入的 revision 标记；</li>
 *   <li>{@code epoch}：该区块首次快照的 epoch（此后必须一致）；</li>
 *   <li>{@code <revision>/snapshot.nbt.gz}：GZIP 的区块 NBT 快照；</li>
 *   <li>{@code <revision>/metadata.json}：epoch/format/compression/hash/size。</li>
 * </ul>
 *
 * <p>校验失败（哈希/大小/epoch/顺序）返回对应拒绝原因且不落盘新快照；控制服务不可用时
 * 由上层 fail closed（保持 required，虚拟后端不得以旧基线服务）。
 */
public final class LocalSnapshotSink implements SnapshotSink {

    private static final String REQUIRED_FILE = "required.rev";
    private static final String COMMITTED_FILE = "committed.rev";
    private static final String EPOCH_FILE = "epoch";
    private static final String SNAPSHOT_FILE = "snapshot.nbt.gz";
    private static final String METADATA_FILE = "metadata.json";

    private final Path root;

    public LocalSnapshotSink(Path root) {
        this.root = root;
    }

    @Override
    public void raiseRequiredRevision(ChunkKey key, long revision) {
        Path dir = this.dir(key);
        writeAtomically(dir.resolve(REQUIRED_FILE), Long.toString(revision));
    }

    @Override
    public PutResult putSnapshot(ChunkSnapshot snapshot) {
        ChunkKey key = snapshot.key();
        Path dir = this.dir(key);

        // 顺序：revision 必须等于当前 required（防乱序覆盖）
        long required = readLong(dir.resolve(REQUIRED_FILE), -1L);
        if (snapshot.revision() != required) {
            return PutResult.REJECTED_OUT_OF_ORDER;
        }

        // epoch：首次快照记录，之后必须一致（防跨 epoch 复用）
        long storedEpoch = readLong(dir.resolve(EPOCH_FILE), -1L);
        if (storedEpoch >= 0 && storedEpoch != snapshot.epoch()) {
            return PutResult.REJECTED_EPOCH_MISMATCH;
        }

        // 完整性：哈希与未压缩长度（解压校验，对应 PUT_SNAPSHOT.uncompressedLength）
        if (!ModpackFingerprint.sha256Hex(snapshot.payload()).equals(snapshot.payloadHash())) {
            return PutResult.REJECTED_HASH_MISMATCH;
        }
        if (decompressedLength(snapshot.payload()) != snapshot.uncompressedLength()) {
            return PutResult.REJECTED_SIZE_MISMATCH;
        }

        try {
            Path revisionDir = dir.resolve(Long.toString(snapshot.revision()));
            Files.createDirectories(revisionDir);
            writeAtomically(revisionDir.resolve(SNAPSHOT_FILE), snapshot.payload());
            writeAtomically(revisionDir.resolve(METADATA_FILE), metadataJson(snapshot));
            if (storedEpoch < 0) {
                writeAtomically(dir.resolve(EPOCH_FILE), Long.toString(snapshot.epoch()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("快照落盘失败: " + key, e);
        }
        return PutResult.OK;
    }

    @Override
    public void commitRevision(ChunkKey key, long revision) {
        Path dir = this.dir(key);
        long required = readLong(dir.resolve(REQUIRED_FILE), -1L);
        if (revision < required) {
            throw new IllegalStateException("committed revision 低于 required: key=" + key + " rev=" + revision);
        }
        writeAtomically(dir.resolve(COMMITTED_FILE), Long.toString(revision));
    }

    @Override
    public void close() {
        // 本地文件 sink 无打开句柄
    }

    private Path dir(ChunkKey key) {
        return this.root
                .resolve(key.dimension().getNamespace())
                .resolve(key.dimension().getPath())
                .resolve(Integer.toString(key.x()))
                .resolve(Integer.toString(key.z()));
    }

    private static String metadataJson(ChunkSnapshot snapshot) {
        return "{\"snapshotFormat\":\"" + snapshot.snapshotFormat()
                + "\",\"compression\":\"" + snapshot.compression()
                + "\",\"epoch\":" + snapshot.epoch()
                + ",\"revision\":" + snapshot.revision()
                + ",\"uncompressedLength\":" + snapshot.uncompressedLength()
                + ",\"payloadHash\":\"" + snapshot.payloadHash() + "\"}\n";
    }

    private static void writeAtomically(Path file, byte[] data) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeAtomically(Path file, String content) {
        try {
            writeAtomically(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("写入失败: " + file, e);
        }
    }

    private static long decompressedLength(byte[] gzipPayload) {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gzipPayload))) {
            long total = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
            }
            return total;
        } catch (java.io.IOException e) {
            return -1L; // 无法解压：视为大小校验不一致（fail closed）
        }
    }

    private static long readLong(Path file, long defaultValue) {
        try {
            return Long.parseLong(Files.readString(file).trim());
        } catch (IOException e) {
            return defaultValue;
        }
    }
}

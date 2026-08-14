package cn.cubegarden.icbm.caching.protocol.store;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPInputStream;

/**
 * 版本化区块 NBT 快照对象库（CACHE_PROTOCOL.md §2 PUT_SNAPSHOT + 草稿 phase2-control-protocol §4.1/4.3）。
 *
 * <p>磁盘布局（唯一实现，两侧复用）：
 * <pre>
 * {@code <root>/<namespace>/<path>/epoch}                       维度当前 epoch（原子写）
 * {@code <root>/<namespace>/<path>/<x>/<z>/required.rev}        requiredRevision（仅增）
 * {@code <root>/<namespace>/<path>/<x>/<z>/committed.rev}       committedRevision（原子写）
 * {@code <root>/<namespace>/<path>/<x>/<z>/<rev>/snapshot.nbt.gz}
 * {@code <root>/<namespace>/<path>/<x>/<z>/<rev>/metadata.json} 格式/epoch/hash/大小
 * </pre>
 *
 * <p>一致性（验收 2：乱序、重放、损坏快照不能降低 revision 或覆盖新对象）：
 * <ul>
 *   <li>required 仅增（max 语义，INVALIDATE 幂等）；</li>
 *   <li>put 校验顺序：格式白名单 → revision==required → epoch==维度 epoch → hash → 解压长度 →
 *       重放守卫（同 revision 目录已存在且 hash 不同 → 拒收，绝不覆盖）；</li>
 *   <li>commit 要求 revision &gt;= required（违反抛 {@link IllegalStateException}）；</li>
 *   <li>恢复：扫描目录重建索引；committed&gt;required、对象缺失或 metadata 不可解析 →
 *       该区块标记损坏并 fail closed（resolve 返回 UNAVAILABLE），不做猜测性修复。</li>
 * </ul>
 */
public final class SnapshotObjectStore implements AutoCloseable {

    private static final String EPOCH_FILE = "epoch";
    private static final String REQUIRED_FILE = "required.rev";
    private static final String COMMITTED_FILE = "committed.rev";
    private static final String SNAPSHOT_FILE = "snapshot.nbt.gz";
    private static final String METADATA_FILE = "metadata.json";

    /** 单区块恢复后的索引（required/committed/对象哈希/对象 epoch/解压长度/损坏标志）。 */
    public record ChunkIndex(long required, long committed, String committedHash, long committedEpoch,
                             int committedUncompressedLength, boolean corrupt) {

        public static ChunkIndex empty() {
            return new ChunkIndex(0, 0, "", 0, 0, false);
        }
    }

    /** resolve 结果（对应 RESOLVE_CHUNK_RES 字段）。 */
    public record ResolveResult(
            Status status, long committedRevision, String objectId, String objectHash
    ) {

        public enum Status {
            HIT, MISS, STALE, INCOMPATIBLE, UNAVAILABLE,
        }
    }

    private final Path root;
    private final ConcurrentMap<ChunkRef, ChunkIndex> index = new ConcurrentHashMap<>();
    private final ConcurrentMap<DimensionRef, Long> dimensionEpochs = new ConcurrentHashMap<>();
    private volatile boolean ready;

    public SnapshotObjectStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return this.root;
    }

    public boolean available() {
        return this.ready;
    }

    /** 启动恢复：扫描目录重建索引；任一区块损坏 → 该区块 fail closed，其余继续可用。 */
    public void recover() throws IOException {
        this.index.clear();
        this.dimensionEpochs.clear();
        if (!Files.isDirectory(this.root)) {
            this.ready = true; // 空库合法
            return;
        }
        try (var namespaces = Files.list(this.root)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                try (var paths = Files.list(namespace)) {
                    for (Path path : paths.filter(Files::isDirectory).toList()) {
                        DimensionRef dimension = new DimensionRef(namespace.getFileName().toString(), path.getFileName().toString());
                        long epoch = readLong(path.resolve(EPOCH_FILE), 0L);
                        if (epoch > 0) {
                            this.dimensionEpochs.put(dimension, epoch);
                        }
                        this.recoverDimension(dimension, path);
                    }
                }
            }
        }
        this.ready = true;
    }

    private void recoverDimension(DimensionRef dimension, Path dimensionDir) throws IOException {
        try (var xDirs = Files.list(dimensionDir)) {
            for (Path xDir : xDirs.filter(Files::isDirectory).toList()) {
                int x = Integer.parseInt(xDir.getFileName().toString());
                try (var zDirs = Files.list(xDir)) {
                    for (Path zDir : zDirs.filter(Files::isDirectory).toList()) {
                        int z = Integer.parseInt(zDir.getFileName().toString());
                        ChunkRef key = ChunkRef.of(dimension, x, z);
                        this.recoverChunk(key, zDir);
                    }
                }
            }
        }
    }

    private void recoverChunk(ChunkRef key, Path chunkDir) {
        long required = readLong(chunkDir.resolve(REQUIRED_FILE), 0L);
        long committed = readLong(chunkDir.resolve(COMMITTED_FILE), 0L);
        if (committed > required) {
            // 违反提交顺序：损坏，fail closed
            this.index.put(key, new ChunkIndex(required, committed, "", 0, 0, true));
            return;
        }
        if (committed > 0) {
            Metadata meta = readMetadata(chunkDir.resolve(Long.toString(committed)).resolve(METADATA_FILE));
            boolean objectExists = Files.isRegularFile(chunkDir.resolve(Long.toString(committed)).resolve(SNAPSHOT_FILE));
            if (meta == null || !objectExists) {
                this.index.put(key, new ChunkIndex(required, committed, "", 0, 0, true));
                return;
            }
            this.index.put(key, new ChunkIndex(required, committed, meta.payloadHash(), meta.epoch(),
                    meta.uncompressedLength(), false));
        } else {
            this.index.put(key, new ChunkIndex(required, committed, "", 0, 0, false));
        }
    }

    /** 声明维度新 epoch；旧对象（epoch 不匹配）立即不可读。 */
    public void announceEpoch(DimensionRef dimension, long epoch) {
        if (epoch <= 0) {
            throw new IllegalArgumentException("invalid epoch: " + epoch);
        }
        synchronized (this) {
            Path file = dimensionDir(dimension).resolve(EPOCH_FILE);
            writeAtomically(file, Long.toString(epoch));
            this.dimensionEpochs.put(dimension, epoch);
        }
    }

    /** 当前维度 epoch（未声明返回 0）。 */
    public long dimensionEpoch(DimensionRef dimension) {
        return this.dimensionEpochs.getOrDefault(dimension, 0L);
    }

    /** 提高 requiredRevision（仅增，幂等；对应 INVALIDATE）。 */
    public void raiseRequired(ChunkRef key, long revision) {
        synchronized (this) {
            ChunkIndex current = this.index.getOrDefault(key, ChunkIndex.empty());
            if (revision <= current.required()) {
                return; // 仅增：重放/乱序不能降低 required
            }
            this.index.put(key, new ChunkIndex(revision, current.committed(), current.committedHash(),
                    current.committedEpoch(), current.committedUncompressedLength(), current.corrupt()));
            Path dir = chunkDir(key);
            writeAtomically(dir.resolve(REQUIRED_FILE), Long.toString(revision));
        }
    }

    /**
     * 持久化并校验快照（对应 PUT_SNAPSHOT）。校验失败返回拒绝原因且不落盘；
     * 重放守卫：同 revision 目录已存在且 hash 相同 → 幂等 OK，hash 不同 → 拒收（不覆盖新对象）。
     */
    public PutResult put(
            ChunkRef key,
            long revision,
            long epoch,
            String snapshotFormat,
            String compression,
            int uncompressedLength,
            String payloadHash,
            byte[] payload
    ) {
        if (!"NBT_CHUNK_V1".equals(snapshotFormat)) {
            return PutResult.REJECTED_UNSUPPORTED;
        }
        if (!"GZIP".equals(compression)) {
            return PutResult.REJECTED_UNSUPPORTED;
        }
        synchronized (this) {
            ChunkIndex current = this.index.getOrDefault(key, ChunkIndex.empty());
            if (current.corrupt()) {
                return PutResult.REJECTED_UNAVAILABLE;
            }
            if (revision != current.required()) {
                return PutResult.REJECTED_OUT_OF_ORDER;
            }
            long dimensionEpoch = this.dimensionEpochs.getOrDefault(key.dimension(), 0L);
            if (dimensionEpoch == 0L) {
                // 首次快照隐式确立维度 epoch（与阶段1 本地 sink 语义一致）
                this.dimensionEpochs.put(key.dimension(), epoch);
                writeAtomically(dimensionDir(key.dimension()).resolve(EPOCH_FILE), Long.toString(epoch));
            } else if (epoch != dimensionEpoch) {
                return PutResult.REJECTED_EPOCH_MISMATCH;
            }
            if (!sha256Hex(payload).equals(payloadHash)) {
                return PutResult.REJECTED_HASH_MISMATCH;
            }
            if (decompressedLength(payload) != uncompressedLength) {
                return PutResult.REJECTED_SIZE_MISMATCH;
            }
            Path revisionDir = chunkDir(key).resolve(Long.toString(revision));
            Path metadataFile = revisionDir.resolve(METADATA_FILE);
            if (Files.isRegularFile(metadataFile)) {
                Metadata existing = readMetadata(metadataFile);
                if (existing != null && !existing.payloadHash().equals(payloadHash)) {
                    // 重放但内容不同：拒绝覆盖
                    return PutResult.REJECTED_OUT_OF_ORDER;
                }
                // 同 revision 同 hash：幂等（ack 丢失后的重发）
            }
            try {
                Files.createDirectories(revisionDir);
                writeAtomically(revisionDir.resolve(SNAPSHOT_FILE), payload);
                writeAtomically(metadataFile, metadataJson(
                        snapshotFormat, compression, epoch, revision, uncompressedLength, payloadHash
                ));
            } catch (IOException e) {
                throw new IllegalStateException("snapshot persist failed: " + key + " rev=" + revision, e);
            }
            return PutResult.OK;
        }
    }

    /** 原子设置 committedRevision；revision &lt; required 视为协议违规（抛异常，调用方断连）。 */
    public void commit(ChunkRef key, long revision) {
        synchronized (this) {
            ChunkIndex current = this.index.getOrDefault(key, ChunkIndex.empty());
            if (revision < current.required()) {
                throw new IllegalStateException("commit below required: " + key + " rev=" + revision);
            }
            String hash = "";
            long epoch = 0;
            int uncompressedLength = 0;
            if (revision > 0) {
                Metadata meta = readMetadata(chunkDir(key).resolve(Long.toString(revision)).resolve(METADATA_FILE));
                if (meta == null) {
                    throw new IllegalStateException("commit for missing object: " + key + " rev=" + revision);
                }
                hash = meta.payloadHash();
                epoch = meta.epoch();
                uncompressedLength = meta.uncompressedLength();
            }
            this.index.put(key, new ChunkIndex(current.required(), revision, hash, epoch,
                    uncompressedLength, current.corrupt()));
            writeAtomically(chunkDir(key).resolve(COMMITTED_FILE), Long.toString(revision));
        }
    }

    /** RESOLVE_CHUNK：INVALIDATE（committed&lt;required）→ STALE；epoch 不匹配 → INCOMPATIBLE；损坏 → UNAVAILABLE。 */
    public ResolveResult resolve(ChunkRef key, long minRevision) {
        if (!this.ready) {
            return new ResolveResult(ResolveResult.Status.UNAVAILABLE, 0, "", "");
        }
        ChunkIndex current = this.index.get(key);
        if (current == null || current.committed() == 0) {
            return new ResolveResult(ResolveResult.Status.MISS, 0, "", "");
        }
        if (current.corrupt()) {
            return new ResolveResult(ResolveResult.Status.UNAVAILABLE, current.committed(), "", "");
        }
        if (current.committed() < current.required()) {
            return new ResolveResult(ResolveResult.Status.STALE, current.committed(), "", "");
        }
        if (current.committed() < minRevision) {
            return new ResolveResult(ResolveResult.Status.STALE, current.committed(), "", "");
        }
        long dimensionEpoch = this.dimensionEpochs.getOrDefault(key.dimension(), 0L);
        if (dimensionEpoch > 0 && current.committedEpoch() != dimensionEpoch) {
            return new ResolveResult(ResolveResult.Status.INCOMPATIBLE, current.committed(), "", "");
        }
        return new ResolveResult(
                ResolveResult.Status.HIT, current.committed(),
                objectId(key, current.committed()), current.committedHash()
        );
    }

    /** 读取 committed 对象 payload（master 重拉 / slave 装载用）。对象缺失返回 null。 */
    public byte[] loadCommittedPayload(ChunkRef key) {
        ChunkIndex current = this.index.get(key);
        if (current == null || current.committed() <= 0 || current.corrupt()) {
            return null;
        }
        Path file = chunkDir(key).resolve(Long.toString(current.committed())).resolve(SNAPSHOT_FILE);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** committed 对象的解压长度（metadata 持久化值；无对象返回 0）。 */
    public int committedUncompressedLength(ChunkRef key) {
        ChunkIndex current = this.index.get(key);
        return current == null || current.corrupt() ? 0 : current.committedUncompressedLength();
    }

    /** committed 对象的 epoch（metadata 持久化值；无对象返回 0）。 */
    public long committedEpoch(ChunkRef key) {
        ChunkIndex current = this.index.get(key);
        return current == null || current.corrupt() ? 0 : current.committedEpoch();
    }

    /** 已知区块（含损坏）按区域枚举（ADMIN_INVALIDATE REGION 范围用）。 */
    public List<ChunkRef> chunksInRegion(DimensionRef dimension, int regionX, int regionZ) {
        List<ChunkRef> result = new ArrayList<>();
        for (ChunkRef key : this.index.keySet()) {
            if (!key.dimension().equals(dimension)) {
                continue;
            }
            if (Math.floorDiv(key.x(), 32) == regionX && Math.floorDiv(key.z(), 32) == regionZ) {
                result.add(key);
            }
        }
        return result;
    }

    public List<ChunkRef> chunksInDimension(DimensionRef dimension) {
        List<ChunkRef> result = new ArrayList<>();
        for (ChunkRef key : this.index.keySet()) {
            if (key.dimension().equals(dimension)) {
                result.add(key);
            }
        }
        return result;
    }

    /** 已知维度（ADMIN_INVALIDATE WORLD 范围用）。 */
    public List<DimensionRef> knownDimensions() {
        return new ArrayList<>(this.dimensionEpochs.keySet());
    }

    public ChunkIndex indexOf(ChunkRef key) {
        return this.index.get(key);
    }

    @Override
    public void close() {
        // 无打开句柄（每次原子写独立打开）
    }

    // ---- 内部工具 ----

    private Path dimensionDir(DimensionRef dimension) {
        return this.root.resolve(dimension.namespace()).resolve(dimension.path());
    }

    private Path chunkDir(ChunkRef key) {
        return dimensionDir(key.dimension()).resolve(Integer.toString(key.x())).resolve(Integer.toString(key.z()));
    }

    private static String objectId(ChunkRef key, long revision) {
        return key.dimension().id() + "/" + key.x() + "/" + key.z() + "/" + revision;
    }

    private record Metadata(String snapshotFormat, String compression, long epoch, long revision,
                            int uncompressedLength, String payloadHash) {
    }

    private static String metadataJson(
            String format, String compression, long epoch, long revision, int uncompressedLength, String hash
    ) {
        return "{\"snapshotFormat\":\"" + format
                + "\",\"compression\":\"" + compression
                + "\",\"epoch\":" + epoch
                + ",\"revision\":" + revision
                + ",\"uncompressedLength\":" + uncompressedLength
                + ",\"payloadHash\":\"" + hash + "\"}\n";
    }

    /** 解析我们自身写出的扁平 JSON（字段顺序固定；不引入 JSON 依赖）。 */
    private static Metadata readMetadata(Path file) {
        try {
            String content = Files.readString(file);
            return new Metadata(
                    jsonString(content, "snapshotFormat"),
                    jsonString(content, "compression"),
                    jsonLong(content, "epoch"),
                    jsonLong(content, "revision"),
                    (int) jsonLong(content, "uncompressedLength"),
                    jsonString(content, "payloadHash")
            );
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String jsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        start += pattern.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated string: " + key);
        }
        return json.substring(start, end);
    }

    private static long jsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        start += pattern.length();
        int end = json.indexOf(',', start);
        if (end < 0) {
            end = json.indexOf('}', start);
        }
        if (end < 0) {
            throw new IllegalArgumentException("unterminated number: " + key);
        }
        return Long.parseLong(json.substring(start, end).trim());
    }

    private static void writeAtomically(Path file, String content) {
        writeAtomically(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path file, byte[] data) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("atomic write failed: " + file, e);
        }
    }

    private static long readLong(Path file, long defaultValue) {
        try {
            return Long.parseLong(Files.readString(file).trim());
        } catch (IOException e) {
            return defaultValue;
        }
    }

    private static int decompressedLength(byte[] gzipPayload) {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipPayload))) {
            long total = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
            }
            return (int) total;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

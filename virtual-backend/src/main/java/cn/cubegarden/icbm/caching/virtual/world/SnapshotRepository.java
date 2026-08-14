package cn.cubegarden.icbm.caching.virtual.world;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * committed snapshot 装载仓库（CACHE_PROTOCOL.md §2 RESOLVE_CHUNK + ServeGate 语义）：
 * 只有满足全部条件才把本地对象作为客户端基线（fail closed）：
 * <ol>
 *   <li>master 控制连接存活（否则无法证明 required 水位最新 → 拒绝一切）；</li>
 *   <li>存在非损坏 committed 对象且 committed &gt;= required；</li>
 *   <li>对象 epoch == 维度当前 epoch。</li>
 * </ol>
 * 未命中/落后/不匹配 → {@code null}（不展示旧区块；客户端保持空区块视图）。
 *
 * <p>编码缓存：同一 (x,z,revision) 的编码结果不可变，按 revision 缓存，避免每会话重复编码。
 */
public final class SnapshotRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotRepository.class);

    private final SnapshotObjectStore store;
    private final DimensionRef dimension;
    private final RegistryBundle registry;
    private final int minSectionY;
    private final int maxSectionY;
    private final int minLightSection;
    private final int maxLightSection;
    private final Map<Long, EncodedChunk> cache = new ConcurrentHashMap<>();
    private volatile boolean masterConnected;

    public SnapshotRepository(
            SnapshotObjectStore store,
            DimensionRef dimension,
            RegistryBundle registry,
            int minSectionY,
            int maxSectionY
    ) {
        this.store = store;
        this.dimension = dimension;
        this.registry = registry;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.minLightSection = minSectionY - 1;
        this.maxLightSection = maxSectionY + 1;
    }

    public void setMasterConnected(boolean connected) {
        this.masterConnected = connected;
    }

    public boolean masterConnected() {
        return this.masterConnected;
    }

    /**
     * 解析并编码一个区块；不可服务返回 null（fail closed，不展示旧区块）。
     */
    public EncodedChunk resolveAndEncode(int chunkX, int chunkZ) {
        if (!this.masterConnected) {
            return null; // 控制服务不可用：拒绝不确定基线
        }
        ChunkRef key = ChunkRef.of(this.dimension, chunkX, chunkZ);
        SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
        if (index == null || index.committed() <= 0 || index.corrupt()) {
            return null;
        }
        if (index.committed() < index.required()) {
            return null; // 落后：INVALIDATE 已到而快照未到
        }
        long epoch = this.store.dimensionEpoch(this.dimension);
        if (epoch > 0 && index.committedEpoch() != epoch) {
            return null; // epoch 不匹配：不得装载旧 epoch 对象
        }
        return this.loadEncoded(key, index.committed());
    }

    private EncodedChunk loadEncoded(ChunkRef key, long revision) {
        long cacheKey = ((long) key.x() << 32) ^ (key.z() & 0xFFFFFFFFL);
        EncodedChunk cached = this.cache.get(cacheKey);
        if (cached != null && cached.revision() == revision) {
            return cached;
        }
        byte[] payload = this.store.loadCommittedPayload(key);
        if (payload == null) {
            return null;
        }
        try {
            Nbt.Tag root = Nbt.fromGzip(payload);
            ChunkDataEncoder.EncodedChunk encoded =
                    ChunkDataEncoder.encode(root, this.minSectionY, this.maxSectionY,
                            this.minLightSection, this.maxLightSection, this.registry);
            EncodedChunk result = new EncodedChunk(revision, encoded.chunkDataPayload(), encoded.lightDataPayload());
            if (this.cache.size() > 4096) {
                this.cache.clear(); // 简单上限：重新装载（慢路径可接受）
            }
            this.cache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            LOGGER.error("[vb] 快照编码失败（该区块 fail closed）: {}: {}", key, e.toString());
            return null;
        }
    }

    /** 编码后的区块（含 revision，缓存校验用）。 */
    public record EncodedChunk(long revision, byte[] chunkDataPayload, byte[] lightDataPayload) {
    }

    public void close() {
        this.cache.clear();
    }
}

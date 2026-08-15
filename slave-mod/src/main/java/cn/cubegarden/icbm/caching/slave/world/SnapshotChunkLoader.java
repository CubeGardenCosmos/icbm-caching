package cn.cubegarden.icbm.caching.slave.world;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.slave.mixin.ChunkMapAccess;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 快照装载器（ServeGate 语义迁移，草稿 slave-fabric-backend §4.1）：只有满足全部条件才
 * 把本地对象作为区块来源（fail closed）：
 * <ol>
 *   <li>master 控制连接存活（否则无法证明 required 水位最新 → 拒绝一切）；</li>
 *   <li>存在非损坏 committed 对象且 committed &gt;= required；</li>
 *   <li>对象 epoch == 维度当前 epoch。</li>
 * </ol>
 * 未命中/落后/不匹配 → {@code null}（不注入旧快照，区块保持 EMPTY 不生成）。
 *
 * <p>26.3 反序列化走官方 {@link SerializableChunkData#parse} + {@code read(...)}
 * （ChunkMap.scheduleChunkLoad 对照，SerializableChunkData.java:565）：源服快照 payload
 * （SerializableChunkData.write() 输出 + GZIP）可直接喂入。注入即释放：不缓存 LevelChunk
 * （256M 堆约束）。
 */
public final class SnapshotChunkLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotChunkLoader.class);

    private final SnapshotObjectStore store;
    private final DimensionRef dimension;
    private volatile boolean masterConnected;

    public SnapshotChunkLoader(SnapshotObjectStore store, DimensionRef dimension) {
        this.store = store;
        this.dimension = dimension;
    }

    public void setMasterConnected(boolean connected) {
        this.masterConnected = connected;
    }

    public boolean masterConnected() {
        return this.masterConnected;
    }

    /** 区块是否可服务（ServeGate 判定，不解析）：玩家移动边界用。 */
    public boolean isChunkServiceable(int chunkX, int chunkZ) {
        if (!this.masterConnected) {
            return false;
        }
        ChunkRef key = ChunkRef.of(this.dimension, chunkX, chunkZ);
        SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
        if (index == null || index.committed() <= 0 || index.corrupt()) {
            return false;
        }
        if (index.committed() < index.required()) {
            return false;
        }
        long epoch = this.store.dimensionEpoch(this.dimension);
        return epoch <= 0 || index.committedEpoch() == epoch;
    }

    /**
     * 解析并注入一个区块；不可服务返回 null（fail closed，不展示旧区块）。
     * 仅在 ChunkMap 主线程调用（与 scheduleChunkLoad 一致）。
     */
    public ChunkAccess loadChunk(ServerLevel level, ChunkPos pos) {
        if (!this.masterConnected) {
            return null; // 控制服务不可用：拒绝不确定基线
        }
        ChunkRef key = ChunkRef.of(this.dimension, pos.x(), pos.z());
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
        byte[] payload = this.store.loadCommittedPayload(key);
        if (payload == null) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(payload),
                    NbtAccounter.create(NbtAccounter.UNCOMPRESSED_NBT_QUOTA));
            SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
            if (data == null) {
                LOGGER.error("[icbm-slave] 快照缺 Status（损坏，该区块 fail closed）: {}", key);
                return null;
            }
            ChunkMapAccess chunkMap = (ChunkMapAccess) ((ServerChunkCache) level.getChunkSource()).chunkMap;
            ChunkAccess chunk = data.read(level, chunkMap.icbm_slave$poiManager(),
                    ((net.minecraft.world.level.chunk.storage.SimpleRegionStorage) chunkMap).storageInfo(), pos);
            if (chunk.getPersistedStatus().isBefore(ChunkStatus.FULL)) {
                LOGGER.warn("[icbm-slave] 快照状态非 FULL（以快照为准）: {} status={}",
                        key, chunk.getPersistedStatus());
            }
            return chunk;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[icbm-slave] 快照解析失败（该区块 fail closed）: {}: {}", key, e.toString());
            return null;
        }
    }
}

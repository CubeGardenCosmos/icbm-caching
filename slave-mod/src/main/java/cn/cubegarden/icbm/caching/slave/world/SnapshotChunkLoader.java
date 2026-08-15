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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
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

    /** 服务维度（操作提交用）。 */
    public DimensionRef dimension() {
        return this.dimension;
    }

    /**
     * 刷新已装载区块：重新解析对象库最新快照 → 逐方块应用到已装载 LevelChunk
     * （flags=UPDATE_CLIENTS：变更广播给追踪玩家——阶段 8.1 修复原 UPDATE_NONE 只改服务端
     * 不通知客户端，真实玩家会保留被拒/过期的预测）。slave 装载 committed snapshot 语义——
     * 非世界计算/非写回；区块实体由 setBlockState 自动增删（LevelChunk.java:322/346 对照）。
     * 卸载路径（SnapshotReloader）因引用不降 0（玩家在 chunk 内）卡住时的兜底与 RESYNC 路径。
     * 在 Server thread 调用。返回 true 表示已应用；false = 无新快照/装载失败（保持现状）。
     */
    public boolean refreshLoaded(ServerLevel level, ChunkPos pos) {
        ChunkAccess fresh = this.loadChunk(level, pos);
        if (fresh == null) {
            return false;
        }
        net.minecraft.world.level.chunk.LevelChunk loaded = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (loaded == null) {
            return false;
        }
        // section 级遍历（26.3 LevelChunkSection.getBlockState(x,y,z) 相对坐标，免 BlockPos
        // 构造——原实现每刷新构造 98304 个 BlockPos 并两次全局定位，主线程开销大）；
        // 差异才构造绝对 BlockPos 并广播 UPDATE_CLIENTS（追踪玩家即时看到新状态——8.1 提交
        // 说明声称已改 UPDATE_CLIENTS，但实现遗留 UPDATE_NONE 只改服务端不通知客户端，
        // 真机表现为“同步慢/被拒预测不恢复”；逐方块广播会刷爆网络，差异广播成本与变更数成正比）。
        net.minecraft.world.level.chunk.LevelChunkSection[] freshSections = fresh.getSections();
        net.minecraft.world.level.chunk.LevelChunkSection[] loadedSections = loaded.getSections();
        int minSectionY = level.getMinSectionY();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        for (int si = 0; si < freshSections.length && si < loadedSections.length; si++) {
            net.minecraft.world.level.chunk.LevelChunkSection fs = freshSections[si];
            net.minecraft.world.level.chunk.LevelChunkSection ls = loadedSections[si];
            int sectionBaseY = (minSectionY + si) << 4;
            for (int y = 0; y < 16; y++) {
                int absY = sectionBaseY + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = fs.getBlockState(x, y, z);
                        if (state == ls.getBlockState(x, y, z)) {
                            continue;
                        }
                        loaded.setBlockState(new net.minecraft.core.BlockPos(minX + x, absY, minZ + z),
                                state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        LOGGER.debug("[icbm-slave] 区块刷新: 已就地应用快照 {},{}（差异广播）", pos.x(), pos.z());
        return true;
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

    /** 目标 chunk 的 committed revision（操作 baseRevision 快速拒绝用；未装载/无对象 → 0）。 */
    public long committedRevision(int chunkX, int chunkZ) {
        if (this.dimension == null) {
            return 0;
        }
        ChunkRef key = ChunkRef.of(this.dimension, chunkX, chunkZ);
        SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
        return index == null ? 0 : index.committed();
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

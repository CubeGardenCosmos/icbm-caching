package cn.cubegarden.icbm.caching.source.dirty;

import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 脏标记跟踪器：
 * <ul>
 *   <li>{@link #onChunkUnsaved} —— Mixin 钩住 {@code LevelChunk.markUnsaved()}（26.3 唯一权威
 *       脏标记路径，LevelChunk.java 对照）的回调，覆盖方块/方块实体/随机刻/活塞/液体/命令/模组修改。</li>
 *   <li>其余事件（区块加载/卸载、玩家破坏、方块实体加载/卸载）为诊断与早期标记，与权威路径
 *       重复标记时由 {@link DirtyChunkSet} 合并。</li>
 * </ul>
 */
public final class DirtyTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirtyTracker.class);

    private final DirtyChunkSet dirty;
    private final boolean diagnosticsEnabled;

    public DirtyTracker(DirtyChunkSet dirty) {
        this(dirty, true);
    }

    public DirtyTracker(DirtyChunkSet dirty, boolean diagnosticsEnabled) {
        this.dirty = dirty;
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    public DirtyChunkSet dirtySet() {
        return this.dirty;
    }

    /** Mixin 回调：区块被标记为 unsaved（false→true 转换，与官方监听器语义一致）。 */
    public void onChunkUnsaved(ServerLevel level, ChunkPos pos) {
        this.dirty.mark(ChunkKey.of(level.dimension().identifier(), pos.x(), pos.z()));
    }

    /** 诊断：区块加载（authority 路径之外无附加动作）。 */
    public void onChunkLoad(ServerLevel level, ChunkPos pos) {
        if (this.diagnosticsEnabled) {
            LOGGER.debug("[icbm-source] chunk loaded: {}", pos);
        }
    }

    /** 诊断 + 早期标记：玩家破坏方块。 */
    public void onPlayerBreakBlock(ServerLevel level, BlockPos pos) {
        ChunkKey key = ChunkKey.of(level.dimension().identifier(), pos.getX() >> 4, pos.getZ() >> 4);
        if (this.diagnosticsEnabled) {
            LOGGER.debug("[icbm-source] player break early-mark: {}", key);
        }
        this.dirty.mark(key);
    }
}

package cn.cubegarden.icbm.caching.slave.world;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.slave.operation.OperationOverlay;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 快照到达/操作终态后刷新已装载区块（里程碑 7 阶段 5 + 阶段 8.1 真实玩家路径）。
 *
 * <p>一致性不变量：已装载 LevelChunk 的状态 = committed snapshot + overlay 全部 pending
 * 预测（overlay 是唯一增量，架构 §5.2）。任何把区块恢复到 committed 的操作（刷新/RESYNC/
 * 重载）之后必须 {@link #applyOverlayToChunk} 重放预测，否则其它玩家的预测消失（幽灵）。
 *
 * <p>官方路径（26.3 ChunkMap.scheduleUnload，ChunkMap.java:525）：ChunkHolder 引用计数
 * （force/players）降 0 → unloadQueue 异步卸载 → level.unload；卸载后重新
 * {@code setChunkForced(true)} → scheduleChunkLoad → SnapshotChunkLoader Mixin 命中对象库
 * 新快照注入。
 *
 * <p>玩家在 chunk 内的情形：玩家自身 ticket 使引用不降 0 → 卸载永不完成 → 走就地
 * {@code refreshLoaded}（阶段 8.1：避免 10s 超时兜底延迟；refreshLoaded 以 UPDATE_CLIENTS
 * 广播变更给玩家视角）。
 *
 * <p>线程模型（26.3 ServerChunkCache.getChunkNow 有主线程检查，非主线程返回 null）：
 * 快照装载/终态回调（reader/通道线程）只登记待处理 pos；{@link #onServerTick}（Server
 * thread）执行刷新/卸载/重载，不阻塞 tick 热路径（每 tick 限处理）。
 */
public final class SnapshotReloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotReloader.class);

    private static final long UNLOAD_TIMEOUT_MILLIS = 10_000;

    private final cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader loader;
    private final OperationOverlay overlay;

    public SnapshotReloader(cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader loader,
                            OperationOverlay overlay) {
        this.loader = loader;
        this.overlay = overlay;
    }

    /** 待主线程检查的 pos（快照到达登记；任意线程安全）。 */
    private final Set<Long> pendingCheck = ConcurrentHashMap.newKeySet();
    /** 强制卸载中的 pos（pos 键）→ 起始时间（超时保护）。 */
    private final Map<Long, Long> pendingUnload = new ConcurrentHashMap<>();
    /** 操作 REJECTED 的 RESYNC pos（终态回调登记；Server tick 就地刷新 + 重应用预测）。 */
    private final Set<Long> pendingResync = ConcurrentHashMap.newKeySet();

    /** 新快照装载成功回调（SlaveControlClient 线程）：登记待检查（实际刷新在主线程 tick）。
     *  已在刷新流程中的 pos 忽略（重复快照不叠加——重载时对象库已是最新，直接命中）。 */
    public void onChunkUpdated(DimensionRef dimension, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (this.pendingUnload.containsKey(key) || this.pendingResync.contains(key)) {
            return;
        }
        this.pendingCheck.add(key);
    }

    /** 操作 REJECTED 的 RESYNC 登记（SlaveControlClient 线程）：就地恢复 committed + 重应用
     *  其余 pending 预测（源服拒绝即世界未变，显示旧状态即与源一致）。 */
    public void requestResync(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        this.pendingUnload.remove(key);
        this.pendingResync.add(key);
    }

    /** Server thread tick：检查已装载区块 → 刷新/卸载重载 → 重应用 overlay 预测。 */
    public void onServerTick(MinecraftServer server) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                Identifier.parse("minecraft:overworld")));
        if (level == null) {
            return;
        }
        // 0) 操作 REJECTED：就地恢复 committed snapshot + 重应用该 chunk 的 pending 预测
        if (!this.pendingResync.isEmpty()) {
            for (Long key : this.pendingResync) {
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                boolean applied = this.loader.refreshLoaded(level, new ChunkPos(cx, cz));
                this.applyOverlayToChunk(level, cx, cz);
                LOGGER.info("[icbm-slave] 区块 RESYNC（操作被拒，落回 committed + 重应用预测）: {},{} applied={}",
                        cx, cz, applied);
            }
            this.pendingResync.clear();
        }
        // 1) 快照到达的 pos：若已装载 → 玩家在 chunk 内则就地刷新（卸载永不完成），否则卸载重载
        if (!this.pendingCheck.isEmpty()) {
            for (Long key : this.pendingCheck) {
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    if (hasPlayerInChunk(level, cx, cz)) {
                        // 玩家在 chunk 内：卸载引用不降 0 → 就地刷新（UPDATE_CLIENTS 广播新状态）
                        boolean applied = this.loader.refreshLoaded(level, new ChunkPos(cx, cz));
                        this.applyOverlayToChunk(level, cx, cz);
                        LOGGER.info("[icbm-slave] 区块刷新: 就地应用新快照（玩家在 chunk 内）: {},{} applied={}",
                                cx, cz, applied);
                    } else {
                        this.pendingUnload.putIfAbsent(key, System.currentTimeMillis());
                        level.setChunkForced(cx, cz, false);
                        LOGGER.info("[icbm-slave] 区块刷新: 已卸载 {},{}（等待重新装载命中新快照）", cx, cz);
                    }
                }
            }
            this.pendingCheck.clear();
        }
        // 2) 卸载完成后重新 force → 重新调度装载（Mixin 注入新快照）→ 重应用 overlay 预测
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, Long>> it = this.pendingUnload.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            long key = entry.getKey();
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                level.setChunkForced(cx, cz, true);
                it.remove();
                this.applyOverlayToChunk(level, cx, cz);
                LOGGER.info("[icbm-slave] 区块刷新: 已重新装载 {},{}（新快照注入 + 重应用预测）", cx, cz);
            } else if (now - entry.getValue() > UNLOAD_TIMEOUT_MILLIS) {
                // 卸载卡住（引用不降 0，如 saveSync 依赖未完成/玩家重新进入）：恢复 force 并就地应用最新快照
                level.setChunkForced(cx, cz, true);
                it.remove();
                boolean applied = this.loader.refreshLoaded(level, new ChunkPos(cx, cz));
                this.applyOverlayToChunk(level, cx, cz);
                LOGGER.warn("[icbm-slave] 区块刷新超时（就地应用快照={}）: {},{}", applied, cx, cz);
            }
        }
    }

    /** 重应用某 chunk 的全部 pending 预测（LevelChunk = committed + overlay 不变量；Server thread）。
     *  区块未装载时跳过（下次装载以 committed 快照注入；预测在 overlay 中保留，装载时不再应用——
     *  该 chunk 无玩家，预测不可见且终态到达后自然清除，fail closed 语义不变）。 */
    public void applyOverlayToChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return;
        }
        for (OperationOverlay.Pending pending : this.overlay.pendingInChunk(chunkX, chunkZ)) {
            level.setBlock(new net.minecraft.core.BlockPos(pending.posX, pending.posY, pending.posZ),
                    cn.cubegarden.icbm.caching.slave.operation.PlayerOperationHandler.parseState(
                            pending.predictedState),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    private static boolean hasPlayerInChunk(ServerLevel level, int chunkX, int chunkZ) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        return !level.getPlayers(p -> p.chunkPosition().equals(pos)).isEmpty();
    }
}

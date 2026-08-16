package cn.cubegarden.icbm.caching.slave.world;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.slave.operation.OperationOverlay;
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
 * <p>真机验证（26.3 ChunkMap.scheduleUnload，ChunkMap.java:525）：玩家 ticket（debug-run
 * 视距 32）使玩家周围区块引用永不为 0 → 卸载永不完成 → 曾用「卸载重载」路径的区块
 * 全部 10s 超时兜底就地应用（warn 噪音 + 延迟）。因此已装载区块一律就地刷新
 * {@code refreshLoaded}（差异才广播 UPDATE_CLIENTS，成本与变更数成正比；无玩家在 chunk
 * 内时无追踪者，广播零开销）；未装载区块无需处理——玩家进入时
 * {@code scheduleChunkLoad} 命中对象库最新快照注入，无旧状态可展示。
 *
 * <p>内存：视距外区块无玩家 ticket/force ticket 自然卸载（SaveGateMixin 拦截写盘点
 * save()，卸载不卡死）；视距内区块常驻是原版行为（玩家 ticket），刷新不增加驻留。
 *
 * <p>线程模型（26.3 ServerChunkCache.getChunkNow 有主线程检查，非主线程返回 null）：
 * 快照装载/终态回调（reader/通道线程）只登记待处理 pos；{@link #onServerTick}（Server
 * thread）执行刷新，不阻塞 tick 热路径（每 tick 限处理）。
 */
public final class SnapshotReloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotReloader.class);

    private final cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader loader;
    private final OperationOverlay overlay;

    public SnapshotReloader(cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader loader,
                            OperationOverlay overlay) {
        this.loader = loader;
        this.overlay = overlay;
    }

    /** 待主线程检查的 pos（快照到达登记；任意线程安全）。 */
    private final Set<Long> pendingCheck = ConcurrentHashMap.newKeySet();
    /** 操作 REJECTED 的 RESYNC pos（终态回调登记；Server tick 就地刷新 + 重应用预测）。 */
    private final Set<Long> pendingResync = ConcurrentHashMap.newKeySet();
    /** 已就地应用的对象库 revision（防旧 rev 快照覆盖新状态后不再刷新：9.4 e2e 暴露——物化
     *  洪水下 rev=2 在裁决后到达 → 刷新旧 diamond 覆盖 F1 air → pendingCheck 去重不再刷新；
     *  记录 appliedRev，仅对象库新 rev 触发重新刷新）。 */
    private final Map<Long, Long> appliedRevisions = new ConcurrentHashMap<>();

    /** 新快照装载成功回调（SlaveControlClient 线程）：登记待检查（实际刷新在主线程 tick）。
     *  已在刷新流程中的 pos 忽略（重复快照不叠加——重载时对象库已是最新，直接命中）。 */
    public void onChunkUpdated(DimensionRef dimension, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (this.pendingResync.contains(key)) {
            return;
        }
        long committed = this.loader.committedRevision(chunkX, chunkZ);
        Long applied = this.appliedRevisions.get(key);
        if (applied != null && committed <= applied) {
            return; // 已应用同/更新 rev（陈旧或重复快照不重复刷新）
        }
        this.pendingCheck.add(key);
    }

    /** 操作 REJECTED 的 RESYNC 登记（SlaveControlClient 线程）：就地恢复 committed + 重应用
     *  其余 pending 预测（源服拒绝即世界未变，显示旧状态即与源一致）。 */
    public void requestResync(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        this.pendingResync.add(key);
    }

    /** Server thread tick：检查已装载区块 → 就地刷新 → 重应用 overlay 预测。 */
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
                if (applied) {
                    this.appliedRevisions.put(key, this.loader.committedRevision(cx, cz));
                }
                this.applyOverlayToChunk(level, cx, cz);
                LOGGER.debug("[icbm-slave] 区块 RESYNC（操作被拒，落回 committed + 重应用预测）: {},{} applied={}",
                        cx, cz, applied);
            }
            this.pendingResync.clear();
        }
        // 1) 快照到达的 pos：已装载 → 就地刷新（玩家 ticket 使卸载永不完成，卸载重载
        //    路径已移除）；未装载 → 跳过（进入时 scheduleChunkLoad 命中对象库新快照）
        if (!this.pendingCheck.isEmpty()) {
            for (Long key : this.pendingCheck) {
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    boolean applied = this.loader.refreshLoaded(level, new ChunkPos(cx, cz));
                    if (applied) {
                        // 就地刷新成功：记录已应用的对象库 rev（新 rev 到达时重新登记刷新）
                        this.appliedRevisions.put(key, this.loader.committedRevision(cx, cz));
                    }
                    this.applyOverlayToChunk(level, cx, cz);
                    LOGGER.debug("[icbm-slave] 区块刷新: 就地应用新快照 {},{} applied={}",
                            cx, cz, applied);
                }
            }
            this.pendingCheck.clear();
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
}

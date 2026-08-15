package cn.cubegarden.icbm.caching.slave.world;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
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
 * 快照到达后刷新已装载区块（里程碑 7 阶段 5；修复里程碑 6 记录的限制「快照到达后重载
 * 已装载区块不做」——乐观操作 COMMITTED 后新快照必须展示给玩家视角）。
 *
 * <p>官方路径（26.3 ChunkMap.scheduleUnload，ChunkMap.java:525）：ChunkHolder 引用计数
 * （force/players）降 0 → unloadQueue 异步卸载 → level.unload；卸载后重新
 * {@code setChunkForced(true)} → scheduleChunkLoad → SnapshotChunkLoader Mixin 命中对象库
 * 新快照注入。
 *
 * <p>线程模型（26.3 ServerChunkCache.getChunkNow 有主线程检查，非主线程返回 null）：
 * 快照装载（reader 线程）只登记待检查 pos；{@link #onServerTick}（Server thread）执行
 * 卸载/重载，不阻塞 tick 热路径（每 tick 限处理）。
 */
public final class SnapshotReloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotReloader.class);

    private static final long UNLOAD_TIMEOUT_MILLIS = 10_000;

    private final cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader loader;

    public SnapshotReloader(cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader loader) {
        this.loader = loader;
    }

    /** 待主线程检查的 pos（快照到达登记；任意线程安全）。 */
    private final Set<Long> pendingCheck = ConcurrentHashMap.newKeySet();
    /** 强制卸载中的 pos（pos 键）→ 起始时间（超时保护）。 */
    private final Map<Long, Long> pendingUnload = new ConcurrentHashMap<>();

    /** 新快照装载成功回调（SlaveControlClient 线程）：登记待检查（实际卸载在主线程 tick）。
     *  已在刷新流程中的 pos 忽略（重复快照不叠加卸载——重载时对象库已是最新，直接命中）。 */
    public void onChunkUpdated(DimensionRef dimension, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (this.pendingUnload.containsKey(key)) {
            return;
        }
        this.pendingCheck.add(key);
    }

    /** Server thread tick：检查已装载区块 → 卸载 → 卸载完成后重新装载（Mixin 注入新快照）。 */
    public void onServerTick(MinecraftServer server) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                Identifier.parse("minecraft:overworld")));
        if (level == null) {
            return;
        }
        // 1) 快照到达的 pos：若已装载 → 解除 force 触发卸载
        if (!this.pendingCheck.isEmpty()) {
            for (Long key : this.pendingCheck) {
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                    this.pendingUnload.putIfAbsent(key, System.currentTimeMillis());
                    level.setChunkForced(cx, cz, false);
                    LOGGER.info("[icbm-slave] 区块刷新: 已卸载 {},{}（等待重新装载命中新快照）", cx, cz);
                }
            }
            this.pendingCheck.clear();
        }
        // 2) 卸载完成后重新 force → 重新调度装载（Mixin 注入新快照）
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
                LOGGER.info("[icbm-slave] 区块刷新: 已重新装载 {},{}（新快照注入）", cx, cz);
            } else if (now - entry.getValue() > UNLOAD_TIMEOUT_MILLIS) {
                // 卸载卡住（引用不降 0，如 saveSync 依赖未完成）：恢复 force 并就地应用最新快照
                level.setChunkForced(cx, cz, true);
                it.remove();
                boolean applied = this.loader.refreshLoaded(level, new ChunkPos(cx, cz));
                LOGGER.warn("[icbm-slave] 区块刷新超时（就地应用快照={}）: {},{}", applied, cx, cz);
            }
        }
    }
}

package cn.cubegarden.icbm.caching.slave.operation;

import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按需物化请求器（8.2，草稿 on-demand-materialization §3.5；slave 侧）：
 * 玩家接近服务边界（PlayerBounds 撞墙）或预取 → 向 master 发 CHUNK_MATERIALIZE_REQ
 * → 源服生成并发布快照 → SNAPSHOT_PUSH → slave 装载。
 *
 * <p>节流/去重（防快速移动引发生成风暴，源服主线程热路径不受阻塞）：
 * <ul>
 *   <li>冷却：同一 chunk 请求后 N tick（默认 100 = 5s）内不重复发；</li>
 *   <li>每 tick 限量：PLAYER_PROXIMITY 每 tick 最多 N 条（默认 4），超出顺延；</li>
 *   <li>预取：每 N tick（默认 40）对玩家所在 chunk 周围 radius 内未服务区块批量
 *       PREFETCH（冷却去重后实际发送很少）。</li>
 * </ul>
 * 仅维护 chunk 坐标 → 冷却 tick 的小映射（256M 堆约束；不缓存区块/快照）。
 */
public final class MaterializeRequester {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterializeRequester.class);

    static final int PROXIMITY_COOLDOWN_TICKS = 100;   // 5s
    static final int MAX_PROXIMITY_PER_TICK = 4;
    static final int PREFETCH_RADIUS = 2;
    static final int PREFETCH_INTERVAL_TICKS = 40;     // 2s

    private final SlaveControlClient client;
    private final SnapshotChunkLoader loader;
    private final Map<Long, Integer> lastRequested = new HashMap<>(); // packed chunk → tick（Server thread only）
    private int tickCounter;
    private int proximityThisTick;

    public MaterializeRequester(SlaveControlClient client, SnapshotChunkLoader loader) {
        this.client = client;
        this.loader = loader;
    }

    /** 撞墙触发（PlayerBoundsMixin，Server thread）：目标 chunk 请求生成（高优先级）。 */
    public void onBlockedMove(int chunkX, int chunkZ) {
        this.request(chunkX, chunkZ, Messages.MaterializePriority.HIGH,
                Messages.MaterializeReason.PLAYER_PROXIMITY);
    }

    /** 每 tick：重置节流计数 + 周期性预取玩家周围未服务区块。 */
    public void onEndServerTick(ServerLevel level) {
        this.tickCounter++;
        this.proximityThisTick = 0;
        if (this.tickCounter % PREFETCH_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            int cx = player.getBlockX() >> 4;
            int cz = player.getBlockZ() >> 4;
            for (int dx = -PREFETCH_RADIUS; dx <= PREFETCH_RADIUS; dx++) {
                for (int dz = -PREFETCH_RADIUS; dz <= PREFETCH_RADIUS; dz++) {
                    int tx = cx + dx;
                    int tz = cz + dz;
                    if (!this.loader.isChunkServiceable(tx, tz)) {
                        this.request(tx, tz, Messages.MaterializePriority.NORMAL,
                                Messages.MaterializeReason.PREFETCH);
                    }
                }
            }
        }
    }

    private void request(int chunkX, int chunkZ, Messages.MaterializePriority priority,
                         Messages.MaterializeReason reason) {
        if (this.loader.dimension() == null || !this.loader.masterConnected()) {
            return; // 未连接/master 不可达：不发（fail closed，撞墙保持）
        }
        long packed = pack(chunkX, chunkZ);
        int last = this.lastRequested.getOrDefault(packed, Integer.MIN_VALUE / 2);
        if (!shouldSend(this.tickCounter, last, this.proximityThisTick, reason,
                PROXIMITY_COOLDOWN_TICKS, MAX_PROXIMITY_PER_TICK)) {
            return;
        }
        this.lastRequested.put(packed, this.tickCounter);
        this.proximityThisTick++;
        boolean sent = this.client.requestMaterialize(new Messages.ChunkMaterializeReq(
                this.client.nextRequestId(), this.loader.dimension(), chunkX, chunkZ,
                priority, reason));
        if (sent) {
            LOGGER.debug("[icbm-slave] 物化请求: {} {},{},{} reason={}",
                    this.loader.dimension(), chunkX, chunkZ, reason);
        }
    }

    /** 冷却/节流判定（纯逻辑；测试覆盖）：冷却期内同 chunk 拒绝；PLAYER_PROXIMITY
     *  受每 tick 限量约束，PREFETCH 不受（低频周期已限频）。 */
    static boolean shouldSend(int tickCounter, int lastTick, int proximityThisTick,
                              Messages.MaterializeReason reason,
                              int proximityCooldownTicks, int maxProximityPerTick) {
        if (tickCounter - lastTick < proximityCooldownTicks) {
            return false;
        }
        if (reason == Messages.MaterializeReason.PLAYER_PROXIMITY
                && proximityThisTick >= maxProximityPerTick) {
            return false;
        }
        return true;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}

package cn.cubegarden.icbm.caching.source.materialize;

import cn.cubegarden.icbm.caching.source.dirty.DirtyTracker;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import cn.cubegarden.icbm.caching.source.revision.SourceStateService;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 物化服务（8.2，草稿 on-demand-materialization §3.3）：源服按需生成区块并确保其进入
 * 快照管道（初始区域物化 + slave 按需物化 REQ 两种入口）。
 *
 * <p>主线程状态机（无全局可变状态；仅 {@link #lastServer} 为 volatile 供通道线程入口使用）：
 * <pre>
 * NEW --request--> GENERATING --setChunkForced(true) 生成--> 完成(getChunkNow!=null)
 *   --> dirty mark + 记录 expectedRevision --> PUBLISHING --revisionOf>=expected（快照已发布）
 *   --> setChunkForced(false) 释放 ticket --> 移除
 * </pre>
 *
 * <p>物化用官方 {@code ServerLevel.setChunkForced}（ServerLevel.java:1517 → updateChunkForced，
 * /forceload 同款；不注册自定义 TicketType——26.3 BuiltInRegistries 冻结后注册有风险）。
 * 生成完成即 FULL（LevelChunk 存在），序列化完整区块（copyOf），无需 ProtoChunk 生成期
 * 脏标记跟踪（草稿 §3.3）。ticket 在快照发布后释放（不永久驻留）；超时保护：GENERATING/
 * PUBLISHING 超时记错误并释放（覆盖缺口在 slave 侧保持撞墙，fail closed 语义正确）。
 *
 * <p>节流：每 tick 最多 {@code maxPerTick} 个区块进入 GENERATING（addTicket），防快速移动
 * 引发生成风暴阻塞源服主线程热路径（草稿 §3.5）。
 */
public final class MaterializeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterializeService.class);

    /** 生成阶段超时（tick）：超过则记错误并释放（不永久驻留）。 */
    static final int GENERATING_TIMEOUT_TICKS = 1200;   // 60s
    /** 发布阶段超时（tick）：快照迟迟未发布（sink 不可用等）→ 释放并记错误。 */
    static final int PUBLISHING_TIMEOUT_TICKS = 600;    // 30s
    /** 热驻留超时（tick）：操作终态后 warm ticket 保留时长（草稿 fast-operation-sync §3.2）；
     *  无操作 30s 自动释放，连续破坏/放置不再逐 op 重载。 */
    static final int PUBLISHED_HOLD_TIMEOUT_TICKS = 600; // 30s

    private enum Stage {
        GENERATING,
        PUBLISHING,
        PUBLISHED_HOLD,
    }

    private record Pending(Stage stage, long expectedRevision, int ticks) {
        Pending tick() {
            return new Pending(this.stage, this.expectedRevision, this.ticks + 1);
        }
    }

    private final SourceStateService service;
    private final DirtyTracker tracker;
    private final int maxPerTick;
    /** 主线程专用（onEndServerTick/request/materializeInitial）。 */
    private final Map<ChunkKey, Pending> pending = new HashMap<>();
    /** 通道线程入口用（volatile；由 onEndServerTick 设置）。 */
    private volatile MinecraftServer lastServer;

    public MaterializeService(SourceStateService service, DirtyTracker tracker, int maxPerTick) {
        this.service = service;
        this.tracker = tracker;
        this.maxPerTick = Math.max(1, maxPerTick);
    }

    /** 按需物化入口（主线程）：请求目标 chunk 生成并发布。已请求/已存在则去重。 */
    public void request(ServerLevel level, int chunkX, int chunkZ) {
        ChunkKey key = ChunkKey.of(level.dimension().identifier(), chunkX, chunkZ);
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk != null) {
            // 已生成：确保其内容进入快照管道（幂等：dirty 合并）
            this.tracker.dirtySet().mark(key);
            return;
        }
        this.pending.putIfAbsent(key, new Pending(Stage.GENERATING, 0, 0));
    }

    /** 初始区域物化（serverStarted；出生点周边 radius 正方形；主线程）。 */    public void materializeInitial(ServerLevel level, BlockPos spawn, int radius) {
        int spawnX = spawn.getX() >> 4;
        int spawnZ = spawn.getZ() >> 4;
        int count = 0;
        for (int x = spawnX - radius; x <= spawnX + radius; x++) {
            for (int z = spawnZ - radius; z <= spawnZ + radius; z++) {
                ChunkKey key = ChunkKey.of(level.dimension().identifier(), x, z);
                if (level.getChunkSource().getChunkNow(x, z) != null) {
                    this.tracker.dirtySet().mark(key); // 已生成（出生点焦点区）：确保发布
                    continue;
                }
                if (this.pending.putIfAbsent(key, new Pending(Stage.GENERATING, 0, 0)) == null) {
                    count++;
                }
            }
        }
        LOGGER.info("[icbm-source] 初始区域物化: spawn={},{} radius={} 新增 {} 区块（pending={}）",
                spawnX, spawnZ, radius, count, this.pending.size());
    }

    /**
     * 操作终态后热驻留（阶段 9.2 F2，草稿 fast-operation-sync §3.2）：目标 chunk 保留
     * warm ticket 30s（PUBLISHED_HOLD），连续破坏/放置不再逐 op 重载。区块已装载才登记
     * （未装载说明操作不可能命中，NeedsChunk 路径已加载）；setChunkForced(true) 幂等，
     * 与 OperationQueue/物化已有 ticket 共存（超时统一由此释放）。主线程调用。
     */
    public void hold(ServerLevel level, int chunkX, int chunkZ) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return; // 未装载：无需驻留（操作命中过说明已装载，此处防御）
        }
        ChunkKey key = ChunkKey.of(level.dimension().identifier(), chunkX, chunkZ);
        if (this.pending.putIfAbsent(key, new Pending(Stage.PUBLISHED_HOLD,
                this.service.revisionOf(key), 0)) == null) {
            level.setChunkForced(chunkX, chunkZ, true); // 30s 内不卸载（幂等）
        }
    }

    /** 通道线程入口：从 CHUNK_MATERIALIZE_REQ 解析并切回主线程。 */
    public void requestFromChannel(cn.cubegarden.icbm.caching.protocol.Messages.ChunkMaterializeReq req) {
        MinecraftServer server = this.lastServer;
        if (server == null) {
            LOGGER.warn("[icbm-source] 物化请求丢弃（服务器未就绪）: req={} {} {},{},{}",
                    req.requestId(), req.dimension(), req.chunkX(), req.chunkZ(), req.reason());
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.getLevel(ResourceKey.create(
                    Registries.DIMENSION, Identifier.parse(req.dimension().id())));
            if (level == null) {
                LOGGER.warn("[icbm-source] 物化请求丢弃（维度不存在）: req={} {}",
                        req.requestId(), req.dimension());
                return;
            }
            this.request(level, req.chunkX(), req.chunkZ());
        });
    }

    /** 每 tick 末推进状态机（ServerTickEvents.END_SERVER_TICK，ticker 之后调用）。 */
    public void onEndServerTick(MinecraftServer server) {
        this.lastServer = server;
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null || this.pending.isEmpty()) {
            return;
        }
        int generatingThisTick = 0;
        Iterator<Map.Entry<ChunkKey, Pending>> it = this.pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkKey, Pending> entry = it.next();
            ChunkKey key = entry.getKey();
            Pending p = entry.getValue().tick();
            switch (p.stage()) {
                case GENERATING -> {
                    if (generatingThisTick >= this.maxPerTick) {
                        entry.setValue(p); // 节流：本 tick 不再 addTicket（下 tick 继续）
                        continue;
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
                    if (chunk == null) {
                        level.setChunkForced(key.x(), key.z(), true);
                        generatingThisTick++;
                        if (p.ticks() > GENERATING_TIMEOUT_TICKS) {
                            // 生成超时：释放 ticket 并放弃（覆盖缺口 fail closed，不永久驻留）
                            LOGGER.error("[icbm-source] 物化生成超时（释放 ticket，覆盖缺口 fail closed）: "
                                    + "{} ticks={}", key, p.ticks());
                            level.setChunkForced(key.x(), key.z(), false);
                            it.remove();
                            continue;
                        }
                        entry.setValue(p);
                    } else {
                        // 生成完成：确保进入快照管道并记录期望 revision（发布后释放）
                        this.tracker.dirtySet().mark(key);
                        entry.setValue(new Pending(Stage.PUBLISHING,
                                this.service.revisionOf(key) + 1, 0));
                    }
                }
                case PUBLISHING -> {
                    if (this.service.revisionOf(key) >= p.expectedRevision()) {
                        // 快照已发布：释放物化 ticket（不永久驻留；对象库已有 committed，slave 直读）
                        level.setChunkForced(key.x(), key.z(), false);
                        it.remove();
                    } else if (p.ticks() > PUBLISHING_TIMEOUT_TICKS) {
                        LOGGER.error("[icbm-source] 物化发布超时（释放 ticket，覆盖缺口 fail closed）: "
                                + "{} expected={} current={}", key, p.expectedRevision(),
                                this.service.revisionOf(key));
                        level.setChunkForced(key.x(), key.z(), false);
                        it.remove();
                    } else {
                        entry.setValue(p);
                    }
                }
                case PUBLISHED_HOLD -> {
                    if (p.ticks() > PUBLISHED_HOLD_TIMEOUT_TICKS) {
                        // 热驻留超时（30s 无操作）：释放 warm ticket（区块可正常卸载）
                        LOGGER.debug("[icbm-source] 操作热驻留到期（释放 warm ticket）: {} ticks={}",
                                key, p.ticks());
                        level.setChunkForced(key.x(), key.z(), false);
                        it.remove();
                    } else {
                        entry.setValue(p);
                    }
                }
                default -> {
                }
            }
        }
    }
}

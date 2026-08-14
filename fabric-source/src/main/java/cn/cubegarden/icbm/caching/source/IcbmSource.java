package cn.cubegarden.icbm.caching.source;

import cn.cubegarden.icbm.caching.source.command.IcbmCommand;
import cn.cubegarden.icbm.caching.source.dirty.DirtyChunkSet;
import cn.cubegarden.icbm.caching.source.dirty.DirtyTracker;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import cn.cubegarden.icbm.caching.source.revision.ModpackFingerprint;
import cn.cubegarden.icbm.caching.source.revision.SourceStateService;
import cn.cubegarden.icbm.caching.source.selfcheck.MixinTargetVerifier;
import cn.cubegarden.icbm.caching.source.snapshot.LocalSnapshotSink;
import cn.cubegarden.icbm.caching.source.snapshot.SnapshotPublisher;
import cn.cubegarden.icbm.caching.source.snapshot.SnapshotSink;
import cn.cubegarden.icbm.caching.source.tick.ChunkSnapshotTicker;
import java.nio.file.Files;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * ICBM Caching 权威源 Fabric 模组入口（阶段1：worldEpoch/chunk revision 与快照）。
 *
 * <p>组合根：在覆盖维度加载时装配 {@link SourceRuntime}（Mixin 自检 → 状态装载 →
 * 快照发布 → tick 驱动），服务器停止时关闭 sink 并清除运行时。
 */
public final class IcbmSource implements ModInitializer {

    public static final String MOD_ID = "icbm_source";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CacheGate gate = new CacheGate();

        // 1) 运行时装配（每个服务器生命周期一次，用覆盖维度判定）
        ServerLevelEvents.LOAD.register((server, world) -> {
            if (!world.dimension().equals(Level.OVERWORLD) || SourceRuntime.current() != null) {
                return;
            }
            // Mixin 自检：任何目标缺失 → fail closed（不静默降级）
            if (!MixinTargetVerifier.verify()) {
                gate.disable("Mixin 目标缺失（LevelChunk.markUnsaved / SerializableChunkData）");
            }
            boolean existingWorld = Files.exists(server.getWorldPath(LevelResource.LEVEL_DATA_FILE));
            SourceStateService service = SourceStateService.load(
                    world.getDataStorage(), ModpackFingerprint.compute(server), existingWorld);

            SnapshotSink sink = new LocalSnapshotSink(
                    server.getWorldPath(LevelResource.ROOT).resolve("icbm_snapshots"));
            SnapshotPublisher publisher = new SnapshotPublisher(sink, gate);
            DirtyTracker tracker = new DirtyTracker(new DirtyChunkSet());
            ChunkSnapshotTicker ticker = new ChunkSnapshotTicker(service, publisher, tracker, gate);

            SourceRuntime.install(new SourceRuntime(gate, service, tracker, publisher, ticker));
            LOGGER.info("[icbm-source] 运行时就绪：gate={} epoch={} fingerprint={}",
                    gate.isEnabled(), service.epochFor(Level.OVERWORLD.identifier()),
                    service.currentFingerprint().substring(0, 12));
        });

        // 2) tick 驱动与生命周期清理
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SourceRuntime runtime = SourceRuntime.current();
            if (runtime != null) {
                runtime.ticker().onEndServerTick(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SourceRuntime runtime = SourceRuntime.current();
            if (runtime != null) {
                runtime.publisher().sink().close();
            }
            SourceRuntime.clear();
        });

        // 3) 诊断与早期标记事件（运行时为空时 no-op）
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            SourceRuntime r = SourceRuntime.current();
            if (r != null) {
                r.tracker().onChunkLoad(level, chunk.getPos());
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            SourceRuntime r = SourceRuntime.current();
            if (r == null) {
                return;
            }
            // 已修改但未发布（卸载）：先失效后提交，旧快照不得继续作为基线
            ChunkKey key = ChunkKey.of(level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z());
            if (r.tracker().dirtySet().remove(key)) {
                long revision = r.service().nextRevision(key);
                if (r.publisher().begin(key, revision)) {
                    r.publisher().failWithoutSnapshot(key);
                    r.publisher().endTick(key);
                }
            }
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            SourceRuntime r = SourceRuntime.current();
            if (r != null && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                r.tracker().onPlayerBreakBlock(serverLevel, pos);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            SourceRuntime r = SourceRuntime.current();
            if (r != null) {
                r.tracker().onChunkLoad(level, level.getChunkAt(blockEntity.getBlockPos()).getPos());
            }
        });

        // 4) 管理命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                IcbmCommand.register(dispatcher));
    }
}

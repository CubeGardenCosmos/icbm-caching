package cn.cubegarden.icbm.caching.source;

import cn.cubegarden.icbm.caching.source.command.IcbmCommand;
import cn.cubegarden.icbm.caching.source.dirty.DirtyChunkSet;
import cn.cubegarden.icbm.caching.source.dirty.DirtyTracker;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import cn.cubegarden.icbm.caching.source.revision.ModpackFingerprint;
import cn.cubegarden.icbm.caching.source.revision.SourceStateService;
import cn.cubegarden.icbm.caching.source.selfcheck.MixinTargetVerifier;
import cn.cubegarden.icbm.caching.source.snapshot.LocalSnapshotSink;
import cn.cubegarden.icbm.caching.source.snapshot.RemoteSnapshotSink;
import cn.cubegarden.icbm.caching.source.snapshot.SnapshotPublisher;
import cn.cubegarden.icbm.caching.source.snapshot.SnapshotSink;
import cn.cubegarden.icbm.caching.source.tick.ChunkSnapshotTicker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
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
 * ICBM Caching 权威源 Fabric 模组入口（阶段1：worldEpoch/chunk revision 与快照；
 * 阶段2：接入控制服务远端 sink + modpack/registry/datapack 指纹）。
 *
 * <p>组合根：在覆盖维度加载时装配 {@link SourceRuntime}（Mixin 自检 → 状态装载 →
 * 快照发布 → tick 驱动），服务器停止时关闭 sink 并清除运行时。
 *
 * <p>sink 选择：服务器根目录 {@code icbm-control.properties} 配置了
 * {@code icbm.backend.forwardingSecret} 时启动 backend 监听（velocity 授信模式，master 主动
 * 连接），否则本地对象库 sink（开发模式）。控制服务不可用时发布器 fail closed
 * （保持 dirty 重试，不产生不确定基线）。
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

            SnapshotSink sink = createSink(server);
            SnapshotPublisher publisher = new SnapshotPublisher(sink, gate);
            DirtyTracker tracker = new DirtyTracker(new DirtyChunkSet());
            String registryFingerprint = registryFingerprint(server);
            ChunkSnapshotTicker ticker = new ChunkSnapshotTicker(
                    service, publisher, tracker, gate, registryFingerprint);
            ticker.announceInitialEpochs();

            SourceRuntime.install(new SourceRuntime(gate, service, tracker, publisher, ticker));
            LOGGER.info("[icbm-source] 运行时就绪：gate={} epoch={} sink={}",
                    gate.isEnabled(), service.epochFor(Level.OVERWORLD.identifier()),
                    sink.getClass().getSimpleName());
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

    /** 构建 sink：配置了 icbm.backend.forwardingSecret 时启动 backend 监听（velocity 授信模式，
     *  master 主动连接），否则本地对象库（开发模式）。 */
    private static SnapshotSink createSink(net.minecraft.server.MinecraftServer server) {
        Path configPath = server.getWorldPath(LevelResource.ROOT).resolve("icbm-control.properties");
        Properties props = new Properties();
        if (Files.isRegularFile(configPath)) {
            try (var in = Files.newInputStream(configPath)) {
                props.load(in);
            } catch (Exception e) {
                LOGGER.error("[icbm-source] 读取 icbm-control.properties 失败，回退本地 sink", e);
            }
        }
        String forwardingSecret = props.getProperty("icbm.backend.forwardingSecret", "").trim();
        // serverName：velocity.toml 服务器名（缓存隔离键，CACHE_PROTOCOL §1）；缺省退回 sourceServerId
        String sourceServerId = props.getProperty("icbm.control.sourceServerId",
                "source-" + UUID.randomUUID().toString().substring(0, 8));
        String serverName = props.getProperty("icbm.control.serverName", sourceServerId).trim();
        if (serverName.isEmpty()) {
            serverName = sourceServerId;
        }
        if (forwardingSecret.isEmpty()) {
            LOGGER.warn("[icbm-source] 未配置 icbm.backend.forwardingSecret，使用本地对象库 sink（开发模式）");
            return new LocalSnapshotSink(
                    server.getWorldPath(LevelResource.ROOT).resolve("icbm_snapshots"), serverName);
        }
        String bindHost = props.getProperty("icbm.backend.bind", "0.0.0.0").trim();
        int bindPort = Integer.parseInt(props.getProperty("icbm.backend.port", "11430"));
        String registryFingerprint = registryFingerprint(server);
        try {
            RemoteSnapshotSink sink = new RemoteSnapshotSink(
                    bindHost, bindPort, forwardingSecret, sourceServerId, serverName,
                    net.minecraft.SharedConstants.getCurrentVersion().name(),
                    net.minecraft.SharedConstants.getProtocolVersion(),
                    net.fabricmc.loader.api.FabricLoader.getInstance()
                            .getModContainer("fabricloader")
                            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"),
                    net.fabricmc.loader.api.FabricLoader.getInstance()
                            .getModContainer("fabric-api")
                            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"),
                    ModpackFingerprint.compute(server), registryFingerprint);
            sink.start();
            LOGGER.info("[icbm-source] backend 监听: {}:{}（velocity 授信，sourceServerId={}）",
                    bindHost, sink.port(), sourceServerId);
            return sink;
        } catch (Exception e) {
            LOGGER.error("[icbm-source] backend sink 初始化失败（fail closed）: {}", e.toString());
            throw new IllegalStateException("icbm-control 配置无效，无法启动 backend 通道", e);
        }
    }

    private static Path resolve(net.minecraft.server.MinecraftServer server, String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : server.getWorldPath(LevelResource.ROOT).resolve(p);
    }

    private static String registryFingerprint(net.minecraft.server.MinecraftServer server) {
        try {
            return ModpackFingerprint.registryAndDatapackFingerprint(server);
        } catch (Exception e) {
            LOGGER.error("[icbm-source] registry/datapack 指纹计算失败（fail closed）: {}", e.toString());
            throw new IllegalStateException("registry/datapack 指纹计算失败", e);
        }
    }
}

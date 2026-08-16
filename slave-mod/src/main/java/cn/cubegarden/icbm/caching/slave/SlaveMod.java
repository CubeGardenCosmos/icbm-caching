package cn.cubegarden.icbm.caching.slave;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.slave.config.SlaveConfig;
import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import cn.cubegarden.icbm.caching.slave.network.LoginVerifyGate;
import cn.cubegarden.icbm.caching.slave.network.PlayerBounds;
import cn.cubegarden.icbm.caching.slave.revision.ModpackFingerprint;
import cn.cubegarden.icbm.caching.slave.selfcheck.MixinTargetVerifier;
import cn.cubegarden.icbm.caching.slave.selfcheck.WorldSimGate;
import cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader;
import cn.cubegarden.icbm.caching.slave.world.SpawnRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ICBM Caching slave Fabric 模组入口（里程碑 4）：缓存会话后端（原生 MC 服务器进程，
 * 只做协议与预测，不做世界计算）。
 *
 * <p>组合根：服务器启动时装配（Mixin 自检 → 配置 → 对象库 → 控制客户端 → 运行时
 * install）；SERVER_STOPPING 关闭控制客户端并清除运行时。自检失败 → 不启动控制通道
 * （玩家登录被拒，fail closed）。
 */
public final class SlaveMod implements ModInitializer {

    public static final String MOD_ID = "icbm_slave";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // world 加载后装配（与源服 IcbmSource 同时序：registryAndDatapackFingerprint 需要
        // 完整注册表；SERVER_STARTING 时注册表未就绪 → 指纹与源不一致）
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD.register((server, world) -> {
            if (!world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
                    || SlaveRuntime.current() != null) {
                return;
            }
            this.setup(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(this::teardown);
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                cn.cubegarden.icbm.caching.slave.command.IcbmSlaveCommand.register(dispatcher);
                cn.cubegarden.icbm.caching.slave.command.BmToolCommand.register(dispatcher);
            });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SlaveRuntime runtime = SlaveRuntime.current();
            if (runtime != null) {
                runtime.spawnRegistry().placePlayer(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SlaveRuntime runtime = SlaveRuntime.current();
            if (runtime != null) {
                ServerPlayer player = handler.getPlayer();
                runtime.loginVerifyGate().onPlayerDisconnect(
                        player.getGameProfile().name(), player.getGameProfile().id(), "disconnect");
            }
        });
    }

    private void setup(MinecraftServer server) {
        if (!MixinTargetVerifier.verify()) {
            LOGGER.error("[icbm-slave] Mixin 目标自检失败（fail closed：slave 控制通道不启动，玩家登录被拒）");
            return;
        }
        // 阶段 9.1 边缘世界计算禁用：gamerule 兜底（random_tick_speed=0/spawn_mobs=false/
        // advance_time=false/advance_weather=false/raids=false，26.3 命名见 GameRules.java）
        // + 读回自检；未生效 → fail closed（不启动控制通道，与 Mixin 自检同路径）
        WorldSimGate.apply(server);
        if (!WorldSimGate.verify(server)) {
            LOGGER.error("[icbm-slave] 世界计算 gamerule 自检失败（fail closed：slave 控制通道不启动）");
            return;
        }
        SlaveConfig config = SlaveConfig.load(server.getWorldPath(LevelResource.ROOT));
        DimensionRef[] dimensions = config.dimensions().stream()
                .map(id -> {
                    String[] parts = id.split(":", 2);
                    return DimensionRef.of(parts[0], parts.length > 1 ? parts[1] : id);
                }).toArray(DimensionRef[]::new);

        SnapshotObjectStore store = new SnapshotObjectStore(config.storeRoot(), config.serverName());
        try {
            store.recover();
        } catch (Exception e) {
            LOGGER.error("[icbm-slave] 对象库恢复失败（fail closed）: {}", e.toString());
            return;
        }
        String registryFingerprint;
        try {
            registryFingerprint = ModpackFingerprint.registryAndDatapackFingerprint(server);
        } catch (Exception e) {
            LOGGER.error("[icbm-slave] registry/datapack 指纹计算失败（fail closed）: {}", e.toString());
            return;
        }

        SnapshotChunkLoader loader = new SnapshotChunkLoader(store, dimensions.length > 0 ? dimensions[0] : null);
        SpawnRegistry spawnRegistry = new SpawnRegistry();
        SlaveControlClient client = new SlaveControlClient(
                config, store, loader, spawnRegistry,
                SharedConstants.getCurrentVersion().name(),
                SharedConstants.getProtocolVersion(),
                net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer("fabricloader")
                        .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"),
                net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer("fabric-api")
                        .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"),
                ModpackFingerprint.compute(server), registryFingerprint);
        LoginVerifyGate loginVerifyGate = new LoginVerifyGate(client, config.serverName());
        PlayerBounds playerBounds = new PlayerBounds(loader);
        // 乐观操作（里程碑 7 阶段 5 + 阶段 8.1 真实玩家接线）：可丢弃 overlay + 终态回调
        // （COMMITTED 清预测等快照覆盖 / REJECTED RESYNC 落回 committed snapshot 并重应用
        // 其余 pending 预测；源服拒绝即世界未变，显示旧状态即与源一致）
        cn.cubegarden.icbm.caching.slave.operation.OperationOverlay overlay =
                new cn.cubegarden.icbm.caching.slave.operation.OperationOverlay();
        // 快照刷新/操作终态：乐观操作 COMMITTED 后新快照展示给玩家视角；REJECTED 后 RESYNC
        // 落回 committed snapshot（玩家在 chunk 内走就地刷新，避免卸载卡住 10s 延迟）
        cn.cubegarden.icbm.caching.slave.world.SnapshotReloader reloader =
                new cn.cubegarden.icbm.caching.slave.world.SnapshotReloader(loader, overlay);
        cn.cubegarden.icbm.caching.slave.operation.PlayerOperationHandler operationHandler =
                new cn.cubegarden.icbm.caching.slave.operation.PlayerOperationHandler(
                        client, overlay, loader, reloader);
        client.setOperationListener(operationHandler);
        client.setSnapshotReloader(reloader);
        client.setServerRef(server);
        // A8：断线 → 丢弃全部 overlay 预测（fail closed；重连后 master 重订阅补齐，世界以快照为准）
        client.setDisconnectCleanup(() -> {
            int dropped = overlay.discardAll();
            if (dropped > 0) {
                LOGGER.warn("[icbm-slave] master 断开：丢弃 {} 项 overlay 预测（fail closed）", dropped);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                tickServer -> reloader.onServerTick(tickServer));
        // 8.2 按需物化：撞墙/预取请求（节流/去重/冷却；源服生成 → 快照 → slave 装载）
        cn.cubegarden.icbm.caching.slave.operation.MaterializeRequester materializeRequester =
                new cn.cubegarden.icbm.caching.slave.operation.MaterializeRequester(client, loader);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                tickServer -> {
                    if (tickServer.overworld() != null) {
                        materializeRequester.onEndServerTick(tickServer.overworld());
                    }
                });
        // bmtool debug 状态提示（/bmtool debug toggle 开启后 action bar 输出性能/通讯/UUID）
        cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay debugOverlay =
                new cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay(client, config.uuidHex());
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(debugOverlay::onServerTick);
        // LLM 翻译（参考 llm-translator；草稿 llm-translation §3）：聊天按接收方 locale 自动
        // 翻译（绿色 [已翻译] 前缀）、告示牌/书本按命令翻译（绿色 Action Bar）；apiKey 空 →
        // 服务装配但 disabled（fail closed：聊天原文直通，命令提示未启用）
        cn.cubegarden.icbm.caching.slave.translate.LlmClient llmClient =
                new cn.cubegarden.icbm.caching.slave.translate.LlmClient(
                        config.llmBaseUrl(), config.llmModel(), config.llmApiKey(),
                        config.llmSystemPrompt(), config.llmTimeoutMillis(),
                        config.llmProxyHost(), config.llmProxyPort());
        cn.cubegarden.icbm.caching.slave.translate.TranslationService translationService =
                new cn.cubegarden.icbm.caching.slave.translate.TranslationService(config, llmClient);
        // fabric-message-api CHAT_MESSAGE 在广播前触发（PlayerListMixin @Inject HEAD）；
        // 本监听只观察不取消：原文按原版路径广播，译文异步后达
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register(
                (message, sender, boundChatType) -> translationService.onChatMessage(message, sender));
        SlaveRuntime runtime = SlaveRuntime.create(client, loader, spawnRegistry, loginVerifyGate, playerBounds,
                overlay, operationHandler, materializeRequester, config.brand(), debugOverlay, translationService);
        SlaveRuntime.install(runtime);
        client.start();
        // 初始同步闸门：装载 spawn 区块前等基线（WORLD_EPOCH/快照）；超时不阻断（fail closed）
        try {
            if (client.awaitInitialSync(8_000)) {
                LOGGER.info("[icbm-slave] 初始同步完成（基线就绪，可服务区块）");
            } else {
                LOGGER.warn("[icbm-slave] 初始同步超时（区块装载可能未命中，fail closed）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 主动装载出生点区块（官方 setChunkForced，ServerLevel.java:1517）：26.3 服务器启动
        // 不保证自动加载 spawn chunks，快照注入需要一次区块装载请求才生效（e2e 玩家视角验证）。
        try {
            cn.cubegarden.icbm.caching.protocol.DimensionRef overworld =
                    cn.cubegarden.icbm.caching.protocol.DimensionRef.of("minecraft", "overworld");
            Messages.SpawnPoint spawn = spawnRegistry.spawnFor(overworld);
            net.minecraft.server.level.ServerLevel overworldLevel = server.overworld();
            overworldLevel.setChunkForced((int) Math.floor(spawn.x() / 16.0),
                    (int) Math.floor(spawn.z() / 16.0), true);
            LOGGER.info("[icbm-slave] 已强制装载出生点区块: {},{}（spawn={},{},{}）",
                    (int) Math.floor(spawn.x() / 16.0), (int) Math.floor(spawn.z() / 16.0),
                    spawn.x(), spawn.y(), spawn.z());
        } catch (Exception e) {
            LOGGER.error("[icbm-slave] 强制装载出生点区块失败（区块可能为空，fail closed）: {}", e.toString());
        }
        LOGGER.info("[icbm-slave] 运行时就绪：master={}:{} serverName={} dims={} storeRoot={}",
                config.masterHost(), config.masterPort(), config.serverName(),
                config.dimensions(), config.storeRoot());
    }

    private void teardown(MinecraftServer server) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime != null) {
            runtime.client().close();
            if (runtime.translationService() != null) {
                runtime.translationService().close();
            }
        }
        SlaveRuntime.clear();
        LOGGER.info("[icbm-slave] 已停止");
    }
}

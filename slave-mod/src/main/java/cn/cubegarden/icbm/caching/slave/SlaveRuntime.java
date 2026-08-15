package cn.cubegarden.icbm.caching.slave;

import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import cn.cubegarden.icbm.caching.slave.network.LoginVerifyGate;
import cn.cubegarden.icbm.caching.slave.network.PlayerBounds;
import cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader;
import cn.cubegarden.icbm.caching.slave.world.SpawnRegistry;

/**
 * slave 运行时（组合根装配，fabric-source SourceRuntime 同款模式）：由 {@link SlaveMod}
 * 在服务器启动时一次性装配并 install；Mixin 通过 {@link #current()} 访问。
 *
 * <p>无全局可变状态：单例仅承载装配好的不可变组件引用；Mixin 目标缺失或控制通道未
 * 就绪时各组件自身 fail closed（返回 null/拒登录）。
 */
public final class SlaveRuntime {

    private static volatile SlaveRuntime current;

    private final SlaveControlClient client;
    private final SnapshotChunkLoader chunkLoader;
    private final SpawnRegistry spawnRegistry;
    private final LoginVerifyGate loginVerifyGate;
    private final PlayerBounds playerBounds;
    private final cn.cubegarden.icbm.caching.slave.operation.OperationOverlay overlay;
    private final cn.cubegarden.icbm.caching.slave.operation.PlayerOperationHandler operationHandler;
    private final String brand;
    private final cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay debugOverlay;

    private SlaveRuntime(SlaveControlClient client, SnapshotChunkLoader chunkLoader,
                         SpawnRegistry spawnRegistry, LoginVerifyGate loginVerifyGate,
                         PlayerBounds playerBounds,
                         cn.cubegarden.icbm.caching.slave.operation.OperationOverlay overlay,
                         cn.cubegarden.icbm.caching.slave.operation.PlayerOperationHandler operationHandler,
                         String brand,
                         cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay debugOverlay) {
        this.client = client;
        this.chunkLoader = chunkLoader;
        this.spawnRegistry = spawnRegistry;
        this.loginVerifyGate = loginVerifyGate;
        this.playerBounds = playerBounds;
        this.overlay = overlay;
        this.operationHandler = operationHandler;
        this.brand = brand;
        this.debugOverlay = debugOverlay;
    }

    public static SlaveRuntime create(SlaveControlClient client, SnapshotChunkLoader chunkLoader,
                                      SpawnRegistry spawnRegistry, LoginVerifyGate loginVerifyGate,
                                      PlayerBounds playerBounds,
                                      cn.cubegarden.icbm.caching.slave.operation.OperationOverlay overlay,
                                      cn.cubegarden.icbm.caching.slave.operation.PlayerOperationHandler operationHandler,
                                      String brand,
                                      cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay debugOverlay) {
        return new SlaveRuntime(client, chunkLoader, spawnRegistry, loginVerifyGate, playerBounds, overlay,
                operationHandler, brand, debugOverlay);
    }

    public static void install(SlaveRuntime runtime) {
        current = runtime;
    }

    public static SlaveRuntime current() {
        return current;
    }

    public static void clear() {
        current = null;
    }

    public SlaveControlClient client() {
        return this.client;
    }

    public SnapshotChunkLoader chunkLoader() {
        return this.chunkLoader;
    }

    public SpawnRegistry spawnRegistry() {
        return this.spawnRegistry;
    }

    public LoginVerifyGate loginVerifyGate() {
        return this.loginVerifyGate;
    }

    public PlayerBounds playerBounds() {
        return this.playerBounds;
    }

    /** 乐观操作 overlay（可丢弃预测；玩家网络层经它查询/登记预测）。 */
    public cn.cubegarden.icbm.caching.slave.operation.OperationOverlay overlay() {
        return this.overlay;
    }

    /** 真实玩家操作接线（阶段 8.1；SpectateGateMixin 路由互动包到操作管道）。 */
    public cn.cubegarden.icbm.caching.slave.operation.PlayerOperationHandler operationHandler() {
        return this.operationHandler;
    }

    /** 服务器品牌（F3 Brand，MinecraftServer.getServerModName 覆盖值）。 */
    public String brand() {
        return this.brand;
    }

    /** 调试提示 overlay（/bmtool debug toggle；未装配时为 null）。 */
    public cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay debugOverlay() {
        return this.debugOverlay;
    }
}

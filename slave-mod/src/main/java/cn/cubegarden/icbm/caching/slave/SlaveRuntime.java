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
    private final String brand;

    private SlaveRuntime(SlaveControlClient client, SnapshotChunkLoader chunkLoader,
                         SpawnRegistry spawnRegistry, LoginVerifyGate loginVerifyGate,
                         PlayerBounds playerBounds, String brand) {
        this.client = client;
        this.chunkLoader = chunkLoader;
        this.spawnRegistry = spawnRegistry;
        this.loginVerifyGate = loginVerifyGate;
        this.playerBounds = playerBounds;
        this.brand = brand;
    }

    public static SlaveRuntime create(SlaveControlClient client, SnapshotChunkLoader chunkLoader,
                                      SpawnRegistry spawnRegistry, LoginVerifyGate loginVerifyGate,
                                      PlayerBounds playerBounds, String brand) {
        return new SlaveRuntime(client, chunkLoader, spawnRegistry, loginVerifyGate, playerBounds, brand);
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

    /** 服务器品牌（F3 Brand，MinecraftServer.getServerModName 覆盖值）。 */
    public String brand() {
        return this.brand;
    }
}

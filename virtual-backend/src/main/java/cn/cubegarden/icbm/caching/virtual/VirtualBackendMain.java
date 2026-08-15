package cn.cubegarden.icbm.caching.virtual;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.virtual.config.VirtualBackendConfig;
import cn.cubegarden.icbm.caching.virtual.control.ReplicationClient;
import cn.cubegarden.icbm.caching.virtual.net.ClientConnection;
import cn.cubegarden.icbm.caching.virtual.net.GameServer;
import cn.cubegarden.icbm.caching.virtual.net.ProtocolState;
import cn.cubegarden.icbm.caching.virtual.net.SessionHandler;
import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import cn.cubegarden.icbm.caching.virtual.session.ConfigurationSession;
import cn.cubegarden.icbm.caching.virtual.session.HandshakeSession;
import cn.cubegarden.icbm.caching.virtual.session.LoginSession;
import cn.cubegarden.icbm.caching.virtual.session.PlaySession;
import cn.cubegarden.icbm.caching.virtual.session.StatusSession;
import cn.cubegarden.icbm.caching.virtual.tls.TlsClientContextFactory;
import cn.cubegarden.icbm.caching.virtual.world.SnapshotRepository;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 虚拟后端（阶段3）：独立 26.3 旁观者后端服务。
 *
 * <p>接线：游戏监听（netty）+ 控制平面（mTLS SLAVE 角色订阅/推送）+ 本地对象库
 * （ServeGate 语义 fail closed）+ 转储注册表（manifest 指纹校验）。
 *
 * <p>验收映射：
 * <ul>
 *   <li>玩家移动不建立源服游戏会话：本进程无任何到源服游戏端口的连接，仅经控制平面收快照；</li>
 *   <li>未命中/epoch/fingerprint 不匹配不展示旧区块：{@link SnapshotRepository} 只发
 *       SERVABLE 区块 + 启动指纹校验；</li>
 *   <li>不写 committed snapshot 或源服世界：对象库只读装载，全部互动包拒绝。 </li>
 * </ul>
 */
public final class VirtualBackendMain implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualBackendMain.class);

    private final VirtualBackendConfig config;
    private final RegistryBundle registry;
    private final SnapshotObjectStore store;
    private final SnapshotRepository repository;
    private final ReplicationClient replicationClient;
    private final GameServer gameServer;
    private final AtomicInteger playerIds = new AtomicInteger(1);

    public VirtualBackendMain(VirtualBackendConfig config, RegistryBundle registry) throws Exception {
        this.config = config;
        this.registry = registry;

        String fingerprintError = registry.verifyFingerprint(config);
        if (fingerprintError != null) {
            throw new IllegalStateException("启动指纹校验失败（fail closed）: " + fingerprintError);
        }

        this.store = new SnapshotObjectStore(Path.of(config.storeRoot()), config.serverId());
        this.store.recover();

        DimensionRef dimension = new DimensionRef(config.dimensionNamespace(), config.dimensionPath());
        this.repository = new SnapshotRepository(
                this.store, dimension, registry,
                (int) registry.worldLong("minSectionY", -4),
                (int) registry.worldLong("maxSectionY", 19));

        SSLContext ssl = TlsClientContextFactory.create(
                Path.of(config.keyStore()), config.keyStorePassword(),
                Path.of(config.trustStore()), config.trustStorePassword());
        this.replicationClient = new ReplicationClient(
                config.controlHost(), config.controlPort(), config.serverId(),
                config.minecraftVersion(), config.runtimeProtocol(),
                config.fabricLoaderVersion(), config.fabricApiVersion(),
                config.modpackFingerprint(), config.registryAndDatapackFingerprint(),
                ssl, 15000, 3000, this.store, this.repository, new DimensionRef[]{dimension}, 16);

        this.gameServer = new GameServer(config, this::newConnection);
    }

    private ClientConnection newConnection(io.netty.channel.Channel channel) {
        ClientConnection connection = new ClientConnection(this.gameServer);
        connection.setupState(ProtocolState.HANDSHAKE,
                new HandshakeSession(connection, this.config, this::newSession));
        return connection;
    }

    private SessionHandler newSession(ProtocolState state, ClientConnection connection) {
        return switch (state) {
            case STATUS -> new StatusSession(connection, this.config);
            case LOGIN -> new LoginSession(connection, this::newSession);
            case CONFIGURATION -> new ConfigurationSession(connection, this.registry, this::newSession);
            case PLAY -> new PlaySession(connection, this.config, this.registry,
                    this.repository, this.playerIds.getAndIncrement());
            default -> throw new IllegalStateException("unexpected state: " + state);
        };
    }

    public void start() throws InterruptedException {
        this.replicationClient.start();
        this.gameServer.start();
        LOGGER.info("[vb] 虚拟后端启动完成: 维度={} 视距={}",
                this.config.dimensionId(), this.config.viewDistance());
    }

    @Override
    public void close() {
        this.gameServer.close();
        this.replicationClient.close();
        this.repository.close();
        this.store.close();
    }

    public int connectionCount() {
        return this.gameServer.connectionCount();
    }

    public boolean masterConnected() {
        return this.replicationClient.connected();
    }

    public SnapshotObjectStore store() {
        return this.store;
    }

    public SnapshotRepository repository() {
        return this.repository;
    }

    public RegistryBundle registry() {
        return this.registry;
    }

    public VirtualBackendConfig config() {
        return this.config;
    }

    public static void main(String[] args) throws Exception {
        String configPath = System.getenv("ICBM_VIRTUAL_BACKEND_CONFIG");
        if (configPath == null) {
            configPath = args.length > 0 ? args[0] : "virtual-backend.properties";
        }
        VirtualBackendConfig config = VirtualBackendConfig.load(Path.of(configPath));
        RegistryBundle registry = RegistryBundle.load(VirtualBackendMain.class.getClassLoader());
        VirtualBackendMain backend = new VirtualBackendMain(config, registry);
        backend.start();
        Runtime.getRuntime().addShutdownHook(new Thread(backend::close, "icbm-vb-shutdown"));
        Thread.currentThread().join();
    }
}

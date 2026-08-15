package cn.cubegarden.icbm.caching.control;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlServer;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.net.SourceConnector;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * master 入口（阶段2：control-service 并入 master 后的独立进程形态；Velocity 插件入口
 * 于阶段5 接线）：恢复对象库与事件日志 → 启动单端口封装通道服务。
 *
 * <p>配置：环境变量 {@code ICBM_CONTROL_CONFIG} 指定 properties 路径；
 * 缺省使用工作目录下 {@code control.properties} 或全默认值。
 */
public final class ControlServiceMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlServiceMain.class);

    private ControlServiceMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(System.getenv().getOrDefault("ICBM_CONTROL_CONFIG", "control.properties"));
        ServiceConfig config = loadConfig(configPath);
        if (config.psk().isEmpty() || config.encapsulatedPort() <= 0) {
            throw new IllegalArgumentException("封装通道未启用：需配置 control.psk（非空）与 control.encapsulatedPort（>0）");
        }
        Files.createDirectories(config.storeRoot());

        SnapshotObjectStore store = new SnapshotObjectStore(config.storeRoot(), config.serverName());
        store.recover();
        EventLog eventLog = new EventLog(config.eventLogPath(), config.maxEventLogEntries());
        eventLog.recover();
        PeerRegistry registry = new PeerRegistry(config.roleBindings());
        Replicator replicator = new Replicator(store, eventLog, config);
        ControlService service = new ControlService(store, eventLog, replicator, registry, config);
        cn.cubegarden.icbm.caching.control.auth.SessionRegistry sessionRegistry =
                new cn.cubegarden.icbm.caching.control.auth.SessionRegistry();
        service.setSessionRegistry(sessionRegistry);

        // Auth Gateway 完整装配（里程碑 5）：票据单次兑换 + Mojang 验证 + 断言签名 + 落点判定
        cn.cubegarden.icbm.caching.control.auth.TicketVault ticketVault =
                new cn.cubegarden.icbm.caching.control.auth.TicketVault();
        service.setTicketVault(ticketVault);
        cn.cubegarden.icbm.caching.control.auth.MojangSessionVerifier verifier =
                new cn.cubegarden.icbm.caching.control.auth.MojangSessionVerifier(
                        config.auth().verifyMojang(), config.auth().mojangTimeoutMillis());
        byte[] assertionKey = config.auth().assertionKey().isEmpty()
                ? randomKey() : config.auth().assertionKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        cn.cubegarden.icbm.caching.control.auth.IdentitySigner signer =
                cn.cubegarden.icbm.caching.control.auth.IdentitySigner.fromKey(assertionKey);
        cn.cubegarden.icbm.caching.control.routing.BackendHealth backendHealth =
                new cn.cubegarden.icbm.caching.control.routing.BackendHealth();
        cn.cubegarden.icbm.caching.control.routing.CircuitBreaker circuitBreaker =
                new cn.cubegarden.icbm.caching.control.routing.CircuitBreaker(3, 30_000);
        cn.cubegarden.icbm.caching.control.routing.RoutingPolicy routing = new cn.cubegarden.icbm.caching.control.routing.RoutingPolicy(
                config.allowedServerNames(), backendHealth, circuitBreaker,
                config.heartbeatIntervalMillis() * 3);
        cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener authGateway =
                new cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener(
                        sessionRegistry, ticketVault, verifier, signer, routing,
                        config.serverId(), 60_000);
        cn.cubegarden.icbm.caching.control.bridge.PacketLog packetLog =
                new cn.cubegarden.icbm.caching.control.bridge.PacketLog(4096);
        cn.cubegarden.icbm.caching.control.bridge.McPassthroughBridge bridge =
                new cn.cubegarden.icbm.caching.control.bridge.McPassthroughBridge(routing, circuitBreaker, packetLog);
        EncapsulatedControlServer encapsulated = new EncapsulatedControlServer(config, service, replicator,
                registry, new cn.cubegarden.icbm.caching.control.bridge.CompositeSessionListener(authGateway, bridge));
        encapsulated.start();

        SourceConnector sourceConnector = null;
        if (config.backend() != null) {
            sourceConnector = new SourceConnector(config, service, replicator, registry);
            sourceConnector.start();
            bridge.attachSourceConnector(sourceConnector);
        }
        SourceConnector sourceRef = sourceConnector;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            encapsulated.close();
            if (sourceRef != null) {
                sourceRef.close();
            }
        }));
        LOGGER.info("[control] master 就绪：对象库={} events={} 封装通道={}:{} psk={}位 peerBindings={} backend={}",
                config.storeRoot(), config.eventLogPath(), config.host(), encapsulated.port(),
                config.psk().length(), config.roleBindings().keySet(),
                config.backend() == null ? "未启用" : config.backend().host() + ":" + config.backend().port());
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    private static ServiceConfig loadConfig(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            LOGGER.warn("[control] 未找到配置 {}，使用默认值", path.toAbsolutePath());
            return ServiceConfig.defaults(Path.of(".").toAbsolutePath());
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        }
        return ServiceConfig.from(props, path.toAbsolutePath().getParent());
    }
}

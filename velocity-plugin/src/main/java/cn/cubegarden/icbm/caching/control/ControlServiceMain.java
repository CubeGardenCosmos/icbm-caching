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
        // 登录握手（借用 Mojang authlib 官方实现验证；RSA 在 master，slave 透传握手）
        cn.cubegarden.icbm.caching.control.auth.LoginHandshake handshake =
                new cn.cubegarden.icbm.caching.control.auth.LoginHandshake(
                        config.auth().verifyMojang(), config.auth().hasJoinedIp());
        byte[] assertionKey = config.auth().assertionKey().isEmpty()
                ? loadOrCreateAssertionKey(config.storeRoot())
                : config.auth().assertionKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
                        sessionRegistry, ticketVault, handshake, signer, routing,
                        config.serverId(), 60_000);
        // 过期断言周期清理（A9 审计修复；每 30s 一次，防会话表泄漏）
        java.util.concurrent.ScheduledExecutorService authCleanup =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "icbm-auth-cleanup");
                    t.setDaemon(true);
                    return t;
                });
        authCleanup.scheduleAtFixedRate(() -> {
            try {
                authGateway.purgeExpired();
            } catch (RuntimeException e) {
                LOGGER.warn("[control] 断言过期清理异常: {}", e.toString());
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
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
        if (sourceConnector != null) {
            service.setSourceConnector(sourceConnector);
        }

        java.util.concurrent.CountDownLatch shutdown = new java.util.concurrent.CountDownLatch(1);
        EventLog eventLogRef = eventLog;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            encapsulated.close();
            if (sourceRef != null) {
                sourceRef.close();
            }
            eventLogRef.close();
            authCleanup.shutdownNow();
            shutdown.countDown();
        }));
        LOGGER.info("[control] master 就绪：对象库={} events={} 封装通道={}:{} psk={}位 peerBindings={} backend={}",
                config.storeRoot(), config.eventLogPath(), config.host(), encapsulated.port(),
                config.psk().length(), config.roleBindings().keySet(),
                config.backend() == null ? "未启用" : config.backend().host() + ":" + config.backend().port());
        // 服务线程均为 daemon（测试友好）：独立进程形态主线程阻塞，Ctrl-C/TERM 经 shutdown hook 退出
        shutdown.await();
    }

    /**
     * 断言签名密钥持久化（A4 审计修复）：未配置时首次生成并写入
     * {@code <storeRoot>/assertion.key}（0600），重启复用——随机每次启动会令旧断言全部
     * 失效、踢掉活跃会话。密钥随文件分发（运维），或配置 control.auth.assertionKey。
     */
    private static byte[] loadOrCreateAssertionKey(Path storeRoot) {
        Path keyFile = storeRoot.resolve("assertion.key");
        try {
            if (Files.isRegularFile(keyFile)) {
                byte[] existing = Files.readAllBytes(keyFile);
                if (existing.length >= 16) {
                    return existing;
                }
                LOGGER.warn("[control] assertion.key 过短（{} 字节），重新生成", existing.length);
            }
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            Files.createDirectories(storeRoot);
            Files.write(keyFile, key, java.nio.file.StandardOpenOption.CREATE_NEW);
            try {
                Files.setPosixFilePermissions(keyFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统（Windows）跳过
            }
            LOGGER.info("[control] 已生成断言签名密钥: {}", keyFile);
            return key;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("断言签名密钥持久化失败（fail closed）: " + keyFile, e);
        }
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

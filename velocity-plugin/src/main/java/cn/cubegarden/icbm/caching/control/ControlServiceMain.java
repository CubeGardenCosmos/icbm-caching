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
        EncapsulatedControlServer encapsulated = new EncapsulatedControlServer(config, service, replicator,
                registry, new cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener(sessionRegistry));
        encapsulated.start();

        SourceConnector sourceConnector = null;
        if (config.backend() != null) {
            sourceConnector = new SourceConnector(config, service, replicator, registry);
            sourceConnector.start();
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

package cn.cubegarden.icbm.caching.control.config;

import cn.cubegarden.icbm.caching.protocol.Role;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 控制服务配置（加载自 properties 文件或默认值；密钥路径缺省时禁用 TLS 仅限测试）。
 *
 * <p>字段：监听地址、mTLS 密钥/信任库、对象库根目录、事件日志路径、角色绑定
 * （证书 CN → {@code cn.cubegarden.icbm.caching.protocol.Role}）、流控窗口与滞后阈值。
 */
public record ServiceConfig(
        String host,
        int port,
        Path keyStore,
        String keyStorePassword,
        Path trustStore,
        String trustStorePassword,
        boolean requireClientCert,
        Path storeRoot,
        Path eventLogPath,
        int maxEventLogEntries,
        String serverId,
        String serverName,
        String psk,
        int encapsulatedPort,
        Map<String, String> roleBindings, // bindingId(cert CN / UUID) -> role 名
        int maxFrameBytes,
        int pushWindow,
        long heartbeatIntervalMillis,
        long lagThresholdMillis,
        int runtimeProtocol
) {

    public static ServiceConfig defaults(Path root) {
        return new ServiceConfig(
                "0.0.0.0", 11420,
                null, "", null, "", false,
                root.resolve("objects"), root.resolve("events.log"), 100_000,
                "master-1", "default", "", 0,
                new LinkedHashMap<>(), 16 * 1024 * 1024,
                64, 30_000, 90_000,
                1_073_742_154 // 26.3-snapshot-8 runtime protocol（docs/VERSION_FREEZE.md）
        );
    }

    /** 从 properties 加载；未知键报错（防拼写错误静默生效）。 */
    public static ServiceConfig from(Properties props, Path baseDir) {
        ServiceConfig d = defaults(baseDir);
        Map<String, String> bindings = new LinkedHashMap<>();
        String bindingsValue = props.getProperty("control.roleBindings", "").trim();
        if (!bindingsValue.isEmpty()) {
            for (String pair : bindingsValue.split(",")) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("control.roleBindings 格式错误: " + pair);
                }
                String roleName = kv[1].trim();
                boolean known = false;
                for (Role role : Role.values()) {
                    if (role.name().equals(roleName)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    throw new IllegalArgumentException("control.roleBindings 未知角色: " + roleName);
                }
                bindings.put(kv[0].trim(), roleName);
            }
        }
        return new ServiceConfig(
                props.getProperty("control.host", d.host()),
                Integer.parseInt(props.getProperty("control.port", Integer.toString(d.port()))),
                path(props, "control.keyStore", d.keyStore(), baseDir),
                props.getProperty("control.keyStorePassword", d.keyStorePassword()),
                path(props, "control.trustStore", d.trustStore(), baseDir),
                props.getProperty("control.trustStorePassword", d.trustStorePassword()),
                Boolean.parseBoolean(props.getProperty("control.requireClientCert", "true")),
                path(props, "control.storeRoot", d.storeRoot(), baseDir),
                path(props, "control.eventLogPath", d.eventLogPath(), baseDir),
                Integer.parseInt(props.getProperty("control.maxEventLogEntries", Integer.toString(d.maxEventLogEntries()))),
                props.getProperty("control.serverId", d.serverId()),
                props.getProperty("control.serverName", d.serverName()),
                props.getProperty("control.psk", d.psk()),
                Integer.parseInt(props.getProperty("control.encapsulatedPort", Integer.toString(d.encapsulatedPort()))),
                bindings,
                Integer.parseInt(props.getProperty("control.maxFrameBytes", Integer.toString(d.maxFrameBytes()))),
                Integer.parseInt(props.getProperty("control.pushWindow", Integer.toString(d.pushWindow()))),
                Long.parseLong(props.getProperty("control.heartbeatIntervalMillis", Long.toString(d.heartbeatIntervalMillis()))),
                Long.parseLong(props.getProperty("control.lagThresholdMillis", Long.toString(d.lagThresholdMillis()))),
                Integer.parseInt(props.getProperty("control.runtimeProtocol", Integer.toString(d.runtimeProtocol())))
        );
    }

    private static Path path(Properties props, String key, Path fallback, Path baseDir) {
        String value = props.getProperty(key, "").trim();
        if (value.isEmpty()) {
            return fallback;
        }
        Path p = Path.of(value);
        return p.isAbsolute() ? p : baseDir.resolve(p);
    }

    public boolean tlsEnabled() {
        return this.keyStore != null && Files.isRegularFile(this.keyStore);
    }
}

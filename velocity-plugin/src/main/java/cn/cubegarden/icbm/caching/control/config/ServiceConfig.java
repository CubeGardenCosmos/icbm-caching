package cn.cubegarden.icbm.caching.control.config;

import cn.cubegarden.icbm.caching.protocol.Role;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * master 配置（加载自 properties 文件或默认值）。
 *
 * <p>字段：封装通道监听地址/端口、公共密码（PSK，运维手册禁止弱密码）、对象库根目录、
 * 事件日志路径、角色绑定（**AUTH UUID → 角色**；SLAVE / SOURCE）、流控窗口与滞后阈值。
 *
 * <p>传输约束（CACHE_PROTOCOL §1）：单端口封装通道（magic + AUTH 公共密码证明 +
 * ChaCha20-Poly1305），不使用 TLS；畸形/原版握手 RST。
 */
public record ServiceConfig(
        String host,
        Path storeRoot,
        Path eventLogPath,
        int maxEventLogEntries,
        String serverId,
        String serverName,
        String psk,
        int encapsulatedPort,
        Map<String, String> roleBindings, // auth UUID(hex) -> role 名（SLAVE/SOURCE）
        int maxFrameBytes,
        int pushWindow,
        long heartbeatIntervalMillis,
        long lagThresholdMillis,
        int runtimeProtocol
) {

    public static ServiceConfig defaults(Path root) {
        return new ServiceConfig(
                "0.0.0.0",
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
}

package cn.cubegarden.icbm.caching.control.config;

import cn.cubegarden.icbm.caching.protocol.Role;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
        BackendServer backend,            // 源服 backend（velocity 授信；null = 不启用）
        int maxFrameBytes,
        int pushWindow,
        long heartbeatIntervalMillis,
        long lagThresholdMillis,
        int runtimeProtocol,
        AuthConfig auth,                  // Auth Gateway（Mojang 验证/断言签名）
        List<String> allowedServerNames   // 落点白名单（空 = 全部允许）
) {

    /** 源服 backend 连接（master 主动连接；PSK = velocity forwarding secret）。 */
    public record BackendServer(String host, int port, String forwardingSecret) {
    }

    /**
     * Auth Gateway 配置（HIERARCHY §2 / CACHE_PROTOCOL §9）。
     *
     * <p>verifyMojang：调用 Mojang session server 的 hasJoined 完成正版验证；false = offline
     * 放行但仍记账（开发/测试惯例，生产必须 true，运维手册约束）。mojangTimeoutMillis：
     * hasJoined HTTP 超时（超时/不可用 → AuthenticationUnavailable 语义，fail closed 拒绝）。
     * assertionKey：身份断言 HMAC 签名密钥（离线保护；重启后旧断言自然失效，断言短期有效）。
     */
    public record AuthConfig(boolean verifyMojang, long mojangTimeoutMillis, String assertionKey) {
    }

    /** 15 参数便捷构造（测试/默认装配）：auth = offline 默认 + 空白名单。 */
    public ServiceConfig(String host, Path storeRoot, Path eventLogPath, int maxEventLogEntries,
                         String serverId, String serverName, String psk, int encapsulatedPort,
                         Map<String, String> roleBindings, BackendServer backend,
                         int maxFrameBytes, int pushWindow,
                         long heartbeatIntervalMillis, long lagThresholdMillis, int runtimeProtocol) {
        this(host, storeRoot, eventLogPath, maxEventLogEntries, serverId, serverName, psk,
                encapsulatedPort, roleBindings, backend, maxFrameBytes, pushWindow,
                heartbeatIntervalMillis, lagThresholdMillis, runtimeProtocol,
                new AuthConfig(false, 10_000, ""), List.of());
    }

    public static ServiceConfig defaults(Path root) {
        return new ServiceConfig(
                "0.0.0.0",
                root.resolve("objects"), root.resolve("events.log"), 100_000,
                "master-1", "default", "", 0,
                new LinkedHashMap<>(), null, 16 * 1024 * 1024,
                64, 30_000, 90_000,
                1_073_742_154, // 26.3-snapshot-8 runtime protocol（docs/VERSION_FREEZE.md）
                new AuthConfig(false, 10_000, ""), List.of()
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
        String psk = props.getProperty("control.psk", d.psk());
        if (psk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 16) {
            throw new IllegalArgumentException("control.psk 过短（至少 16 字节；运维手册禁止弱公共密码，A6 审计修复）");
        }
        String backendHost = props.getProperty("control.backendHost", "").trim();
        int backendPort = Integer.parseInt(props.getProperty("control.backendPort", "11430"));
        String backendSecret = props.getProperty("control.backendForwardingSecret", "").trim();
        if (!backendSecret.isEmpty() && backendSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 16) {
            throw new IllegalArgumentException("control.backendForwardingSecret 过短（至少 16 字节）");
        }
        BackendServer backend = (!backendHost.isEmpty() && !backendSecret.isEmpty())
                ? new BackendServer(backendHost, backendPort, backendSecret) : null;
        return new ServiceConfig(
                props.getProperty("control.host", d.host()),
                path(props, "control.storeRoot", d.storeRoot(), baseDir),
                path(props, "control.eventLogPath", d.eventLogPath(), baseDir),
                Integer.parseInt(props.getProperty("control.maxEventLogEntries", Integer.toString(d.maxEventLogEntries()))),
                props.getProperty("control.serverId", d.serverId()),
                props.getProperty("control.serverName", d.serverName()),
                psk,
                Integer.parseInt(props.getProperty("control.encapsulatedPort", Integer.toString(d.encapsulatedPort()))),
                bindings,
                backend,
                Integer.parseInt(props.getProperty("control.maxFrameBytes", Integer.toString(d.maxFrameBytes()))),
                Integer.parseInt(props.getProperty("control.pushWindow", Integer.toString(d.pushWindow()))),
                Long.parseLong(props.getProperty("control.heartbeatIntervalMillis", Long.toString(d.heartbeatIntervalMillis()))),
                Long.parseLong(props.getProperty("control.lagThresholdMillis", Long.toString(d.lagThresholdMillis()))),
                Integer.parseInt(props.getProperty("control.runtimeProtocol", Integer.toString(d.runtimeProtocol()))),
                new AuthConfig(
                        Boolean.parseBoolean(props.getProperty("control.auth.verifyMojang",
                                Boolean.toString(d.auth().verifyMojang()))),
                        Long.parseLong(props.getProperty("control.auth.mojangTimeoutMillis",
                                Long.toString(d.auth().mojangTimeoutMillis()))),
                        assertionKey(props.getProperty("control.auth.assertionKey", d.auth().assertionKey()))),
                Arrays.stream(props.getProperty("control.allowedServerNames", "").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList()
        );
    }

    /** assertionKey 校验（A6：签名密钥至少 16 字节；空 = 未配置，由入口生成并持久化）。 */
    private static String assertionKey(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.isEmpty() && trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 16) {
            throw new IllegalArgumentException("control.auth.assertionKey 过短（至少 16 字节）");
        }
        return trimmed;
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

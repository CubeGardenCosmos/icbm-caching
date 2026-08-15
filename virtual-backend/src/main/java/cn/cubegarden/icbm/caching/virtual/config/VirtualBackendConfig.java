package cn.cubegarden.icbm.caching.virtual.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 虚拟后端运行配置（properties 风格，环境变量 ICBM_VIRTUAL_BACKEND_CONFIG 指定路径）。
 *
 * <p>关键字段：游戏监听、控制服务地址（mTLS，SLAVE 角色）、本地对象库根、
 * 冻结指纹（与 registry 转储 manifest 不一致 → fail closed）、服务维度与视距。
 */
public record VirtualBackendConfig(
        String gameHost,
        int gamePort,
        String controlHost,
        int controlPort,
        String serverId,
        String keyStore,
        String keyStorePassword,
        String trustStore,
        String trustStorePassword,
        String storeRoot,
        String minecraftVersion,
        int runtimeProtocol,
        String fabricLoaderVersion,
        String fabricApiVersion,
        String modpackFingerprint,
        String registryAndDatapackFingerprint,
        String dimensionNamespace,
        String dimensionPath,
        int viewDistance,
        int keepAliveIntervalMillis
) {

    public static VirtualBackendConfig load(Path file) throws java.io.IOException {
        Map<String, String> props = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            props.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return of(props);
    }

    public static VirtualBackendConfig of(Map<String, String> p) {
        return new VirtualBackendConfig(
                p.getOrDefault("virtualBackend.gameHost", "0.0.0.0"),
                Integer.parseInt(p.getOrDefault("virtualBackend.gamePort", "25575")),
                p.get("control.host"),
                Integer.parseInt(p.getOrDefault("control.port", "11420")),
                p.getOrDefault("virtualBackend.serverId", "virtual-backend-1"),
                p.get("control.keyStore"),
                p.getOrDefault("control.keyStorePassword", "changeit"),
                p.get("control.trustStore"),
                p.getOrDefault("control.trustStorePassword", "changeit"),
                p.getOrDefault("virtualBackend.storeRoot", "build/workspace/virtual-backend/objects"),
                p.getOrDefault("fingerprint.minecraftVersion", "26.3-snapshot-8"),
                Integer.parseInt(p.getOrDefault("fingerprint.runtimeProtocol", "1073742154")),
                p.getOrDefault("fingerprint.fabricLoaderVersion", "0.19.3"),
                p.getOrDefault("fingerprint.fabricApiVersion", "0.157.1+26.3"),
                p.getOrDefault("fingerprint.modpackFingerprint", ""),
                p.getOrDefault("fingerprint.registryAndDatapackFingerprint", ""),
                p.getOrDefault("virtualBackend.dimensionNamespace", "minecraft"),
                p.getOrDefault("virtualBackend.dimensionPath", "overworld"),
                Integer.parseInt(p.getOrDefault("virtualBackend.viewDistance", "8")),
                Integer.parseInt(p.getOrDefault("virtualBackend.keepAliveIntervalMillis", "15000"))
        );
    }

    /** 服务维度（Join Game 与区块窗口所在维度）。 */
    public String dimensionId() {
        return this.dimensionNamespace + ":" + this.dimensionPath;
    }
}

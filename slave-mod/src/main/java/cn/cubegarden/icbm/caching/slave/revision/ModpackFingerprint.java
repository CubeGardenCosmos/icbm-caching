package cn.cubegarden.icbm.caching.slave.revision;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * modpack / registry+datapack 指纹（与源服 fabric-source ModpackFingerprint 同算法，
 * CACHE_PROTOCOL HELLO 指纹校验要求 slave 与源服一致；跨模组 jar 无法共享，
 * 算法确定性见草稿 phase2-control-protocol §5 与 VERSION_FREEZE.md）。
 */
public final class ModpackFingerprint {

    private ModpackFingerprint() {
    }

    /** 计算当前服务器 modpack 指纹（确定性：所有输入排序后拼接再 SHA-256）。 */
    public static String compute(MinecraftServer server) {
        List<String> parts = new ArrayList<>();
        parts.add(SharedConstants.getCurrentVersion().name());
        FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString())
                .sorted()
                .forEach(parts::add);
        server.getPackRepository().getSelectedIds().stream()
                .sorted()
                .forEach(parts::add);
        return sha256Hex(String.join("|", parts));
    }

    /**
     * registry + datapack 指纹（HELLO.registryAndDatapackFingerprint）。
     *
     * <p>注册表：{@code registryKey=id1,id2,...}（排序后拼接再整体哈希）；
     * 数据包：{@code path@sha256}（按路径排序）。
     */
    public static String registryAndDatapackFingerprint(MinecraftServer server) throws Exception {
        List<String> parts = new ArrayList<>();
        server.registryAccess().registries().forEach(entry -> {
            List<String> ids = new ArrayList<>();
            entry.value().keySet().stream().map(Identifier::toString).sorted().forEach(ids::add);
            parts.add(entry.key().identifier().toString() + "=" + String.join(",", ids));
        });
        List<String> packFiles = new ArrayList<>();
        server.getResourceManager().listResources("", resourceId -> true).entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .forEach(e -> {
                    try (InputStream in = e.getValue().open()) {
                        packFiles.add(e.getKey() + "@" + sha256Hex(in.readAllBytes()));
                    } catch (Exception ex) {
                        throw new IllegalStateException("数据包资源读取失败: " + e.getKey(), ex);
                    }
                });
        parts.addAll(packFiles);
        return sha256Hex(String.join("|", parts));
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

package cn.cubegarden.icbm.caching.source.revision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;

/**
 * modpack/registry/datapack fingerprint（对应 HELLO 的 modpackFingerprint 与
 * registryAndDatapackFingerprint，CACHE_PROTOCOL.md §1）。
 *
 * <p>阶段1 覆盖：Minecraft 版本 + 已装模组（id@version）+ 已选数据包。注册表与生成设置指纹
 * 在阶段2 控制服务接入时补充（VERSION_FREEZE.md 注意事项）。
 */
public final class ModpackFingerprint {

    private ModpackFingerprint() {
    }

    /** 计算当前服务器指纹（确定性：所有输入排序后拼接再 SHA-256）。 */
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

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }
}

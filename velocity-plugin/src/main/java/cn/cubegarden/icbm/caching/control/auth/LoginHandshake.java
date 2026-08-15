package cn.cubegarden.icbm.caching.control.auth;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.authlib.services.ProfileResult;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录握手服务（CACHE_PROTOCOL §9）：RSA 密钥对在 master，slave 只透传握手。
 *
 * <p>借用 Mojang 官方实现（不自造验证组件）：authlib {@code MinecraftServicesDiscoveryService
 * .create(proxy).createMinecraftSessionService()} → {@code SessionService.hasJoinedServer}，
 * 与 26.3 官方入口同源（Main.java:127 Services.create 对照）；RSA 解密/摘要仅用 JCA 复刻
 * 官方 Crypt（Crypt.java:68 generateKeyPair / :170 decryptByteToSecretKey / :78 digestData）。
 *
 * <p>密钥对：master 启动时生成一次（RSA 1024，官方同规格），全体登录共用；每登录仅
 * challenge 随机（官方 ServerLoginPacketListenerImpl.java:67）。
 */
public final class LoginHandshake {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginHandshake.class);

    private final boolean verifyMojang;
    private final boolean hasJoinedIp;
    private final KeyPair keyPair;
    private final SessionService sessionService;
    private final SecureRandom random = new SecureRandom();

    public LoginHandshake(boolean verifyMojang, boolean hasJoinedIp) {
        this.verifyMojang = verifyMojang;
        this.hasJoinedIp = hasJoinedIp;
        this.keyPair = generateKeyPair();
        this.sessionService = MinecraftServicesDiscoveryService.create(envProxy())
                .createMinecraftSessionService();
        LOGGER.info("[control] 登录握手就绪：RSA-1024 密钥对 + Mojang authlib SessionService"
                + "（verifyMojang={} hasJoinedIp={}）", verifyMojang, hasJoinedIp);
    }

    /** master 公钥 X.509 DER（slave 构造 ClientboundHelloPacket 用，26.3 官方同编码）。 */
    public byte[] publicKeyDer() {
        return this.keyPair.getPublic().getEncoded();
    }

    /** 每登录随机挑战（官方 ServerLoginPacketListenerImpl.java:67 challenge 语义）。 */
    public byte[] randomChallenge() {
        byte[] challenge = new byte[4];
        this.random.nextBytes(challenge);
        return challenge;
    }

    /** 解密客户端加密会话密钥（官方 Crypt.decryptByteToSecretKey 对照：RSA 解密 → AES）。 */
    public SecretKey decryptSecret(byte[] keybytes) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, this.keyPair.getPrivate());
        return new SecretKeySpec(cipher.doFinal(keybytes), "AES");
    }

    /** 挑战回显校验（官方 ServerboundKeyPacket.isChallengeValid 对照）。 */
    public boolean isChallengeValid(byte[] encryptedChallenge, byte[] challenge) {
        try {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, this.keyPair.getPrivate());
            return Arrays.equals(challenge, cipher.doFinal(encryptedChallenge));
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** serverId 摘要（官方 Crypt.digestData 对照：SHA-1 over serverId‖secret‖pubkey，十六进制）。 */
    public String computeDigest(SecretKey secretKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update("".getBytes(StandardCharsets.ISO_8859_1)); // 26.3 serverId = ""
            md.update(secretKey.getEncoded());
            md.update(this.keyPair.getPublic().getEncoded());
            return new BigInteger(md.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    /**
     * Mojang 正版验证（借 authlib 官方实现；offline 开关放行但仍记账，开发/测试惯例，
     * offline 以客户端声明身份记账）。
     *
     * @return 已验证 GameProfile（含 profileProperties 纹理签名）；null = 未验证
     * @throws AuthenticationUnavailableException 服务不可用（fail closed，官方 authservers_down）
     */
    public GameProfile verify(String name, String digest, String ip, UUID claimedUuid)
            throws AuthenticationUnavailableException {
        if (!this.verifyMojang) {
            LOGGER.info("[control] offline 模式放行（未调用 Mojang）: {}", name);
            return new GameProfile(claimedUuid, name);
        }
        InetAddress address = this.hasJoinedIp ? parseIp(ip) : null;
        ProfileResult result = this.sessionService.hasJoinedServer(name, digest, address);
        if (result == null) {
            return null;
        }
        LOGGER.info("[control] Mojang 验证通过: {} uuid={}", name, result.profile().id());
        return result.profile();
    }

    private static InetAddress parseIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(ip);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    /** 环境代理（AGENTS.md 联网遵循 HTTPS_PROXY/HTTP_PROXY；无则 NO_PROXY，镜像 Main.java:127）。 */
    private static Proxy envProxy() {
        String proxy = firstNonEmpty(
                System.getenv("HTTPS_PROXY"), System.getenv("HTTP_PROXY"),
                System.getenv("https_proxy"), System.getenv("http_proxy"));
        if (proxy != null && proxy.startsWith("http")) {
            try {
                URI uri = URI.create(proxy);
                if (uri.getHost() != null && !uri.getHost().isEmpty() && uri.getPort() > 0) {
                    return new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress(uri.getHost(), uri.getPort()));
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[control] HTTPS_PROXY 格式非法，使用直连: {}", e.getMessage());
            }
        }
        return Proxy.NO_PROXY;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** RSA 1024（官方 Crypt.generateKeyPair 对照）。 */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 不可用", e);
        }
    }
}

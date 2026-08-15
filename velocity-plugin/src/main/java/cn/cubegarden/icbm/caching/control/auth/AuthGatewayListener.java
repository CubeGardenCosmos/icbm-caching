package cn.cubegarden.icbm.caching.control.auth;

import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.routing.RoutingPolicy;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LOGIN_VERIFY 流（streamId=1）服务——Auth Gateway（HIERARCHY §2 / CACHE_PROTOCOL §9）。
 * master 按 velocity 模式执行整套原版登录流程（RSA 密钥对在 master，slave 只透传握手并
 * 拿回会话密钥）：
 *
 * <p>阶段 A（LOGIN_VERIFY_REQ → RES）：slave 递交登录开始（name/uuid/slaveId/connectionInfo），
 * master 依次 票据单次兑换 → 落点判定，通过后生成每登录随机 challenge，
 * RES(ok=true, challenge, publicKeyDer)——slave 据此向客户端发 ClientboundHelloPacket
 * （26.3 ServerLoginPacketListenerImpl.java:125-127 对照）。
 *
 * <p>阶段 B（LOGIN_KEY_REQ → RES）：slave 递交客户端加密响应（ServerboundKeyPacket 原样
 * keybytes/encryptedChallenge），master 用私钥解密会话密钥 → 校验挑战回显 → 计算 digest
 * （Crypt.digestData 对照）→ 调 Mojang authlib SessionService.hasJoinedServer（借用官方
 * 实现，不自造 HTTP/JSON）→ 以**验证通过的 UUID** 做全局会话记账（重复会话拒新，
 * 与官方 verifyLoginAndFinishConnectionSetup 同序）→ 签发身份断言并回 sharedSecret
 * （slave 以 AES/CFB8 建 Cipher 解密客户端报文，Crypt.getCipher 对照）+ 断言。
 *
 * <p>失败路径回滚记账（已登记的会话注销），保证记账不泄漏。
 */
public final class AuthGatewayListener implements EncapsulatedControlSession.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthGatewayListener.class);

    /** 登录限速（A10 审计修复）：单连接验证失败/服务不可用计数；超过阈值 → 该连接拒绝
     *  后续验证（防 Mojang hasJoined 被打爆；slave 连接是受信面，正常流量远低于阈值）。 */
    private static final int MAX_FAILURES_PER_CONNECTION = 16;

    /** 阶段 A 到阶段 B 的最大间隔（超过则 pending 失效，防握手挂起泄漏）。 */
    private static final long PENDING_TTL_MILLIS = 30_000;

    private final SessionRegistry registry;
    private final TicketVault ticketVault;
    private final LoginHandshake handshake;
    private final IdentitySigner signer;
    private final RoutingPolicy routing;
    private final String issuer;
    private final long assertionTtlMillis;
    /** 会话 id → 连续失败计数（上限阈值后拒绝；成功时清零）。 */
    private final Map<String, Integer> failuresBySession = new ConcurrentHashMap<>();
    /** 阶段 A 已发挑战、等待阶段 B 的登录（nonceHex → 状态；单次兑换后的握手状态）。 */
    private final Map<String, PendingLogin> pendingLogins = new ConcurrentHashMap<>();

    public AuthGatewayListener(SessionRegistry registry, TicketVault ticketVault,
                               LoginHandshake handshake, IdentitySigner signer,
                               RoutingPolicy routing, String issuer, long assertionTtlMillis) {
        this.registry = registry;
        this.ticketVault = ticketVault;
        this.handshake = handshake;
        this.signer = signer;
        this.routing = routing;
        this.issuer = issuer;
        this.assertionTtlMillis = assertionTtlMillis;
    }

    /** 阶段 A：登录开始 → 前置校验 → 发 challenge + master 公钥。 */
    @Override
    public void onLoginVerifyRequest(EncapsulatedControlSession session,
                                     MessageType type, byte[] body) {
        if (type != MessageType.LOGIN_VERIFY_REQ) {
            LOGGER.error("[control] 登录流类型异常（fail closed）: {}", type);
            session.fail("login stream type unexpected: " + type);
            return;
        }
        Messages.LoginVerifyReq req;
        try {
            req = decode(MessageType.LOGIN_VERIFY_REQ, body);
        } catch (IOException e) {
            LOGGER.error("[control] LOGIN_VERIFY_REQ 解码失败（fail closed，关闭会话）: {}", e.getMessage());
            session.fail("LOGIN_VERIFY 解码失败");
            return;
        }
        String uuidHex = HexFormat.of().formatHex(req.playerUuid());
        String nonceHex = HexFormat.of().formatHex(req.nonce());
        String sessionKey = session.uuidHex() + "|" + req.slaveId();

        // A10：单连接失败限速（fail closed 前先拒绝后续验证请求）
        if (this.failuresBySession.getOrDefault(sessionKey, 0) >= MAX_FAILURES_PER_CONNECTION) {
            LOGGER.warn("[control] 登录验证限速触发（fail closed）: slave={}", req.slaveId());
            this.rejectVerify(session, "RATE_LIMITED");
            return;
        }

        // 2. 票据单次兑换（重放拒绝）
        if (!this.ticketVault.redeem(nonceHex)) {
            LOGGER.warn("[control] 票据重放被拒: uuid={} slave={}", uuidHex, req.slaveId());
            this.countFailure(sessionKey);
            this.rejectVerify(session, "REPLAYED_NONCE");
            return;
        }

        // 3. 落点判定（白名单；serverName == slaveId）
        RoutingPolicy.RoutingDecision decision = this.routing.decide(
                req.slaveId(), RoutingPolicy.SessionMode.CACHE, System.currentTimeMillis());
        if (decision.mode() == RoutingPolicy.SessionMode.REJECT) {
            LOGGER.warn("[control] 落点拒绝（白名单）: serverName={} uuid={}", req.slaveId(), uuidHex);
            this.rejectVerify(session, "SERVER_NOT_ALLOWED");
            return;
        }

        // 4. 每登录随机挑战（官方 ServerLoginPacketListenerImpl.java:67 challenge 语义）
        byte[] challenge = this.handshake.randomChallenge();
        this.pendingLogins.put(nonceHex, new PendingLogin(
                req.playerName(), uuidHex, req.slaveId(),
                ipOf(req.connectionInfo()), challenge,
                System.currentTimeMillis() + PENDING_TTL_MILLIS));
        Messages.LoginVerifyRes res = new Messages.LoginVerifyRes(
                true, "", challenge, this.handshake.publicKeyDer());
        LOGGER.info("[control] 登录阶段A（挑战已发）: {} 声明uuid={} slave={}", req.playerName(), uuidHex, req.slaveId());
        this.sendVerifyRes(session, res);
    }

    /** 阶段 B：客户端加密响应 → 解密/校验 → Mojang authlib 验证 → 记账 → 断言 + 会话密钥。 */
    public void onLoginKeyRequest(EncapsulatedControlSession session, byte[] body) {
        Messages.LoginKeyReq req;
        try {
            req = decode(MessageType.LOGIN_KEY_REQ, body);
        } catch (IOException e) {
            LOGGER.error("[control] LOGIN_KEY_REQ 解码失败（fail closed，关闭会话）: {}", e.getMessage());
            session.fail("LOGIN_KEY 解码失败");
            return;
        }
        String claimedUuidHex = HexFormat.of().formatHex(req.playerUuid());
        String nonceHex = HexFormat.of().formatHex(req.nonce());
        String sessionKey = session.uuidHex() + "|" + claimedUuidHex;
        PendingLogin pending = this.pendingLogins.remove(nonceHex);
        if (pending == null) {
            LOGGER.warn("[control] 登录阶段B 无对应阶段A（过期/重放，fail closed）: uuid={}", claimedUuidHex);
            this.countFailure(sessionKey);
            this.rejectKey(session, "KEY_UNKNOWN");
            return;
        }
        try {
            // 解密会话密钥 + 校验挑战回显（26.3 handleKey/Crypt 对照）
            SecretKey secretKey = this.handshake.decryptSecret(req.keybytes());
            if (!this.handshake.isChallengeValid(req.encryptedChallenge(), pending.challenge())) {
                LOGGER.warn("[control] 挑战回显校验失败（fail closed）: {}", pending.name());
                this.countFailure(sessionKey);
                this.rejectKey(session, "CHALLENGE_MISMATCH");
                return;
            }
            // Mojang 正版验证（借 authlib 官方实现；fail closed）
            String digest = this.handshake.computeDigest(secretKey);
            GameProfile profile = this.handshake.verify(pending.name(), digest, pending.ip(),
                    uuidFromBytes(req.playerUuid()));
            if (profile == null) {
                LOGGER.warn("[control] 未验证用户名（hasJoined 未命中）: {}", pending.name());
                this.countFailure(sessionKey);
                this.rejectKey(session, "UNVERIFIED_USERNAME");
                return;
            }
            // 以验证通过的 UUID 记账（官方 verifyLoginAndFinishConnectionSetup 同序；拒新）
            String verifiedUuidHex = HexFormat.of().formatHex(uuidBytes(profile.id()));
            String sessionId = this.registry.register(verifiedUuidHex, pending.slaveId());
            if (sessionId == null) {
                LOGGER.warn("[control] 重复会话被拒: {} uuid={} slave={}",
                        profile.name(), verifiedUuidHex, pending.slaveId());
                this.countFailure(sessionKey);
                this.rejectKey(session, "DUPLICATE_SESSION");
                return;
            }
            long expiresAt = System.currentTimeMillis() + this.assertionTtlMillis;
            this.ticketVault.activate(sessionId, expiresAt);
            this.failuresBySession.remove(sessionKey);
            // 断言签发（HMAC 签名 + profileProperties 纹理随断言传 slave 供皮肤）
            Messages.ProfileProperty[] properties = toProperties(profile.properties());
            Messages.MasterVerifiedIdentity identity = new Messages.MasterVerifiedIdentity(
                    this.issuer, this.signer.keyId(), pending.slaveId(),
                    uuidBytes(profile.id()), sessionId, hexToBytes(nonceHex),
                    expiresAt, new byte[0], properties);
            identity = this.signer.sign(identity);
            Messages.LoginKeyRes res = new Messages.LoginKeyRes(
                    true, "", secretKey.getEncoded(), identity);
            LOGGER.info("[control] 登录验证通过: {} uuid={} slave={} session={}", profile.name(),
                    verifiedUuidHex, pending.slaveId(), sessionId);
            this.sendKeyRes(session, res);
        } catch (AuthenticationUnavailableException e) {
            LOGGER.error("[control] Mojang 验证不可用（fail closed 拒绝）: {}", e.getMessage());
            this.countFailure(sessionKey);
            this.rejectKey(session, "AUTH_SERVERS_UNAVAILABLE");
        } catch (java.security.GeneralSecurityException e) {
            LOGGER.error("[control] 会话密钥解密失败（fail closed）: {}", e.getMessage());
            this.countFailure(sessionKey);
            this.rejectKey(session, "KEY_DECRYPT_FAILED");
        }
    }

    /** 连接断开清理（EncapsulatedControlServer 断开回调）：注销该 slave 的活跃会话与挂起握手。 */
    public int onSlaveDisconnected(String slaveId) {
        int count = this.registry.unregisterAllBySlave(slaveId);
        this.pendingLogins.entrySet().removeIf(entry -> entry.getValue().slaveId().equals(slaveId));
        this.failuresBySession.entrySet().removeIf(entry -> entry.getKey().endsWith("|" + slaveId));
        if (count > 0) {
            LOGGER.info("[control] slave 断开注销 {} 个活跃会话: {}", count, slaveId);
        }
        return count;
    }

    /** 周期清理过期断言与挂起握手（装配方定时调用；A9 审计修复）。 */
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        int expired = this.ticketVault.purgeExpired(now);
        this.pendingLogins.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        return expired;
    }

    private void countFailure(String sessionKey) {
        this.failuresBySession.merge(sessionKey, 1, Integer::sum);
    }

    private void rejectVerify(EncapsulatedControlSession session, String reason) {
        this.sendVerifyRes(session, Messages.LoginVerifyRes.reject(reason));
    }

    private void sendVerifyRes(EncapsulatedControlSession session, Messages.LoginVerifyRes res) {
        try {
            session.sendLoginVerify(MessageType.LOGIN_VERIFY_RES,
                    encode(MessageType.LOGIN_VERIFY_RES, res));
        } catch (IOException e) {
            LOGGER.error("[control] LOGIN_VERIFY_RES 发送失败: {}", e.getMessage());
            session.fail("LOGIN_VERIFY 发送失败");
        }
    }

    private void rejectKey(EncapsulatedControlSession session, String reason) {
        this.sendKeyRes(session, Messages.LoginKeyRes.reject(reason));
    }

    private void sendKeyRes(EncapsulatedControlSession session, Messages.LoginKeyRes res) {
        try {
            session.sendLoginVerify(MessageType.LOGIN_KEY_RES,
                    encode(MessageType.LOGIN_KEY_RES, res));
        } catch (IOException e) {
            LOGGER.error("[control] LOGIN_KEY_RES 发送失败: {}", e.getMessage());
            session.fail("LOGIN_KEY 发送失败");
        }
    }

    /** 阶段 A 挂起状态（阶段 B 用；nonce 单次兑换后即从票据簿移除，此处为握手状态）。 */
    private record PendingLogin(String name, String claimedUuidHex, String slaveId,
                                String ip, byte[] challenge, long expiresAt) {
    }

    private static Messages.ProfileProperty[] toProperties(
            com.mojang.authlib.properties.PropertyMap properties) {
        return properties.values().stream()
                .map(p -> new Messages.ProfileProperty(p.name(), p.value(), p.signature()))
                .toArray(Messages.ProfileProperty[]::new);
    }

    private static String ipOf(String connectionInfo) {
        // connectionInfo 格式 "ip:port"（slave 侧 remote 地址）
        if (connectionInfo == null) {
            return null;
        }
        int colon = connectionInfo.lastIndexOf(':');
        return colon > 0 ? connectionInfo.substring(0, colon) : connectionInfo;
    }

    private static UUID uuidFromBytes(byte[] bytes) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFFL);
            lsb = (lsb << 8) | (bytes[8 + i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    private static byte[] uuidBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return bytes;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static <T> T decode(MessageType type, byte[] body) throws IOException {
        return (T) MessageBodyCodec.decode(type, new DataInputStream(new ByteArrayInputStream(body)));
    }

    private static byte[] encode(MessageType type, Object message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageBodyCodec.encode(type, new DataOutputStream(bytes), message);
        return bytes.toByteArray();
    }
}

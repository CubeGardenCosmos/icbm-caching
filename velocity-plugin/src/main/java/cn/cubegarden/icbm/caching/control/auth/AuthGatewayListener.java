package cn.cubegarden.icbm.caching.control.auth;

import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.routing.RoutingPolicy;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LOGIN_VERIFY 流（streamId=1）服务——Auth Gateway（里程碑 5 完整化；HIERARCHY §2 /
 * CACHE_PROTOCOL §9）。slave 递交登录验证材料 → master 唯一验证 + 记账 + 落点判定 +
 * 断言签发：
 *
 * <ol>
 *   <li>解码 LOGIN_VERIFY_REQ（失败 → fail closed 关闭会话）；</li>
 *   <li>单次兑换：nonce 已用（重放）→ REJECTED（票据不重放）；</li>
 *   <li>全局会话记账：同一玩家 UUID 已活跃 → REJECTED（DUPLICATE_SESSION，拒新）；</li>
 *   <li>落点判定：serverName（=slaveId）白名单 → REJECTED（SERVER_NOT_ALLOWED）；</li>
 *   <li>Mojang 正版验证（MojangSessionVerifier；offline 开关放行但仍记账）；失败/不可用
 *       → REJECTED（fail closed）；</li>
 *   <li>签发 MasterVerifiedIdentity（HMAC 签名 + profileProperties 随断言传 slave），
 *       RES(verified=true)。</li>
 * </ol>
 *
 * <p>失败路径回滚记账（已登记的会话注销），保证记账不泄漏。
 */
public final class AuthGatewayListener implements EncapsulatedControlSession.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthGatewayListener.class);

    /** 登录限速（A10 审计修复）：单连接验证失败/服务不可用计数；超过阈值 → 该连接拒绝
     *  后续验证（防 Mojang hasJoined 被打爆；slave 连接是受信面，正常流量远低于阈值）。 */
    private static final int MAX_FAILURES_PER_CONNECTION = 16;

    private final SessionRegistry registry;
    private final TicketVault ticketVault;
    private final MojangSessionVerifier verifier;
    private final IdentitySigner signer;
    private final RoutingPolicy routing;
    private final String issuer;
    private final long assertionTtlMillis;
    /** 会话 id → 连续失败计数（上限阈值后拒绝；成功时清零）。 */
    private final java.util.Map<String, Integer> failuresBySession =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AuthGatewayListener(SessionRegistry registry, TicketVault ticketVault,
                               MojangSessionVerifier verifier, IdentitySigner signer,
                               RoutingPolicy routing, String issuer, long assertionTtlMillis) {
        this.registry = registry;
        this.ticketVault = ticketVault;
        this.verifier = verifier;
        this.signer = signer;
        this.routing = routing;
        this.issuer = issuer;
        this.assertionTtlMillis = assertionTtlMillis;
    }

    @Override
    public void onLoginVerifyRequest(EncapsulatedControlSession session, byte[] body) {
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
            this.reject(session, false, "RATE_LIMITED");
            return;
        }

        // 2. 单次兑换（票据不重放）
        if (!this.ticketVault.redeem(nonceHex)) {
            LOGGER.warn("[control] 票据重放被拒: uuid={} slave={}", uuidHex, req.slaveId());
            this.countFailure(sessionKey);
            this.reject(session, false, "REPLAYED_NONCE");
            return;
        }

        // 3. 全局会话记账（拒新；sessionId 由 SessionRegistry 唯一分配）
        String sessionId = this.registry.register(uuidHex, req.slaveId());
        if (sessionId == null) {
            LOGGER.warn("[control] 重复会话被拒: uuid={} slave={}", uuidHex, req.slaveId());
            this.reject(session, false, "DUPLICATE_SESSION");
            return;
        }
        long expiresAt = System.currentTimeMillis() + this.assertionTtlMillis;
        this.ticketVault.activate(sessionId, expiresAt);

        // 4. 落点判定（白名单；serverName == slaveId）
        RoutingPolicy.RoutingDecision decision = this.routing.decide(
                req.slaveId(), RoutingPolicy.SessionMode.CACHE, System.currentTimeMillis());
        if (decision.mode() == RoutingPolicy.SessionMode.REJECT) {
            LOGGER.warn("[control] 落点拒绝（白名单）: serverName={} uuid={}", req.slaveId(), uuidHex);
            this.rollback(uuidHex, sessionId);
            this.reject(session, false, "SERVER_NOT_ALLOWED");
            return;
        }

        // 5. Mojang 正版验证（fail closed）
        MojangSessionVerifier.Profile profile;
        try {
            profile = this.verifier.verify(req.playerName(), req.serverHash(), ipOf(req));
        } catch (MojangSessionVerifier.AuthenticationUnavailable e) {
            LOGGER.error("[control] Mojang 验证不可用（fail closed 拒绝）: {}", e.getMessage());
            this.rollback(uuidHex, sessionId);
            this.countFailure(sessionKey);
            this.reject(session, false, "AUTH_SERVERS_UNAVAILABLE");
            return;
        }
        if (profile == null) {
            LOGGER.warn("[control] 未验证用户名（hasJoined 204）: {}", req.playerName());
            this.rollback(uuidHex, sessionId);
            this.countFailure(sessionKey);
            this.reject(session, false, "UNVERIFIED_USERNAME");
            return;
        }
        // 验证成功：清零限速计数
        this.failuresBySession.remove(sessionKey);

        // 6. 断言签发（HMAC 签名 + profileProperties 随断言）
        Messages.MasterVerifiedIdentity identity = new Messages.MasterVerifiedIdentity(
                this.issuer, this.signer.keyId(), req.slaveId(), req.playerUuid(),
                sessionId, req.nonce(), System.currentTimeMillis() + this.assertionTtlMillis,
                new byte[0], profile.properties());
        identity = this.signer.sign(identity);
        Messages.LoginVerifyRes res = new Messages.LoginVerifyRes(true, "", identity);
        LOGGER.info("[control] 登录验证通过: {} uuid={} slave={} session={}", profile.name(), uuidHex,
                req.slaveId(), sessionId);
        this.sendRes(session, res);
    }

    /** 连接断开清理（EncapsulatedControlServer 断开回调）：注销该 slave 的活跃会话。 */
    public int onSlaveDisconnected(String slaveId) {
        int count = this.registry.unregisterAllBySlave(slaveId);
        for (java.util.Map.Entry<String, Integer> entry : this.failuresBySession.entrySet()) {
            if (entry.getKey().endsWith("|" + slaveId)) {
                this.failuresBySession.remove(entry.getKey());
            }
        }
        if (count > 0) {
            LOGGER.info("[control] slave 断开注销 {} 个活跃会话: {}", count, slaveId);
        }
        return count;
    }

    /** 周期清理过期断言（装配方定时调用；A9 审计修复）。 */
    public int purgeExpired() {
        return this.ticketVault.purgeExpired(System.currentTimeMillis());
    }

    private void countFailure(String sessionKey) {
        this.failuresBySession.merge(sessionKey, 1, Integer::sum);
    }

    /** 失败路径回滚：注销记账 + 吊销票据（保证记账不泄漏）。 */
    private void rollback(String uuidHex, String sessionId) {
        this.registry.unregister(uuidHex, sessionId);
        this.ticketVault.revoke(sessionId);
    }

    private static String ipOf(Messages.LoginVerifyReq req) {
        // connectionInfo 格式 "ip:port"（slave 侧 remote 地址）
        String info = req.connectionInfo();
        int colon = info.lastIndexOf(':');
        return colon > 0 ? info.substring(0, colon) : info;
    }

    private void reject(EncapsulatedControlSession session, boolean verified, String reason) {
        this.sendRes(session, new Messages.LoginVerifyRes(verified, reason, null));
    }

    private void sendRes(EncapsulatedControlSession session, Messages.LoginVerifyRes res) {
        try {
            session.sendLoginVerify(encode(MessageType.LOGIN_VERIFY_RES, res));
        } catch (IOException e) {
            LOGGER.error("[control] LOGIN_VERIFY_RES 发送失败: {}", e.getMessage());
            session.fail("LOGIN_VERIFY 发送失败");
        }
    }

    private static Messages.LoginVerifyReq decode(MessageType type, byte[] body) throws IOException {
        return MessageBodyCodec.decode(type, new DataInputStream(new ByteArrayInputStream(body)));
    }

    private static byte[] encode(MessageType type, Object message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageBodyCodec.encode(type, new DataOutputStream(bytes), message);
        return bytes.toByteArray();
    }
}

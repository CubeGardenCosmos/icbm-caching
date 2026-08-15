package cn.cubegarden.icbm.caching.control.auth;

import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
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
 * LOGIN_VERIFY 流（streamId=1）服务：slave 递交登录验证材料（LOGIN_VERIFY_REQ）→
 * master 记账并回 LOGIN_VERIFY_RES（offline 放行，阶段5 接 Mojang 正版验证与签名）。
 *
 * <p>fail closed：解码失败 → 关闭会话（不猜测兼容格式）；重复会话 → RES(verified=false,
 * DUPLICATE_SESSION)。验证请求走封装通道受信流（HIERARCHY §2），不经玩家流量。
 */
public final class AuthGatewayListener implements EncapsulatedControlSession.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthGatewayListener.class);

    private final SessionRegistry registry;

    public AuthGatewayListener(SessionRegistry registry) {
        this.registry = registry;
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
        String sessionId = this.registry.register(uuidHex);
        Messages.LoginVerifyRes res;
        if (sessionId == null) {
            LOGGER.warn("[control] 重复会话被拒: uuid={} slave={}", uuidHex, req.slaveId());
            res = new Messages.LoginVerifyRes(false, "DUPLICATE_SESSION", null);
        } else {
            // 里程碑 4 offline 放行仍记账；签名/单次兑换/吊销随里程碑 5 Auth Gateway
            res = new Messages.LoginVerifyRes(true, "", new Messages.MasterVerifiedIdentity(
                    "master", "key-1", req.slaveId(), req.playerUuid(), sessionId, req.nonce(),
                    System.currentTimeMillis() + 60_000, new byte[0]));
            LOGGER.info("[control] 登录验证放行: {} slave={} session={}", uuidHex, req.slaveId(), sessionId);
        }
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

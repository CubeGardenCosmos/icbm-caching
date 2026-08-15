package cn.cubegarden.icbm.caching.slave.network;

import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * 登录握手透传（HIERARCHY §2 / CACHE_PROTOCOL §9）：RSA 密钥对在 master，slave 只透传
 * 握手——阶段 A 递交 LOGIN_VERIFY_REQ 拿回挑战 + master 公钥（构造 ClientboundHelloPacket
 * 发客户端）；阶段 B 递交客户端加密响应（LOGIN_KEY_REQ）拿回会话密钥 sharedSecret + 身份
 * 断言（slave 以 sharedSecret 建 Cipher 解密客户端报文并继续原版流程）。
 *
 * <p>profileProperties 由 master 的 Mojang hasJoinedServer 结果填充（客户端 hello 不携带
 * properties，26.3 ServerboundHelloPacket(name, profileId)）；slave 不直接调用 Mojang。
 */
public final class LoginVerifyGate {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SlaveControlClient client;
    private final String slaveId;
    private final java.util.Map<String, String> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    public LoginVerifyGate(SlaveControlClient client, String slaveId) {
        this.client = client;
        this.slaveId = slaveId;
    }

    /**
     * 阶段 A：递交登录开始，等 master 挑战（null → 拒登录）。
     *
     * @param profileId 客户端声明 UUID（ServerboundHelloPacket.profileId）
     */
    public Messages.LoginVerifyRes requestChallenge(String name, UUID profileId,
                                                    InetSocketAddress remote) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        Messages.LoginVerifyReq req = new Messages.LoginVerifyReq(
                name, uuidToBytes(profileId), this.slaveId,
                remote == null ? "unknown" : remote.getAddress().getHostAddress() + ":" + remote.getPort(),
                nonce, System.currentTimeMillis());
        Messages.LoginVerifyRes res = this.client.requestChallenge(req);
        if (res == null) {
            return null;
        }
        if (!res.ok()) {
            return res;
        }
        // 保存 nonce 供阶段 B 关联（同一次登录的挑战请求与密钥递交）
        this.pendingNonces.put(uuidHex(profileId), nonce);
        return res;
    }

    private final java.util.Map<String, byte[]> pendingNonces =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 阶段 B：递交客户端加密响应（ServerboundKeyPacket 原样字段），等 master 会话密钥 +
     * 断言（null → 拒登录）。
     */
    public Messages.LoginKeyRes submitKey(UUID profileId, byte[] keybytes, byte[] encryptedChallenge) {
        byte[] nonce = this.pendingNonces.remove(uuidHex(profileId));
        if (nonce == null) {
            return null;
        }
        return this.client.submitKey(new Messages.LoginKeyReq(
                uuidToBytes(profileId), nonce, keybytes, encryptedChallenge));
    }

    /**
     * 玩家登出：上报 SESSION_CLOSED（master 注销记账，防会话表泄漏）。
     */
    public void onPlayerDisconnect(String name, UUID profileId, String reason) {
        String sessionId = this.sessions.remove(profileId.toString());
        if (sessionId == null) {
            return;
        }
        this.client.sendSessionClosed(uuidToBytes(profileId), sessionId, reason);
    }

    /** 验证成功时记录 master 签发的 sessionId（登出上报用）。 */
    public void recordSession(UUID profileId, String sessionId) {
        if (sessionId != null) {
            this.sessions.put(profileId.toString(), sessionId);
        }
    }

    private static String uuidHex(UUID uuid) {
        return java.util.HexFormat.of().formatHex(uuidToBytes(uuid));
    }

    private static byte[] uuidToBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (56 - i * 8));
            bytes[8 + i] = (byte) (lsb >>> (56 - i * 8));
        }
        return bytes;
    }
}

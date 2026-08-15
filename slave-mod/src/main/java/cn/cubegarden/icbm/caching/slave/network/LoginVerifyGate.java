package cn.cubegarden.icbm.caching.slave.network;

import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * 登录验证材料递交（HIERARCHY §2 / CACHE_PROTOCOL §9）：slave 终止 26.3 登录握手
 * （RSA 在 slave），把验证材料（serverHash/sharedSecret/客户端声明身份/连接信息/nonce）
 * 经 LOGIN_VERIFY 流交给 master（唯一验证者），master 返回前不放行。
 *
 * <p>profileProperties 由 master 的 hasJoinedServer 结果填充（客户端 hello 不携带 properties，
 * 26.3 ServerboundHelloPacket(name, profileId)）；slave 不直接调用 Mojang session server。
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
     * 验证玩家（在登录验证线程调用，阻塞等 master RES；null/未验证 → 拒登录）。
     *
     * @param profileId    客户端声明 UUID（ServerboundHelloPacket.profileId）
     * @param serverHash   sha1-hex(serverId ‖ publicKey ‖ sharedSecret)（26.3 Crypt.digestData）
     * @param sharedSecret 客户端加密的会话密钥明文（16 字节）
     */
    public Messages.LoginVerifyRes verify(String name, UUID profileId, String serverHash,
                                          byte[] sharedSecret, InetSocketAddress remote) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        Messages.LoginVerifyReq req = new Messages.LoginVerifyReq(
                name, uuidToBytes(profileId), new Messages.ProfileProperty[0],
                serverHash, sharedSecret, this.slaveId,
                remote == null ? "unknown" : remote.getAddress().getHostAddress() + ":" + remote.getPort(),
                nonce, System.currentTimeMillis());
        return this.client.verifyLogin(req);
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

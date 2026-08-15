package cn.cubegarden.icbm.caching.protocol.encapsulation;

import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AUTH 证明 token（草稿 encapsulated-channel §3.1）：
 * {@code authToken = HMAC-SHA256(PSK, "ICBM-AUTH" ‖ uuid ‖ clientNonce)} 的前 16 字节。
 *
 * <p>PSK 内网信任边界：知道公共密码者可计算相同 token，故 AUTH 只是通道准入证明，
 * 真正的会话安全由后续 HKDF 派生密钥 + ChaCha20-Poly1305 承担（防监听/篡改/重放）。
 */
public final class AuthToken {

    private AuthToken() {
    }

    public static byte[] compute(byte[] psk, byte[] uuid, byte[] clientNonce) {
        if (psk == null || psk.length == 0) {
            throw new IllegalArgumentException("psk 不能为空（运维手册禁止弱公共密码）");
        }
        if (uuid == null || uuid.length != ProtocolConstants.UUID_BYTES) {
            throw new IllegalArgumentException("uuid 必须为 16 字节");
        }
        if (clientNonce == null || clientNonce.length != ProtocolConstants.UUID_BYTES) {
            throw new IllegalArgumentException("clientNonce 必须为 16 字节");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(psk, "HmacSHA256"));
            mac.update(ProtocolConstants.AUTH_MESSAGE_PREFIX.getBytes(StandardCharsets.UTF_8));
            mac.update(uuid);
            mac.update(clientNonce);
            byte[] digest = mac.doFinal();
            byte[] token = new byte[ProtocolConstants.AUTH_TOKEN_BYTES];
            System.arraycopy(digest, 0, token, 0, token.length);
            return token;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** 常量时间比较，防时序侧信道。 */
    public static boolean verify(byte[] psk, byte[] uuid, byte[] clientNonce, byte[] presented) {
        if (presented == null || presented.length != ProtocolConstants.AUTH_TOKEN_BYTES) {
            return false;
        }
        return MessageDigest.isEqual(compute(psk, uuid, clientNonce), presented);
    }
}

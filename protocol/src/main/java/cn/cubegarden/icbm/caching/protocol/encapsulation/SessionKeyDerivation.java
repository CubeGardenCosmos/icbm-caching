package cn.cubegarden.icbm.caching.protocol.encapsulation;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256（RFC 5869；草稿 encapsulated-channel §3.1 会话密钥派生）。
 *
 * <p>用法：{@code sessionKey = hkdfSha256(psk, salt=clientNonce‖serverNonce‖uuid,
 * info="icbm-session", 32)}。PSK 为 IKM，salt 绑定连接双方 nonce 与 UUID，
 * 防重放与弱化 slave 间互读（HIERARCHY/EXECUTION_PLAN 风险注意）。
 *
 * <p>RFC 5869 测试向量（用例 1）由 {@code SessionKeyDerivationTest} 锁定。
 */
public final class SessionKeyDerivation {

    private static final int HASH_BYTES = 32;

    private SessionKeyDerivation() {
    }

    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, String info, int length) {
        return hkdfSha256(ikm, salt, info.getBytes(StandardCharsets.UTF_8), length);
    }

    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (ikm == null || ikm.length == 0) {
            throw new IllegalArgumentException("ikm(PSK) 不能为空");
        }
        if (salt == null || salt.length == 0) {
            throw new IllegalArgumentException("salt 不能为空（必须绑定 nonce+UUID）");
        }
        if (length <= 0 || length > 255 * HASH_BYTES) {
            throw new IllegalArgumentException("length 越界: " + length);
        }
        try {
            byte[] prk = hmacSha256(salt, ikm);                       // extract
            byte[] okm = new byte[length];
            byte[] t = new byte[0];
            int offset = 0;
            int counter = 1;
            while (offset < length) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(t);
                mac.update(info);
                mac.update((byte) counter);
                t = mac.doFinal();
                int copy = Math.min(t.length, length - offset);
                System.arraycopy(t, 0, okm, offset, copy);
                offset += copy;
                counter++;
            }
            return okm;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 不可用", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}

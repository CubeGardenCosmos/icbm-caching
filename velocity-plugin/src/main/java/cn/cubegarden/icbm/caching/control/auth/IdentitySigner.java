package cn.cubegarden.icbm.caching.control.auth;

import cn.cubegarden.icbm.caching.protocol.Messages;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 身份断言签名/验证（HIERARCHY §2：master 签发 MasterVerifiedIdentity，slave 验证
 * issuer/keyId/签名/受众/有效期）。
 *
 * <p>签名 = HMAC-SHA256(assertionKey, canonical) 前 16 字节，canonical 为断言全部
 * 明文字段的定长编码（含 profileProperties 的顺序签名，防字段重排）。对称密钥设计：
 * 断言只经封装通道（ChaCha20-Poly1305 会话密钥）传输，通道受信面内 HMAC 足以防跨会话
 * 重放与完整性；slave 间伪造由通道隔离阻断。keyId 支持轮换（配置换 key 时旧断言仍可
 * 验证至过期）。非对称签名（公钥验证）随密钥分发流（阶段 6/7）。
 */
public final class IdentitySigner {

    private static final int KEY_ID_LENGTH = 8;

    private final String keyId;
    private final byte[] key;

    public IdentitySigner(String keyId, byte[] key) {
        if (key == null || key.length < 16) {
            throw new IllegalArgumentException("断言签名密钥至少 16 字节");
        }
        this.keyId = keyId;
        this.key = key.clone();
    }

    /** 便捷构造：keyId = SHA-256(key) 前 8 字节 hex。 */
    public static IdentitySigner fromKey(byte[] key) {
        String keyId;
        try {
            keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key), 0, KEY_ID_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new IdentitySigner(keyId, key);
    }

    public String keyId() {
        return this.keyId;
    }

    /** 为断言签名（keyId 固定为本 signer 的 keyId；返回带 signature 的新断言）。 */
    public Messages.MasterVerifiedIdentity sign(Messages.MasterVerifiedIdentity identity) {
        byte[] signature = hmac(canonical(identity.issuer(), this.keyId, identity.audience(),
                identity.playerUuid(), identity.sessionId(), identity.nonce(),
                identity.expiresAt(), identity.profileProperties()));
        return new Messages.MasterVerifiedIdentity(
                identity.issuer(), this.keyId, identity.audience(), identity.playerUuid(),
                identity.sessionId(), identity.nonce(), identity.expiresAt(), signature,
                identity.profileProperties());
    }

    /** 验证断言：签名、keyId（允许历史 keyId）、有效期。失败返回原因；成功 null。 */
    public String verify(Messages.MasterVerifiedIdentity identity) {
        if (!this.keyId.equals(identity.keyId())) {
            return "断言 keyId 不匹配: " + identity.keyId() + "（期望 " + this.keyId + "）";
        }
        byte[] expected = hmac(canonical(identity.issuer(), identity.keyId(), identity.audience(),
                identity.playerUuid(), identity.sessionId(), identity.nonce(),
                identity.expiresAt(), identity.profileProperties()));
        if (!MessageDigest.isEqual(expected, identity.signature())) {
            return "断言签名无效";
        }
        if (identity.expiresAt() < System.currentTimeMillis()) {
            return "断言已过期";
        }
        return null;
    }

    /** 断言 canonical 编码（签名输入；字段顺序 = 线上 codec 顺序 + profileProperties）。 */
    private static byte[] canonical(String issuer, String keyId, String audience, byte[] playerUuid,
                                    String sessionId, byte[] nonce, long expiresAt,
                                    Messages.ProfileProperty[] properties) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            writeString(out, issuer);
            writeString(out, keyId);
            writeString(out, audience);
            out.write(playerUuid);
            writeString(out, sessionId);
            out.write(nonce);
            out.write(longBytes(expiresAt));
            if (properties != null) {
                out.write(shortBytes(properties.length));
                for (Messages.ProfileProperty property : properties) {
                    writeString(out, property.name());
                    writeString(out, property.value());
                    writeString(out, property.signature());
                }
            } else {
                out.write(shortBytes(0));
            }
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toByteArray();
    }

    private static void writeString(java.io.ByteArrayOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(shortBytes(bytes.length));
        out.write(bytes);
    }

    private static byte[] shortBytes(int value) {
        return new byte[] {(byte) (value >>> 8), (byte) value};
    }

    private static byte[] longBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (value >>> (56 - i * 8));
        }
        return bytes;
    }

    private byte[] hmac(byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(this.key, "HmacSHA256"));
            return java.util.Arrays.copyOf(mac.doFinal(data), 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

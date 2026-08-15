package cn.cubegarden.icbm.caching.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.Messages;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 身份断言签名/验证：HMAC 签名 round-trip、keyId 派生、篡改/过期/字段重排拒绝。
 */
class IdentitySignerTest {

    private static final byte[] KEY = "test-assertion-key-0123456789".getBytes(StandardCharsets.UTF_8);

    private Messages.MasterVerifiedIdentity identity() {
        return new Messages.MasterVerifiedIdentity(
                "master", "key", "lobby-1", new byte[16], "sess-1", new byte[16],
                System.currentTimeMillis() + 60_000, new byte[0],
                new Messages.ProfileProperty[] {new Messages.ProfileProperty("textures", "v", "s")});
    }

    @Test
    void signVerifyRoundTrip() {
        IdentitySigner signer = IdentitySigner.fromKey(KEY);
        assertNotNull(signer.keyId());
        assertFalse(signer.keyId().isEmpty());

        Messages.MasterVerifiedIdentity signed = signer.sign(identity());
        assertTrue(signed.signature().length > 0);
        assertNull(signer.verify(signed), "签名必须验证通过");
    }

    @Test
    void tamperedSignatureRejected() {
        IdentitySigner signer = IdentitySigner.fromKey(KEY);
        Messages.MasterVerifiedIdentity signed = signer.sign(identity());
        Messages.MasterVerifiedIdentity tampered = new Messages.MasterVerifiedIdentity(
                signed.issuer(), signed.keyId(), signed.audience(), signed.playerUuid(),
                signed.sessionId(), signed.nonce(), signed.expiresAt(),
                new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, signed.profileProperties());
        assertNotNull(signer.verify(tampered), "篡改签名必须拒绝");
    }

    @Test
    void expiredRejected() {
        IdentitySigner signer = IdentitySigner.fromKey(KEY);
        Messages.MasterVerifiedIdentity expired = new Messages.MasterVerifiedIdentity(
                "master", signer.keyId(), "lobby-1", new byte[16], "sess-1", new byte[16],
                System.currentTimeMillis() - 1_000, new byte[0], new Messages.ProfileProperty[0]);
        assertNotNull(signer.verify(signer.sign(expired)), "过期断言必须拒绝");
    }

    @Test
    void fieldReorderingRejected() {
        IdentitySigner signer = IdentitySigner.fromKey(KEY);
        Messages.MasterVerifiedIdentity signed = signer.sign(identity());
        // 换 keyId 字段（canonical 编码变化 → 签名不匹配）
        Messages.MasterVerifiedIdentity swapped = new Messages.MasterVerifiedIdentity(
                signed.issuer(), "other-key", signed.audience(), signed.playerUuid(),
                signed.sessionId(), signed.nonce(), signed.expiresAt(), signed.signature(),
                signed.profileProperties());
        assertNotNull(signer.verify(swapped), "字段被改必须拒绝");
    }

    @Test
    void wrongKeyRejected() {
        IdentitySigner signerA = IdentitySigner.fromKey(KEY);
        IdentitySigner signerB = IdentitySigner.fromKey("another-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        Messages.MasterVerifiedIdentity signed = signerA.sign(identity());
        assertNotNull(signerB.verify(signed), "不同密钥验证必须拒绝");
    }
}

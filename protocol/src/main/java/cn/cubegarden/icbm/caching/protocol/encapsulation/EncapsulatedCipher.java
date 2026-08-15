package cn.cubegarden.icbm.caching.protocol.encapsulation;

import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ChaCha20-Poly1305 封装帧加密器（JDK {@code Cipher}，无外部依赖；草稿
 * encapsulated-channel §3 取舍：加密位置仿官方 {@code Connection.setEncryptionKey}
 * ——帧层之外整体加密）。
 *
 * <p>会话密钥经 HKDF 派生为两个 32 字节方向密钥（client→server / server→client，
 * 草稿 §3.1），nonce = 8 字节大端帧计数器 + 4 字节方向常量。方向密钥不同 ⇒ 双向
 * nonce 永不碰撞；计数器从 0 单调递增，每帧唯一（AEAD nonce 复用即灾难）。
 *
 * <p>解密失败（篡改/tag 不匹配）抛 {@link IOException}，调用方必须断连（fail closed）。
 */
public final class EncapsulatedCipher {

    /** 派生出的双向会话密钥。 */
    public record SessionKeys(EncapsulatedCipher clientToServer, EncapsulatedCipher serverToClient) {

        public static SessionKeys derive(byte[] psk, byte[] clientNonce, byte[] serverNonce, byte[] uuid) {
            byte[] salt = new byte[clientNonce.length + serverNonce.length + uuid.length];
            System.arraycopy(clientNonce, 0, salt, 0, clientNonce.length);
            System.arraycopy(serverNonce, 0, salt, clientNonce.length, serverNonce.length);
            System.arraycopy(uuid, 0, salt, clientNonce.length + serverNonce.length, uuid.length);
            byte[] material = SessionKeyDerivation.hkdfSha256(
                    psk, salt, ProtocolConstants.HKDF_INFO_SESSION, ProtocolConstants.SESSION_KEY_BYTES * 2);
            byte[] c2sKey = new byte[ProtocolConstants.SESSION_KEY_BYTES];
            byte[] s2cKey = new byte[ProtocolConstants.SESSION_KEY_BYTES];
            System.arraycopy(material, 0, c2sKey, 0, c2sKey.length);
            System.arraycopy(material, c2sKey.length, s2cKey, 0, s2cKey.length);
            return new SessionKeys(
                    new EncapsulatedCipher(c2sKey, 0x00000001L),
                    new EncapsulatedCipher(s2cKey, 0x00000002L)
            );
        }
    }

    private final SecretKeySpec key;
    private final long directionTag;
    private final AtomicLong counter = new AtomicLong();

    private EncapsulatedCipher(byte[] keyBytes, long directionTag) {
        this.key = new SecretKeySpec(keyBytes, "ChaCha20");
        this.directionTag = directionTag;
    }

    /** 方向密钥字节（测试/审计用）。 */
    public byte[] keyBytes() {
        return this.key.getEncoded();
    }

    /** 加密一帧：返回密文（长度 = 明文 + 16 字节 AEAD tag）。 */
    public byte[] encrypt(byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.ENCRYPT_MODE, this.key, new IvParameterSpec(nextNonce()));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ChaCha20-Poly1305 加密失败", e);
        }
    }

    /** 解密一帧；篡改/tag 不匹配/格式错误 → IOException（fail closed）。 */
    public byte[] decrypt(byte[] ciphertext, byte[] aad) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.DECRYPT_MODE, this.key, new IvParameterSpec(nextNonce()));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IOException("封装帧解密失败（篡改或密钥不匹配）", e);
        }
    }

    private byte[] nextNonce() {
        long n = this.counter.getAndIncrement();
        byte[] nonce = new byte[ProtocolConstants.CHACHA_NONCE_BYTES];
        for (int i = 0; i < 8; i++) {
            nonce[7 - i] = (byte) (n >>> (8 * i));
        }
        nonce[8] = (byte) (this.directionTag >>> 24);
        nonce[9] = (byte) (this.directionTag >>> 16);
        nonce[10] = (byte) (this.directionTag >>> 8);
        nonce[11] = (byte) this.directionTag;
        return nonce;
    }
}

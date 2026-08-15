package cn.cubegarden.icbm.caching.protocol.encapsulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * HKDF-SHA256 测试：RFC 5869 测试用例 1 锁定实现正确性。
 */
class SessionKeyDerivationTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    void rfc5869TestCase1() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = new byte[13];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) i;
        }
        String info = "f0f1f2f3f4f5f6f7f8f9";
        byte[] okm = SessionKeyDerivation.hkdfSha256(ikm, salt, hex(info), 42);

        byte[] expected = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        assertArrayEquals(expected, okm, "RFC 5869 用例 1 OKM");
    }

    @Test
    void rejectsEmptyPskOrSalt() {
        byte[] salt = new byte[16];
        assertThrows(IllegalArgumentException.class,
                () -> SessionKeyDerivation.hkdfSha256(new byte[0], salt, "i", 32));
        assertThrows(IllegalArgumentException.class,
                () -> SessionKeyDerivation.hkdfSha256(new byte[16], new byte[0], "i", 32));
    }

    @Test
    void deterministicOutputForSameInput() {
        byte[] a = SessionKeyDerivation.hkdfSha256(
                "psk".getBytes(StandardCharsets.UTF_8), new byte[16], "icbm-session", 32);
        byte[] b = SessionKeyDerivation.hkdfSha256(
                "psk".getBytes(StandardCharsets.UTF_8), new byte[16], "icbm-session", 32);
        assertArrayEquals(a, b);
    }

    @Test
    void differentInfoChangesOutput() {
        byte[] a = SessionKeyDerivation.hkdfSha256(
                "psk".getBytes(StandardCharsets.UTF_8), new byte[16], "icbm-session", 32);
        byte[] b = SessionKeyDerivation.hkdfSha256(
                "psk".getBytes(StandardCharsets.UTF_8), new byte[16], "other", 32);
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void outputLengthMustBeInRange() {
        assertThrows(IllegalArgumentException.class, () -> SessionKeyDerivation.hkdfSha256(
                new byte[16], new byte[16], "i", 0));
        assertThrows(IllegalArgumentException.class, () -> SessionKeyDerivation.hkdfSha256(
                new byte[16], new byte[16], "i", 255 * 32 + 1));
    }

    @Test
    void derivedKeysAre32BytesAndSessionDerivesBothDirections() {
        byte[] psk = "test-psk-1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] clientNonce = bytes(0x11, 0x20);
        byte[] serverNonce = bytes(0x21, 0x30);
        byte[] uuid = bytes(0x01, 0x10);
        EncapsulatedCipher.SessionKeys keys = EncapsulatedCipher.SessionKeys.derive(psk, clientNonce, serverNonce, uuid);
        assertEquals(ProtocolConstants.SESSION_KEY_BYTES, keys.clientToServer().keyBytes().length);
        assertEquals(ProtocolConstants.SESSION_KEY_BYTES, keys.serverToClient().keyBytes().length);
    }

    private static byte[] bytes(int from, int to) {
        byte[] out = new byte[to - from + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (from + i);
        }
        return out;
    }
}

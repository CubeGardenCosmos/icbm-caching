package cn.cubegarden.icbm.caching.protocol.encapsulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 封装通道单元测试：AUTH/AUTH_ACK golden 布局、HKDF 派生、ChaCha20-Poly1305
 * 帧往返/篡改检测、帧头判别与长度上限（草稿 encapsulated-channel §3.1）。
 */
class EncapsulatedChannelTest {

    private static final byte[] PSK = "test-psk-1234567890".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UUID = bytes(0x01, 0x10);
    private static final byte[] CLIENT_NONCE = bytes(0x11, 0x20);
    private static final byte[] SERVER_NONCE = bytes(0x21, 0x30);

    private static byte[] bytes(int from, int to) {
        byte[] out = new byte[to - from + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (from + i);
        }
        return out;
    }

    // ---- AUTH token 与握手 golden ----

    @Test
    void authTokenGoldenBytes() {
        // python 预计算：HMAC-SHA256(psk, "ICBM-AUTH" ‖ uuid ‖ clientNonce) 前 16 字节
        byte[] token = AuthToken.compute(PSK, UUID, CLIENT_NONCE);
        assertArrayEquals(hex("92faad3e48a003ebd0d61a2c344943f7"), token);
        assertTrue(AuthToken.verify(PSK, UUID, CLIENT_NONCE, token));
        byte[] wrong = token.clone();
        wrong[0] ^= 1;
        assertFalse(AuthToken.verify(PSK, UUID, CLIENT_NONCE, wrong));
        assertFalse(AuthToken.verify(PSK, UUID, CLIENT_NONCE, new byte[8]));
    }

    @Test
    void authFrameGoldenLayout() throws IOException {
        byte[] token = AuthToken.compute(PSK, UUID, CLIENT_NONCE);
        EncapsulatedHandshake.Auth auth = new EncapsulatedHandshake.Auth(token, UUID, CLIENT_NONCE);
        byte[] frame = EncapsulatedHandshake.encodeAuth(auth);

        // [authTokenLen(2)=16][authToken(16)][uuid(16)][clientNonce(16)] = 50 字节
        assertEquals(50, frame.length);
        assertEquals(0, frame[0]);
        assertEquals(ProtocolConstants.AUTH_TOKEN_BYTES, frame[1]);
        assertArrayEquals(token, java.util.Arrays.copyOfRange(frame, 2, 2 + 16));
        assertArrayEquals(UUID, java.util.Arrays.copyOfRange(frame, 2 + 16, 2 + 32));
        assertArrayEquals(CLIENT_NONCE, java.util.Arrays.copyOfRange(frame, 2 + 32, 2 + 48));

        EncapsulatedHandshake.Auth back = EncapsulatedHandshake.decodeAuth(frame);
        assertArrayEquals(token, back.authToken());
        assertArrayEquals(UUID, back.uuid());
        assertArrayEquals(CLIENT_NONCE, back.clientNonce());
    }

    @Test
    void authAckRoundTripAndRejectLayout() throws IOException {
        EncapsulatedHandshake.AuthAck accept = new EncapsulatedHandshake.AuthAck(true, SERVER_NONCE, "");
        byte[] acceptFrame = EncapsulatedHandshake.encodeAuthAck(accept);
        // [accepted(1)=1][serverNonce(16)][reason len(2)=0]
        assertEquals(1 + 16 + 2, acceptFrame.length);
        assertEquals(1, acceptFrame[0]);
        EncapsulatedHandshake.AuthAck back = EncapsulatedHandshake.decodeAuthAck(acceptFrame);
        assertTrue(back.accepted());
        assertArrayEquals(SERVER_NONCE, back.serverNonce());

        EncapsulatedHandshake.AuthAck reject = new EncapsulatedHandshake.AuthAck(false, null, "bad psk");
        byte[] rejectFrame = EncapsulatedHandshake.encodeAuthAck(reject);
        assertEquals(0, rejectFrame[0]);
        EncapsulatedHandshake.AuthAck rejected = EncapsulatedHandshake.decodeAuthAck(rejectFrame);
        assertFalse(rejected.accepted());
        assertEquals("bad psk", rejected.reason());
    }

    // ---- HKDF 派生与密钥 ----

    @Test
    void sessionKeyDerivationBindsNoncesAndUuid() {
        EncapsulatedCipher.SessionKeys keys =
                EncapsulatedCipher.SessionKeys.derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID);
        EncapsulatedCipher.SessionKeys other = EncapsulatedCipher.SessionKeys.derive(
                PSK, bytes(0x31, 0x40), SERVER_NONCE, UUID);
        assertFalse(java.util.Arrays.equals(keys.clientToServer().keyBytes(),
                other.clientToServer().keyBytes()), "nonce 必须进入派生");
        EncapsulatedCipher.SessionKeys otherUuid = EncapsulatedCipher.SessionKeys.derive(
                PSK, CLIENT_NONCE, SERVER_NONCE, bytes(0x41, 0x50));
        assertFalse(java.util.Arrays.equals(keys.clientToServer().keyBytes(),
                otherUuid.clientToServer().keyBytes()), "UUID 必须进入派生");
        // 双向密钥不同
        assertFalse(java.util.Arrays.equals(keys.clientToServer().keyBytes(),
                keys.serverToClient().keyBytes()));
    }

    // ---- ChaCha20-Poly1305 帧 ----

    @Test
    void encryptedFrameRoundTrip() throws IOException {
        // 真实场景：加密/解密在连接两端各自持有独立计数器；派生两次相同输入得到相同密钥
        EncapsulatedCipher enc = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        EncapsulatedCipher dec = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        byte[] body = {1, 2, 3, 4, 5};
        byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(enc, StreamId.CONTROL, body);

        // 布局：[magic(4)][len(4)][ciphertext(1+5+16)]
        assertEquals(8 + 1 + body.length + ProtocolConstants.CHACHA_TAG_BYTES, frame.length);
        assertArrayEquals(ProtocolConstants.MAGIC, java.util.Arrays.copyOfRange(frame, 0, 4));
        int cipherLength = ((frame[4] & 0xFF) << 24) | ((frame[5] & 0xFF) << 16)
                | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        assertEquals(1 + body.length + ProtocolConstants.CHACHA_TAG_BYTES, cipherLength);

        EncapsulatedFrame.Decrypted decrypted =
                EncapsulatedFrame.decodeEncryptedFrame(dec, frame);
        assertEquals(StreamId.CONTROL, decrypted.stream());
        assertArrayEquals(body, decrypted.body());
    }

    @Test
    void eachFrameUsesFreshNonce() throws IOException {
        EncapsulatedCipher enc = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        EncapsulatedCipher dec = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        byte[] body = {9, 9, 9};
        byte[] f1 = EncapsulatedFrame.encodeEncryptedFrame(enc, StreamId.CONTROL, body);
        byte[] f2 = EncapsulatedFrame.encodeEncryptedFrame(enc, StreamId.CONTROL, body);
        assertFalse(java.util.Arrays.equals(f1, f2), "nonce 计数器必须每帧递增");
        // 两帧都能独立解密（接收端计数器同步推进）
        assertArrayEquals(body, EncapsulatedFrame.decodeEncryptedFrame(dec, f1).body());
        assertArrayEquals(body, EncapsulatedFrame.decodeEncryptedFrame(dec, f2).body());
    }

    @Test
    void tamperedFrameRejected() throws IOException {
        EncapsulatedCipher enc = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        EncapsulatedCipher dec = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(
                enc, StreamId.CONTROL, new byte[] {1, 2, 3});
        frame[frame.length - 1] ^= 1; // 篡改密文尾部
        assertThrows(IOException.class,
                () -> EncapsulatedFrame.decodeEncryptedFrame(dec, frame));
    }

    @Test
    void wrongKeyRejected() throws IOException {
        EncapsulatedCipher enc = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        EncapsulatedCipher wrong = EncapsulatedCipher.SessionKeys
                .derive("other-psk".getBytes(StandardCharsets.UTF_8), CLIENT_NONCE, SERVER_NONCE, UUID)
                .serverToClient();
        byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(
                enc, StreamId.CONTROL, new byte[] {1});
        assertThrows(IOException.class,
                () -> EncapsulatedFrame.decodeEncryptedFrame(wrong, frame));
    }

    @Test
    void wrongDirectionKeyRejected() throws IOException {
        EncapsulatedCipher enc = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        EncapsulatedCipher wrongDirection = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).serverToClient();
        byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(
                enc, StreamId.CONTROL, new byte[] {1});
        // 服务端→客户端密钥不能解客户端→服务端帧（方向密钥分离）
        assertThrows(IOException.class,
                () -> EncapsulatedFrame.decodeEncryptedFrame(wrongDirection, frame));
    }

    @Test
    void unknownStreamIdRejected() throws IOException {
        EncapsulatedCipher enc = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        EncapsulatedCipher dec = EncapsulatedCipher.SessionKeys
                .derive(PSK, CLIENT_NONCE, SERVER_NONCE, UUID).clientToServer();
        // 手工构造明文体首字节为未知流 0x7F（encodeEncryptedFrame 只接受合法枚举，需直接加密）
        byte[] plaintext = {(byte) 0x7F, 1, 2};
        int cipherLength = plaintext.length + ProtocolConstants.CHACHA_TAG_BYTES;
        byte[] aad = new byte[8];
        System.arraycopy(ProtocolConstants.MAGIC, 0, aad, 0, 4);
        aad[4] = (byte) (cipherLength >>> 24);
        aad[5] = (byte) (cipherLength >>> 16);
        aad[6] = (byte) (cipherLength >>> 8);
        aad[7] = (byte) cipherLength;
        byte[] ciphertext = enc.encrypt(plaintext, aad);
        byte[] frame = new byte[8 + ciphertext.length];
        System.arraycopy(ProtocolConstants.MAGIC, 0, frame, 0, 4);
        frame[4] = (byte) (cipherLength >>> 24);
        frame[5] = (byte) (cipherLength >>> 16);
        frame[6] = (byte) (cipherLength >>> 8);
        frame[7] = (byte) cipherLength;
        System.arraycopy(ciphertext, 0, frame, 8, ciphertext.length);
        assertThrows(IOException.class, () -> EncapsulatedFrame.decodeEncryptedFrame(dec, frame));
    }

    // ---- 帧头判别与长度上限 ----

    @Test
    void firstByteMagicDiscrimination() {
        assertTrue(EncapsulatedFrame.startsWithMagic(ProtocolConstants.MAGIC[0]));
        assertFalse(EncapsulatedFrame.startsWithMagic((byte) 0x00));  // 原版握手首字节（长度前缀）
        assertFalse(EncapsulatedFrame.startsWithMagic((byte) 'X'));
    }

    @Test
    void plaintextFrameGoldenLayout() throws IOException {
        byte[] payload = {10, 20, 30};
        byte[] frame = EncapsulatedFrame.encodePlaintextFrame(payload);
        assertArrayEquals(ProtocolConstants.MAGIC, java.util.Arrays.copyOfRange(frame, 0, 4));
        assertEquals(3, ((frame[4] & 0xFF) << 24) | ((frame[5] & 0xFF) << 16)
                | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF));
        assertArrayEquals(payload, EncapsulatedFrame.decodePlaintextPayload(frame));
    }

    @Test
    void badMagicAndOversizeRejected() {
        assertThrows(IOException.class, () -> EncapsulatedFrame.decodePlaintextPayload(new byte[4]));
        byte[] frame = EncapsulatedFrame.encodePlaintextFrame(new byte[] {1});
        frame[0] = 'X';
        assertThrows(IOException.class, () -> EncapsulatedFrame.decodePlaintextPayload(frame));

        byte[] oversized = new byte[ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES + 9];
        java.util.Arrays.fill(oversized, (byte) 1);
        System.arraycopy(ProtocolConstants.MAGIC, 0, oversized, 0, 4);
        oversized[4] = (byte) 0x7F;
        oversized[5] = (byte) 0xFF;
        oversized[6] = (byte) 0xFF;
        oversized[7] = (byte) 0xFF;
        assertThrows(IOException.class, () -> EncapsulatedFrame.decodePlaintextPayload(oversized));
    }

    @Test
    void lengthMismatchRejected() throws IOException {
        byte[] payload = {1, 2, 3};
        byte[] frame = EncapsulatedFrame.encodePlaintextFrame(payload);
        // 伪造长度字段 ≠ 实际载荷长
        frame[7] = 99;
        assertThrows(IOException.class, () -> EncapsulatedFrame.decodePlaintextPayload(frame));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

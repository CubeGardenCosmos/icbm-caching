package cn.cubegarden.icbm.caching.protocol.encapsulation;

import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import java.io.IOException;
import java.util.Arrays;

/**
 * 封装帧编解码（草稿 encapsulated-channel §3.1 线上布局；golden 测试锁定）。
 *
 * <p>线上布局：
 * <pre>
 * 明文层（握手期）：[magic "ICBM"(4)][length(4 大端，明文载荷长)][payload]
 * 加密层（ACTIVE）：[magic "ICBM"(4)][length(4 大端，= ciphertext 长)][ciphertext]
 *   ciphertext = ChaCha20-Poly1305([streamId(1)][body]，AAD=magic‖length)
 * </pre>
 *
 * <p>首字节 magic 判别：master 端口只接受封装流量，连接首字节非 magic → 立即 RST
 * （SO_LINGER=0，不读剩余数据）。AAD 绑定帧头（magic+长度），streamId 在明文体
 * 内被 AEAD tag 完整性保护。
 */
public final class EncapsulatedFrame {

    /** 解密后的帧内容。 */
    public record Decrypted(StreamId stream, byte[] body) {
    }

    private EncapsulatedFrame() {
    }

    /** 连接首字节 magic 判别（服务端读到首字节后立即判定，非 magic → RST）。 */
    public static boolean startsWithMagic(byte firstByte) {
        return firstByte == ProtocolConstants.MAGIC[0];
    }

    /** 明文握手帧（AUTH / AUTH_ACK 均走此编码）。 */
    public static byte[] encodePlaintextFrame(byte[] payload) {
        if (payload == null || payload.length > ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES) {
            throw new IllegalArgumentException("payload 长度越界: " + (payload == null ? "null" : payload.length));
        }
        byte[] frame = new byte[8 + payload.length];
        System.arraycopy(ProtocolConstants.MAGIC, 0, frame, 0, 4);
        writeIntBE(frame, 4, payload.length);
        System.arraycopy(payload, 0, frame, 8, payload.length);
        return frame;
    }

    /** 解码明文握手帧（校验 magic/长度/上限；失败抛 IOException → 断连）。 */
    public static byte[] decodePlaintextPayload(byte[] frame) throws IOException {
        validateHeader(frame);
        int payloadLength = readIntBE(frame, 4);
        if (payloadLength != frame.length - 8) {
            throw new IOException("明文帧长度与载荷不符: " + payloadLength + " != " + (frame.length - 8));
        }
        return Arrays.copyOfRange(frame, 8, frame.length);
    }

    /** 编码加密帧（streamId 在明文体首字节；AAD = magic + ciphertext 长度）。 */
    public static byte[] encodeEncryptedFrame(EncapsulatedCipher cipher, StreamId stream, byte[] body) {
        if (stream == null) {
            throw new IllegalArgumentException("stream 不能为空");
        }
        if (body == null || body.length > ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES - 1 - ProtocolConstants.CHACHA_TAG_BYTES) {
            throw new IllegalArgumentException("body 长度越界: " + (body == null ? "null" : body.length));
        }
        byte[] plaintext = new byte[1 + body.length];
        plaintext[0] = stream.id();
        System.arraycopy(body, 0, plaintext, 1, body.length);
        byte[] aad = headerAad(plaintext.length + ProtocolConstants.CHACHA_TAG_BYTES);
        byte[] ciphertext = cipher.encrypt(plaintext, aad);
        byte[] frame = new byte[8 + ciphertext.length];
        System.arraycopy(ProtocolConstants.MAGIC, 0, frame, 0, 4);
        writeIntBE(frame, 4, ciphertext.length);
        System.arraycopy(ciphertext, 0, frame, 8, ciphertext.length);
        return frame;
    }

    /** 解码加密帧；magic/长度/AEAD 校验失败 → IOException（fail closed）。 */
    public static Decrypted decodeEncryptedFrame(EncapsulatedCipher cipher, byte[] frame) throws IOException {
        validateHeader(frame);
        int cipherLength = readIntBE(frame, 4);
        if (cipherLength != frame.length - 8) {
            throw new IOException("加密帧长度与载荷不符: " + cipherLength + " != " + (frame.length - 8));
        }
        byte[] ciphertext = Arrays.copyOfRange(frame, 8, frame.length);
        byte[] plaintext = cipher.decrypt(ciphertext, headerAad(cipherLength));
        if (plaintext.length == 0) {
            throw new IOException("解密后为空帧");
        }
        StreamId stream = StreamId.fromId(plaintext[0]);
        if (stream == null) {
            throw new IOException("未知 streamId: " + plaintext[0]);
        }
        return new Decrypted(stream, Arrays.copyOfRange(plaintext, 1, plaintext.length));
    }

    private static byte[] headerAad(int cipherLength) {
        byte[] aad = new byte[8];
        System.arraycopy(ProtocolConstants.MAGIC, 0, aad, 0, 4);
        writeIntBE(aad, 4, cipherLength);
        return aad;
    }

    private static void validateHeader(byte[] frame) throws IOException {
        if (frame == null || frame.length < 8) {
            throw new IOException("帧过短");
        }
        for (int i = 0; i < ProtocolConstants.MAGIC.length; i++) {
            if (frame[i] != ProtocolConstants.MAGIC[i]) {
                throw new IOException("bad magic");
            }
        }
        int length = readIntBE(frame, 4);
        if (length < 0 || length > ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES) {
            throw new IOException("封装帧长度越界: " + length);
        }
    }

    private static void writeIntBE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static int readIntBE(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 24)
                | ((source[offset + 1] & 0xFF) << 16)
                | ((source[offset + 2] & 0xFF) << 8)
                | (source[offset + 3] & 0xFF);
    }
}

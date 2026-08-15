package cn.cubegarden.icbm.caching.protocol.encapsulation;

import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 封装握手消息编解码（草稿 encapsulated-channel §3.1；仅握手期明文传输）。
 *
 * <p>线上布局（golden 测试锁定）：
 * <pre>
 * AUTH:    [authTokenLen(2)=16][authToken(16)][uuid(16)][clientNonce(16)]
 * AUTH_ACK:[accepted(1)][serverNonce(16)][reason(2+utf8)]（accepted=false 时 reason 非空）
 * </pre>
 * 会话密钥派生：{@code sessionKey = HKDF-SHA256(PSK, salt=clientNonce‖serverNonce‖uuid,
 * info="icbm-session")}，见 {@link SessionKeyDerivation}。
 */
public final class EncapsulatedHandshake {

    private EncapsulatedHandshake() {
    }

    /** AUTH 请求：客户端→master。 */
    public record Auth(byte[] authToken, byte[] uuid, byte[] clientNonce) {

        public Auth {
            if (authToken == null || authToken.length != ProtocolConstants.AUTH_TOKEN_BYTES) {
                throw new IllegalArgumentException("authToken 必须为 16 字节");
            }
            if (uuid == null || uuid.length != ProtocolConstants.UUID_BYTES) {
                throw new IllegalArgumentException("uuid 必须为 16 字节");
            }
            if (clientNonce == null || clientNonce.length != ProtocolConstants.UUID_BYTES) {
                throw new IllegalArgumentException("clientNonce 必须为 16 字节");
            }
        }
    }

    /** AUTH_ACK 响应：master→客户端（accepted=false 后 master 立即关闭连接）。 */
    public record AuthAck(boolean accepted, byte[] serverNonce, String reason) {

        public AuthAck {
            if (accepted && (serverNonce == null || serverNonce.length != ProtocolConstants.UUID_BYTES)) {
                throw new IllegalArgumentException("accepted=true 必须携带 16 字节 serverNonce");
            }
            if (serverNonce == null) {
                serverNonce = new byte[ProtocolConstants.UUID_BYTES];
            }
            if (reason == null) {
                reason = "";
            }
        }
    }

    public static byte[] encodeAuth(Auth auth) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(auth.authToken().length);
            out.write(auth.authToken());
            out.write(auth.uuid());
            out.write(auth.clientNonce());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("AUTH encode failed", e);
        }
    }

    public static Auth decodeAuth(byte[] frame) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
        int tokenLength = in.readUnsignedShort();
        if (tokenLength != ProtocolConstants.AUTH_TOKEN_BYTES) {
            throw new IOException("authToken 长度非法: " + tokenLength);
        }
        byte[] token = new byte[tokenLength];
        in.readFully(token);
        byte[] uuid = new byte[ProtocolConstants.UUID_BYTES];
        in.readFully(uuid);
        byte[] clientNonce = new byte[ProtocolConstants.UUID_BYTES];
        in.readFully(clientNonce);
        return new Auth(token, uuid, clientNonce);
    }

    public static byte[] encodeAuthAck(AuthAck ack) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBoolean(ack.accepted());
            out.write(ack.serverNonce());
            MessageBodyCodec.writeString(out, ack.reason());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("AUTH_ACK encode failed", e);
        }
    }

    public static AuthAck decodeAuthAck(byte[] frame) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
        boolean accepted = in.readBoolean();
        byte[] serverNonce = new byte[ProtocolConstants.UUID_BYTES];
        in.readFully(serverNonce);
        String reason = MessageBodyCodec.readString(in);
        return new AuthAck(accepted, serverNonce, reason);
    }
}

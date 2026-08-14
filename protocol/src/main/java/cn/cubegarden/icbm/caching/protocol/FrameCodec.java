package cn.cubegarden.icbm.caching.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/**
 * 控制帧编解码：{@code ICBM magic(4) + schemaVersion(1) + messageType(1) + requestId(8) +
 * sourceServerId(2字节长度+UTF-8) + worldEpoch(8) + sequence(8) + sentAt(8) + bodyLength(4) + body}。
 *
 * <p>长度前缀为 4 字节大端（草稿 phase2-control-protocol §3 帧格式取舍）；
 * 传输层（netty/阻塞 socket）负责按长度切帧，本类只做单帧编解码。
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    /** 编码单帧（含 magic/schema/包络/body）。 */
    public static byte[] encodeFrame(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 + envelope.body().length);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(ProtocolConstants.MAGIC);
            out.writeByte(envelope.schemaVersion());
            out.writeByte(envelope.messageType().id());
            out.writeLong(envelope.requestId());
            MessageBodyCodec.writeString(out, envelope.sourceServerId());
            out.writeLong(envelope.worldEpoch());
            out.writeLong(envelope.sequence());
            out.writeLong(envelope.sentAt());
            out.writeInt(envelope.body().length);
            out.write(envelope.body());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("frame encode failed", e);
        }
    }

    /**
     * 解码单帧。任何字段非法（magic/schema/长度/类型）抛 {@link IOException}，
     * 调用方必须断连而非猜测兼容格式（fail closed）。
     */
    public static Envelope decodeFrame(byte[] frame) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
        byte[] magic = new byte[ProtocolConstants.MAGIC.length];
        in.readFully(magic);
        for (int i = 0; i < magic.length; i++) {
            if (magic[i] != ProtocolConstants.MAGIC[i]) {
                throw new IOException("bad magic");
            }
        }
        byte schemaVersion = in.readByte();
        if (schemaVersion != ProtocolConstants.SCHEMA_VERSION) {
            throw new IOException("unsupported schemaVersion: " + schemaVersion);
        }
        MessageType type = MessageType.fromId(in.readByte());
        if (type == null) {
            throw new IOException("unknown messageType");
        }
        long requestId = in.readLong();
        String sourceServerId;
        try {
            sourceServerId = MessageBodyCodec.readString(in);
        } catch (EOFException e) {
            throw new IOException("truncated sourceServerId", e);
        }
        long worldEpoch = in.readLong();
        long sequence = in.readLong();
        long sentAt = in.readLong();
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > ProtocolConstants.DEFAULT_MAX_FRAME_BYTES) {
            throw new IOException("body length out of range: " + bodyLength);
        }
        byte[] body = new byte[bodyLength];
        in.readFully(body);
        return Envelope.of(type, requestId, sourceServerId, worldEpoch, sequence, sentAt, body);
    }
}

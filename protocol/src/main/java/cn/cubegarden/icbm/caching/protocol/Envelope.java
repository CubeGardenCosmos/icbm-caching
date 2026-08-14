package cn.cubegarden.icbm.caching.protocol;

import java.util.Objects;

/**
 * 控制消息包络（CACHE_PROTOCOL.md §1）。
 *
 * <p>sequence 在同一发送方向（同连接）内单调递增；跨连接连续性由对象库
 * 单调 required/committed 与幂等操作保证（草稿 phase2-control-protocol §4.3）。
 */
public record Envelope(
        byte schemaVersion,
        MessageType messageType,
        long requestId,
        String sourceServerId,
        long worldEpoch,
        long sequence,
        long sentAt,
        byte[] body
) {

    public Envelope {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        Objects.requireNonNull(body, "body");
        if (schemaVersion != ProtocolConstants.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (body.length > ProtocolConstants.MAX_FRAME_HARD_LIMIT) {
            throw new IllegalArgumentException("body too large: " + body.length);
        }
    }

    public static Envelope of(
            MessageType messageType,
            long requestId,
            String sourceServerId,
            long worldEpoch,
            long sequence,
            long sentAt,
            byte[] body
    ) {
        return new Envelope(
                ProtocolConstants.SCHEMA_VERSION, messageType, requestId,
                sourceServerId, worldEpoch, sequence, sentAt, body
        );
    }
}

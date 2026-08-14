package cn.cubegarden.icbm.caching.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 控制协议常量（docs/CACHE_PROTOCOL.md §1 包络字段；草稿 phase2-control-protocol §3/§4）。
 *
 * <p>帧格式：{@code ICBM magic(4) + schemaVersion(1) + messageType(1) + requestId(8) +
 * sourceServerId(utf16 长度前缀字符串) + worldEpoch(8) + sequence(8) + sentAt(8) +
 * bodyLength(4) + body(bodyLength 字节)}。
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    /** 帧头魔数（"ICBM"），用于快速识别与控制通道隔离。 */
    public static final byte[] MAGIC = {'I', 'C', 'B', 'M'};

    /** 当前 schema 版本（唯一冻结值，见 docs/VERSION_FREEZE.md）。 */
    public static final byte SCHEMA_VERSION = 1;

    /** 默认单帧 body 上限（16 MiB）；PUT_SNAPSHOT 的 payload 单独受 {@link #MAX_SNAPSHOT_BYTES} 限制。 */
    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** 单个快照 payload 上限（压缩后；GZIP 区块 NBT 远小于此，留余量防放大攻击）。 */
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;

    /** 字符串上限（sourceServerId、fingerprint、dimensionId 等）。 */
    public static final int MAX_STRING_BYTES = 65535;

    /** HELLO 握手超时（毫秒）。 */
    public static final long HELLO_TIMEOUT_MILLIS = 10_000;

    /** 快照格式（CACHE_PROTOCOL PUT_SNAPSHOT.snapshotFormat）。 */
    public static final String SNAPSHOT_FORMAT_NBT_CHUNK_V1 = "NBT_CHUNK_V1";

    /** 快照压缩算法（CACHE_PROTOCOL PUT_SNAPSHOT.compression）。 */
    public static final String COMPRESSION_GZIP = "GZIP";

    public static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

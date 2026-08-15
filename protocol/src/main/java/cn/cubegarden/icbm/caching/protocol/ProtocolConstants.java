package cn.cubegarden.icbm.caching.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 控制协议常量（docs/CACHE_PROTOCOL.md §1 包络字段；草稿 phase2-control-protocol §3/§4）。
 *
 * <p>帧格式：传输层为 {@code [4字节大端长度][ICBM magic(4) + schemaVersion(1) + messageType(1) +
 * requestId(8) + sourceServerId(utf16 长度前缀字符串) + worldEpoch(8) + sequence(8) + sentAt(8) +
 * bodyLength(4) + body(bodyLength 字节)]}；{@link FrameCodec} 只处理长度后的帧体。
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    /** 帧头魔数（"ICBM"），用于快速识别与控制通道隔离。 */
    public static final byte[] MAGIC = {'I', 'C', 'B', 'M'};

    /** 当前 schema 版本（唯一冻结值，见 docs/VERSION_FREEZE.md）。 */
    public static final byte SCHEMA_VERSION = 1;

    /** 单个快照 payload 上限（压缩后；GZIP 区块 NBT 远小于此，留余量防放大攻击）。 */
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;

    /** 默认单帧 body 上限（16 MiB）；实际由帧层按配置拦截，PUT_SNAPSHOT 的 payload 单独受 {@link #MAX_SNAPSHOT_BYTES} 限制。 */
    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** 单帧硬上限（Envelope 构造兜底；含最大快照 + 元数据余量，防越界分配）。 */
    public static final int MAX_FRAME_HARD_LIMIT = MAX_SNAPSHOT_BYTES + 4 * 1024 * 1024;

    /** 字符串上限（sourceServerId、fingerprint、dimensionId 等）。 */
    public static final int MAX_STRING_BYTES = 65535;

    /** HELLO 握手超时（毫秒）。 */
    public static final long HELLO_TIMEOUT_MILLIS = 10_000;

    /** 封装通道（CACHE_PROTOCOL §1，草稿 encapsulated-channel §3.1）常量。 */
    /** AUTH 证明 token 长度（HMAC-SHA256 前 16 字节）。 */
    public static final int AUTH_TOKEN_BYTES = 16;

    /** UUID / nonce / sharedSecret 定长 16 字节。 */
    public static final int UUID_BYTES = 16;

    /** ChaCha20-Poly1305 nonce 长度（JDK 固定 12 字节）。 */
    public static final int CHACHA_NONCE_BYTES = 12;

    /** ChaCha20-Poly1305 AEAD tag 长度（JDK 固定 16 字节）。 */
    public static final int CHACHA_TAG_BYTES = 16;

    /** 会话密钥长度（HKDF-SHA256 输出 32 字节）。 */
    public static final int SESSION_KEY_BYTES = 32;

    /** 封装帧长度硬上限（控制帧 body 上限 + 流头 + AEAD tag 余量）。 */
    public static final int MAX_ENCAPSULATED_FRAME_BYTES = MAX_FRAME_HARD_LIMIT + 1 + CHACHA_TAG_BYTES + 64;

    /** HKDF 派生用途（info 参数，见草稿 encapsulated-channel §3.1）。 */
    public static final String HKDF_INFO_SESSION = "icbm-session";

    /** AUTH 证明用途（HMAC 消息前缀，见草稿 encapsulated-channel §3.1）。 */
    public static final String AUTH_MESSAGE_PREFIX = "ICBM-AUTH";

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

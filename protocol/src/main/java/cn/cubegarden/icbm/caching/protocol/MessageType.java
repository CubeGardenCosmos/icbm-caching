package cn.cubegarden.icbm.caching.protocol;

/**
 * 控制消息类型注册表（字节 id 为线上格式，golden 测试锁定；新增 type 必须同步更新
 * docs/CACHE_PROTOCOL.md 与两侧实现——AGENTS.md 协议约束）。
 */
public enum MessageType {

    HELLO(0x01),
    HELLO_ACK(0x02),
    WORLD_EPOCH(0x03),
    INVALIDATE(0x04),
    PUT_SNAPSHOT(0x05),
    PUT_SNAPSHOT_ACK(0x06),
    RESOLVE_CHUNK_REQ(0x07),
    RESOLVE_CHUNK_RES(0x08),
    SUBSCRIBE(0x09),
    SNAPSHOT_PUSH(0x0A),
    ACK(0x0B),
    RESYNC_REQ(0x0C),
    RESYNC_RES(0x0D),
    HEARTBEAT(0x0E),
    ADMIN_INVALIDATE(0x0F),
    ERROR(0x10);

    private final byte id;

    MessageType(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return this.id;
    }

    /** 未知 type 返回 null（调用方必须 fail closed，不猜测兼容格式）。 */
    public static MessageType fromId(byte id) {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}

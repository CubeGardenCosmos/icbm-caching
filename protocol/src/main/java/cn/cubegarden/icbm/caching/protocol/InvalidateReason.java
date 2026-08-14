package cn.cubegarden.icbm.caching.protocol;

/** INVALIDATE 失效原因（CACHE_PROTOCOL.md §2 INVALIDATE.reason）。 */
public enum InvalidateReason {

    BLOCK_STATE(0),
    BLOCK_ENTITY(1),
    CHUNK_SAVE(2),
    WORLDGEN(3),
    ADMIN(4),
    UNKNOWN(5);

    private final byte id;

    InvalidateReason(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return this.id;
    }

    public static InvalidateReason fromId(byte id) {
        for (InvalidateReason reason : values()) {
            if (reason.id == id) {
                return reason;
            }
        }
        return null;
    }
}

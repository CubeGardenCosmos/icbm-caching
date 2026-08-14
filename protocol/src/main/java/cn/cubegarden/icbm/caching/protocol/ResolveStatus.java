package cn.cubegarden.icbm.caching.protocol;

/** RESOLVE_CHUNK 响应状态（CACHE_PROTOCOL.md §2 RESOLVE_CHUNK.response.status）。 */
public enum ResolveStatus {

    HIT(0),
    MISS(1),
    STALE(2),
    INCOMPATIBLE(3),
    UNAVAILABLE(4);

    private final byte id;

    ResolveStatus(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return this.id;
    }

    public static ResolveStatus fromId(byte id) {
        for (ResolveStatus status : values()) {
            if (status.id == id) {
                return status;
            }
        }
        return null;
    }
}

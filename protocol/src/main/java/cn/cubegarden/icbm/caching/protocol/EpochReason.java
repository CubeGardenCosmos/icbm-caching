package cn.cubegarden.icbm.caching.protocol;

/** WORLD_EPOCH 激活原因（CACHE_PROTOCOL.md §2 WORLD_EPOCH.activationReason）。 */
public enum EpochReason {

    START(0),
    ROLLBACK(1),
    MODPACK_CHANGE(2),
    ADMIN(3);

    private final byte id;

    EpochReason(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return this.id;
    }

    public static EpochReason fromId(byte id) {
        for (EpochReason reason : values()) {
            if (reason.id == id) {
                return reason;
            }
        }
        return null;
    }
}

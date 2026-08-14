package cn.cubegarden.icbm.caching.protocol;

/**
 * PUT_SNAPSHOT 校验结果（对应 PUT_SNAPSHOT_ACK 与 SnapshotSink.PutResult）。
 *
 * <p>任意拒绝都保持 requiredRevision（fail closed），由上层决定是否重试。
 */
public enum PutResult {

    OK(0),
    REJECTED_OUT_OF_ORDER(1),
    REJECTED_EPOCH_MISMATCH(2),
    REJECTED_HASH_MISMATCH(3),
    REJECTED_SIZE_MISMATCH(4),
    REJECTED_UNSUPPORTED(5),
    REJECTED_UNAVAILABLE(6);

    private final byte id;

    PutResult(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return this.id;
    }

    public static PutResult fromId(byte id) {
        for (PutResult result : values()) {
            if (result.id == id) {
                return result;
            }
        }
        return null;
    }
}

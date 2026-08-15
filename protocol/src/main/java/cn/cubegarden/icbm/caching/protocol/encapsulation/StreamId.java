package cn.cubegarden.icbm.caching.protocol.encapsulation;

/**
 * 封装通道流 ID（草稿 encapsulated-channel §3.1 多路复用）。
 *
 * <p>加密帧明文体首字节；控制帧走现有包络（包络 sequence 约束），验证流与明文 MC
 * 报文流不参与包络序号校验。
 */
public enum StreamId {

    CONTROL(0),
    LOGIN_VERIFY(1),
    MC_PASSTHROUGH(2);

    private final byte id;

    StreamId(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return this.id;
    }

    /** 未知流返回 null；调用方必须 fail closed（不猜测兼容格式）。 */
    public static StreamId fromId(byte id) {
        for (StreamId stream : values()) {
            if (stream.id == id) {
                return stream;
            }
        }
        return null;
    }
}

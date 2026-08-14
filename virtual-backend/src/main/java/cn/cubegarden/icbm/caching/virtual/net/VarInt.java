package cn.cubegarden.icbm.caching.virtual.net;

/** Minecraft VarInt 读写（net/minecraft/network/VarInt.java 对照）。 */
public final class VarInt {

    private VarInt() {
    }

    public static int read(io.netty.buffer.ByteBuf in) {
        int value = 0;
        int shift = 0;
        while (shift < 32) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new RuntimeException("VarInt too big");
    }

    public static void write(io.netty.buffer.ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }
}

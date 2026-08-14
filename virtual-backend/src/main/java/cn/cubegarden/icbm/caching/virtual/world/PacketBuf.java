package cn.cubegarden.icbm.caching.virtual.world;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 26.3 FriendlyByteBuf 必要原语重实现（net/minecraft/network/FriendlyByteBuf.java、
 * VarInt.java 对照）。
 *
 * <p>可变长字节缓冲（增长数组），支持写入/读取：VarInt（标准 5 字节）、VarLong、定长
 * 数值、UTF-8 字符串（VarInt 长度前缀）、byte 数组（VarInt 长度前缀）、UUID（2 long）。
 */
public final class PacketBuf {

    private byte[] data;
    private int writeIndex;
    private int readIndex;

    public PacketBuf() {
        this(new byte[64]);
    }

    public PacketBuf(java.nio.ByteBuffer initial) {
        this(initial.array());
    }

    public PacketBuf(byte[] initial) {
        this.data = initial;
    }

    /** 包装已有字节并定位到末尾（读取模式）。 */
    public static PacketBuf wrap(byte[] bytes) {
        PacketBuf buf = new PacketBuf(bytes);
        buf.writeIndex = bytes.length;
        buf.readIndex = 0;
        return buf;
    }

    // ---- 写入 ----

    public PacketBuf writeByte(int value) {
        this.ensure(1);
        this.data[this.writeIndex++] = (byte) value;
        return this;
    }

    public PacketBuf writeBoolean(boolean value) {
        return this.writeByte(value ? 1 : 0);
    }

    public PacketBuf writeShort(int value) {
        this.ensure(2);
        this.data[this.writeIndex++] = (byte) (value >>> 8);
        this.data[this.writeIndex++] = (byte) value;
        return this;
    }

    public PacketBuf writeInt(int value) {
        this.ensure(4);
        for (int i = 3; i >= 0; i--) {
            this.data[this.writeIndex++] = (byte) (value >>> (8 * i));
        }
        return this;
    }

    public PacketBuf writeLong(long value) {
        this.ensure(8);
        for (int i = 7; i >= 0; i--) {
            this.data[this.writeIndex++] = (byte) (value >>> (8 * i));
        }
        return this;
    }

    public PacketBuf writeFloat(float value) {
        return this.writeInt(Float.floatToRawIntBits(value));
    }

    public PacketBuf writeDouble(double value) {
        return this.writeLong(Double.doubleToRawLongBits(value));
    }

    public float readFloat() {
        return Float.intBitsToFloat(this.readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(this.readLong());
    }

    public PacketBuf writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            this.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        this.writeByte(value);
        return this;
    }

    public PacketBuf writeVarLong(long value) {
        while ((value & ~0x7FL) != 0) {
            this.writeByte(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        this.writeByte((int) value);
        return this;
    }

    /** 26.3 writeUtf：VarInt 长度 + UTF-8。 */
    public PacketBuf writeUtf(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        this.writeVarInt(bytes.length);
        this.ensure(bytes.length);
        System.arraycopy(bytes, 0, this.data, this.writeIndex, bytes.length);
        this.writeIndex += bytes.length;
        return this;
    }

    public PacketBuf writeByteArray(byte[] bytes) {
        this.writeVarInt(bytes.length);
        this.ensure(bytes.length);
        System.arraycopy(bytes, 0, this.data, this.writeIndex, bytes.length);
        this.writeIndex += bytes.length;
        return this;
    }

    public PacketBuf writeFixedSizeLongArray(long[] longs) {
        for (long value : longs) {
            this.writeLong(value);
        }
        return this;
    }

    public PacketBuf writeUUID(UUID uuid) {
        this.writeLong(uuid.getMostSignificantBits());
        this.writeLong(uuid.getLeastSignificantBits());
        return this;
    }

    /** 26.3 Identifier：writeUtf("namespace:path")。 */
    public PacketBuf writeIdentifier(String namespace, String path) {
        return this.writeUtf(namespace + ":" + path);
    }

    /** 26.3 ResourceKey：两个 Identifier。 */
    public PacketBuf writeResourceKey(String registryNamespace, String registryPath, String valueNamespace, String valuePath) {
        return this.writeIdentifier(registryNamespace, registryPath).writeIdentifier(valueNamespace, valuePath);
    }

    public PacketBuf writeBytes(byte[] bytes, int offset, int length) {
        this.ensure(length);
        System.arraycopy(bytes, offset, this.data, this.writeIndex, length);
        this.writeIndex += length;
        return this;
    }

    // ---- 读取 ----

    public int readByte() {
        return this.data[this.readIndex++] & 0xFF;
    }

    public boolean readBoolean() {
        return this.readByte() != 0;
    }

    public int readUnsignedShort() {
        int a = this.readByte();
        int b = this.readByte();
        return (a << 8) | b;
    }

    public int readInt() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | this.readByte();
        }
        return value;
    }

    public long readLong() {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | this.readByte();
        }
        return value;
    }

    public int readVarInt() {
        int value = 0;
        int shift = 0;
        while (shift < 32) {
            int b = this.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalStateException("VarInt too big");
    }

    public long readVarLong() {
        long value = 0;
        int shift = 0;
        while (shift < 64) {
            int b = this.readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalStateException("VarLong too big");
    }

    public String readUtf() {
        int length = this.readVarInt();
        if (length < 0 || this.readIndex + length > this.writeIndex) {
            throw new IllegalStateException("bad string length: " + length);
        }
        String value = new String(this.data, this.readIndex, length, StandardCharsets.UTF_8);
        this.readIndex += length;
        return value;
    }

    public byte[] readByteArray() {
        int length = this.readVarInt();
        if (length < 0 || this.readIndex + length > this.writeIndex) {
            throw new IllegalStateException("bad byte array length: " + length);
        }
        byte[] bytes = new byte[length];
        System.arraycopy(this.data, this.readIndex, bytes, 0, length);
        this.readIndex += length;
        return bytes;
    }

    public long[] readFixedSizeLongArray(int count) {
        long[] longs = new long[count];
        for (int i = 0; i < count; i++) {
            longs[i] = this.readLong();
        }
        return longs;
    }

    public UUID readUUID() {
        return new UUID(this.readLong(), this.readLong());
    }

    public boolean readable() {
        return this.readIndex < this.writeIndex;
    }

    public int readableBytes() {
        return this.writeIndex - this.readIndex;
    }

    // ---- 其它 ----

    public int writerIndex() {
        return this.writeIndex;
    }

    public byte[] toByteArray() {
        return java.util.Arrays.copyOf(this.data, this.writeIndex);
    }

    /** 剩余未读字节。 */
    public byte[] readRemaining() {
        int length = this.writeIndex - this.readIndex;
        byte[] bytes = new byte[length];
        System.arraycopy(this.data, this.readIndex, bytes, 0, length);
        this.readIndex = this.writeIndex;
        return bytes;
    }

    private void ensure(int extra) {
        if (this.writeIndex + extra <= this.data.length) {
            return;
        }
        int newCapacity = this.data.length;
        while (newCapacity < this.writeIndex + extra) {
            newCapacity = newCapacity * 2;
        }
        this.data = java.util.Arrays.copyOf(this.data, newCapacity);
    }

    /** VarInt 字节数（编码开销计算用）。 */
    public static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}

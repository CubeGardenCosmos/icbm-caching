package cn.cubegarden.icbm.caching.virtual.world;

/**
 * 26.3 SimpleBitStorage 位存储重实现（net/minecraft/util/SimpleBitStorage.java 对照）。
 *
 * <p>每 long 容纳 {@code 64/bits} 个值，条目按低地址低位排列；{@link #getRaw()} 输出
 * 线上原始 long 数组（PalettedContainer.write 直接写出，无长度前缀）。
 */
public final class SimpleBitStorage {

    private final int bits;
    private final int size;
    private final long mask;
    private final int valuesPerLong;
    private final long[] data;

    /** 从线上/磁盘 long 数组构造（vanilla 校验：长度须与 size/bits 一致）。 */
    public SimpleBitStorage(int bits, int size, long[] data) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("invalid bits: " + bits);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("invalid size: " + size);
        }
        this.bits = bits;
        this.size = size;
        this.mask = (1L << bits) - 1L;
        this.valuesPerLong = 64 / bits;
        int requiredLength = (size + this.valuesPerLong - 1) / this.valuesPerLong;
        if (data == null) {
            throw new IllegalArgumentException("missing storage data");
        }
        if (data.length != requiredLength) {
            throw new IllegalArgumentException(
                    "storage length mismatch: expected " + requiredLength + " got " + data.length
                            + " (bits=" + bits + " size=" + size + ")");
        }
        this.data = data;
    }

    /** 从 int 值数组打包（vanilla 构造函数同款：每 long 反向装入）。 */
    public static long[] pack(int bits, int[] values) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("invalid bits: " + bits);
        }
        int size = values.length;
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        long[] packed = new long[(size + valuesPerLong - 1) / valuesPerLong];
        int inputOffset = 0;
        for (; inputOffset <= size - valuesPerLong; inputOffset += valuesPerLong) {
            long packedValue = 0L;
            for (int indexInLong = valuesPerLong - 1; indexInLong >= 0; indexInLong--) {
                packedValue <<= bits;
                packedValue |= values[inputOffset + indexInLong] & mask;
            }
            packed[inputOffset / valuesPerLong] = packedValue;
        }
        if (inputOffset < size) {
            long packedValue = 0L;
            int remainder = size - inputOffset;
            for (int indexInLong = remainder - 1; indexInLong >= 0; indexInLong--) {
                packedValue <<= bits;
                packedValue |= values[inputOffset + indexInLong] & mask;
            }
            packed[inputOffset / valuesPerLong] = packedValue;
        }
        return packed;
    }

    public int get(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException(index);
        }
        int cellIndex = index / this.valuesPerLong;
        int bitIndex = (index - cellIndex * this.valuesPerLong) * this.bits;
        return (int) (this.data[cellIndex] >> bitIndex & this.mask);
    }

    public int[] unpackAll() {
        int[] values = new int[this.size];
        for (int i = 0; i < this.size; i++) {
            values[i] = this.get(i);
        }
        return values;
    }

    public long[] getRaw() {
        return this.data;
    }

    public int getBits() {
        return this.bits;
    }

    public int getSize() {
        return this.size;
    }
}

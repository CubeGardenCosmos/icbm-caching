package cn.cubegarden.icbm.caching.virtual.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 26.3 SimpleBitStorage 位存储测试（构造/解包/raw 与 vanilla 一致）。 */
class SimpleBitStorageTest {

    @Test
    void knownPackingVector() {
        // 2 bits/值，32 值/long；[0,1,2,3] → 0b11_10_01_00 = 0xE4
        long[] packed = SimpleBitStorage.pack(2, new int[]{0, 1, 2, 3});
        assertEquals(1, packed.length);
        assertEquals(0xE4L, packed[0]);
    }

    @Test
    void getAfterPack() {
        SimpleBitStorage storage = new SimpleBitStorage(2, 4, SimpleBitStorage.pack(2, new int[]{0, 1, 2, 3}));
        assertEquals(0, storage.get(0));
        assertEquals(1, storage.get(1));
        assertEquals(2, storage.get(2));
        assertEquals(3, storage.get(3));
    }

    @Test
    void crossLongBoundary() {
        // 7 bits/值：第一 long 容纳 9 个值，跨 long 边界验证
        int[] values = new int[20];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 100;
        }
        long[] packed = SimpleBitStorage.pack(7, values);
        SimpleBitStorage storage = new SimpleBitStorage(7, values.length, packed);
        assertArrayEquals(values, storage.unpackAll());
    }

    @Test
    void rawLengthMatchesVanillaFormula() {
        long[] packed = SimpleBitStorage.pack(4, new int[4096]);
        assertEquals(256, packed.length); // 4096 * 4 / 64
        long[] packedBiomes = SimpleBitStorage.pack(2, new int[64]);
        assertEquals(2, packedBiomes.length); // 64 * 2 / 64
    }

    @Test
    void lengthMismatchFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleBitStorage(4, 4096, new long[100]));
        assertThrows(IllegalArgumentException.class, () -> new SimpleBitStorage(0, 4096, new long[1024]));
    }
}

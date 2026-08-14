package cn.cubegarden.icbm.caching.virtual.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 26.3 PalettedContainer 重实现测试：从 NBT 解包 → 线上写出的字节布局与
 * vanilla unpack/pack 规则一致（golden 向量手工计算）。
 */
class PalettedContainerTest {

    /** 全局 id 提供者（测试直接透传 palette 条目的序号）。 */
    private static java.util.function.ToIntFunction<Nbt.Tag> idProvider() {
        return tag -> tag.asInt();
    }

    private static Nbt.Tag container(int bits, int[] globalIds, long[] data) {
        Nbt.Tag compound = Nbt.compound();
        List<Nbt.Tag> palette = new java.util.ArrayList<>();
        for (int id : globalIds) {
            palette.add(Nbt.intTag(id));
        }
        compound.asCompound().put("palette", Nbt.list(palette));
        if (data != null) {
            compound.asCompound().put("data", Nbt.longArray(data));
        }
        return compound;
    }

    @Test
    void singleValueWireLayout() {
        // 1 个条目：bits=0 + Single palette VarInt(globalId=0)，无 longs
        PalettedContainer container = PalettedContainer.fromNbt(
                container(-1, new int[]{0}, null), PalettedContainer.Kind.BLOCKS, 4096, idProvider());
        assertArrayEquals(new byte[]{0x00, 0x00}, container.writeToBytes());
        assertArrayEquals(new int[4096], container.unpackGlobalIds()); // 全 air(id=0)
    }

    @Test
    void linearFourBitsWireLayout() {
        // 2 个条目 → 4 bits Linear：byte(4) + VarInt(2) + VarInt(1) + VarInt(2) + 1024 longs
        int[] values = new int[4096];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i % 2 == 0) ? 1 : 2;
        }
        long[] data = SimpleBitStorage.pack(4, values);
        PalettedContainer container = PalettedContainer.fromNbt(
                container(4, new int[]{1, 2}, data), PalettedContainer.Kind.BLOCKS, 4096, idProvider());
        byte[] wire = container.writeToBytes();
        assertEquals(4, wire[0]);
        assertEquals(2, wire[1]); // VarInt size
        assertEquals(1, wire[2]); // globalId 1
        assertEquals(2, wire[3]); // globalId 2
        assertEquals(4 + 8 * 256, wire.length); // 4 字节头 + 256 longs（4096×4/64）
        // raw longs 原样写出
        PacketBuf buf = PacketBuf.wrap(wire);
        buf.readVarInt(); // bits
        buf.readVarInt(); // size
        buf.readVarInt();
        buf.readVarInt();
        assertArrayEquals(data, buf.readFixedSizeLongArray(256));
    }

    @Test
    void hashMapFiveBitsWireLayout() {
        // 17 个条目 → 5 bits HashMap：byte(5) + VarInt(17) + 17×VarInt
        int[] globalIds = new int[17];
        for (int i = 0; i < 17; i++) {
            globalIds[i] = i;
        }
        int[] values = new int[4096];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 17;
        }
        long[] data = SimpleBitStorage.pack(5, values);
        PalettedContainer container = PalettedContainer.fromNbt(
                container(5, globalIds, data), PalettedContainer.Kind.BLOCKS, 4096, idProvider());
        byte[] wire = container.writeToBytes();
        assertEquals(5, wire[0]);
        assertEquals(17, wire[1]);
        PacketBuf buf = PacketBuf.wrap(wire);
        buf.readVarInt();
        int size = buf.readVarInt();
        assertEquals(17, size);
        for (int i = 0; i < 17; i++) {
            assertEquals(i, buf.readVarInt());
        }
        assertArrayEquals(data, buf.readFixedSizeLongArray((4096 + 11) / 12)); // 64/5=12 → ceil(4096/12)
    }

    @Test
    void globalRepackForBiomes() {
        // 9 个 biome → 4 bits 存储 → Global(7 bits, 重编码)，无 palette
        int biomeCount = 9;
        int[] globalIds = new int[biomeCount];
        for (int i = 0; i < biomeCount; i++) {
            globalIds[i] = i;
        }
        int[] values = new int[64];
        for (int i = 0; i < 64; i++) {
            values[i] = i % biomeCount;
        }
        long[] data = SimpleBitStorage.pack(4, values); // 存储 bits=4（ceillog2(9)）
        PalettedContainer container = PalettedContainer.fromNbt(
                container(4, globalIds, data), PalettedContainer.Kind.BIOMES, 73, idProvider());
        byte[] wire = container.writeToBytes();
        assertEquals(7, wire[0]); // globalBits = ceillog2(73)
        // 无 palette：第二个字节即第一个 raw long
        PacketBuf buf = PacketBuf.wrap(wire);
        buf.readVarInt();
        long[] raw = buf.readFixedSizeLongArray((64 + 8) / 9); // 64/7=9 → ceil(64/9)
        // 重编码后每格 = globalIds[oldValue]
        SimpleBitStorage repacked = new SimpleBitStorage(7, 64, raw);
        for (int i = 0; i < 64; i++) {
            assertEquals(values[i], repacked.get(i), "index=" + i);
        }
    }

    @Test
    void missingDataFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> PalettedContainer.fromNbt(
                container(-1, new int[]{1, 2}, null), PalettedContainer.Kind.BLOCKS, 4096, idProvider()));
    }

    @Test
    void emptyPaletteFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> PalettedContainer.fromNbt(
                Nbt.compound(), PalettedContainer.Kind.BLOCKS, 4096, idProvider()));
    }
}

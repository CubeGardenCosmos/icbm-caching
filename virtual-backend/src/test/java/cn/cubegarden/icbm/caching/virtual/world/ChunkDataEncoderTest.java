package cn.cubegarden.icbm.caching.virtual.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 26.3 区块数据包编码测试：合成快照 NBT → 逐字节校验 chunk payload 与光照 payload
 * （布局规则对照 ClientboundLevelChunkWithLightPacket 相关类）。
 */
class ChunkDataEncoderTest {

    private static final int MIN_SECTION_Y = -4;
    private static final int MAX_SECTION_Y = 19;
    private static final int MIN_LIGHT = -5;
    private static final int MAX_LIGHT = 20;

    private RegistryBundle bundle() {
        RegistryBundle bundle = RegistryBundle.minimal();
        return bundle;
    }

    private Nbt.Tag section(int y, Nbt.Tag blockStates, Nbt.Tag biomes, byte[] blockLight, byte[] skyLight) {
        Nbt.Tag section = Nbt.compound();
        Map<String, Nbt.Tag> fields = section.asCompound();
        fields.put("Y", Nbt.intTag(y));
        if (blockStates != null) {
            fields.put("block_states", blockStates);
        }
        if (biomes != null) {
            fields.put("biomes", biomes);
        }
        if (blockLight != null) {
            fields.put("BlockLight", Nbt.byteArray(blockLight));
        }
        if (skyLight != null) {
            fields.put("SkyLight", Nbt.byteArray(skyLight));
        }
        return section;
    }

    private Nbt.Tag blockStateContainer(String name) {
        Nbt.Tag container = Nbt.compound();
        Nbt.Tag state = Nbt.compound();
        state.asCompound().put("Name", Nbt.string(name));
        container.asCompound().put("palette", Nbt.list(List.of(state)));
        return container;
    }

    private Nbt.Tag biomeContainer(String biome) {
        Nbt.Tag container = Nbt.compound();
        container.asCompound().put("palette", Nbt.list(List.of(Nbt.string(biome))));
        return container;
    }

    @Test
    void encodesChunkDataWithExpectedLayout() {
        RegistryBundle registry = bundle();
        Nbt.Tag root = Nbt.compound();
        Map<String, Nbt.Tag> chunk = root.asCompound();
        chunk.put("xPos", Nbt.intTag(1));
        chunk.put("zPos", Nbt.intTag(-2));

        // 高度图（WORLD_SURFACE + MOTION_BLOCKING）
        Nbt.Tag heightmaps = Nbt.compound();
        heightmaps.asCompound().put("WORLD_SURFACE", Nbt.longArray(new long[]{0x11L, 0x22L}));
        heightmaps.asCompound().put("MOTION_BLOCKING", Nbt.longArray(new long[]{0x33L}));
        chunk.put("Heightmaps", heightmaps);

        // 单 section（y=0）：air 方块 + plains biome + 非空 BlockLight
        byte[] blockLight = new byte[2048];
        blockLight[0] = 0x0F;
        List<Nbt.Tag> sections = List.of(section(0, blockStateContainer("minecraft:air"),
                biomeContainer("minecraft:plains"), blockLight, null));
        chunk.put("sections", Nbt.list(sections));

        // 方块实体：箱子 at (1, 64, 2)
        Nbt.Tag chest = Nbt.compound();
        Map<String, Nbt.Tag> chestFields = chest.asCompound();
        chestFields.put("id", Nbt.string("minecraft:chest"));
        chestFields.put("x", Nbt.intTag(1));
        chestFields.put("y", Nbt.intTag(64));
        chestFields.put("z", Nbt.intTag(2));
        chestFields.put("custom", Nbt.intTag(5));
        chunk.put("block_entities", Nbt.list(List.of(chest)));

        ChunkDataEncoder.EncodedChunk encoded =
                ChunkDataEncoder.encode(root, MIN_SECTION_Y, MAX_SECTION_Y, MIN_LIGHT, MAX_LIGHT, registry);
        byte[] payload = encoded.chunkDataPayload();
        PacketBuf buf = PacketBuf.wrap(payload);

        // 1) 高度图：2 项，序 WORLD_SURFACE(1) → MOTION_BLOCKING(4)
        assertEquals(2, buf.readVarInt());
        assertEquals(1, buf.readVarInt());
        assertEquals(2, buf.readVarInt());
        assertEquals(0x11L, buf.readLong());
        assertEquals(0x22L, buf.readLong());
        assertEquals(4, buf.readVarInt());
        assertEquals(1, buf.readVarInt());
        assertEquals(0x33L, buf.readLong());

        // 2) section 缓冲：24 节 × 6 字节（全部 air/plains 单值）
        for (int i = 0; i < 24; i++) {
            assertEquals(0, buf.readUnsignedShort()); // nonEmptyBlockCount
            assertEquals(0, buf.readUnsignedShort()); // fluidCount
            assertEquals(0, buf.readVarInt()); // states bits=0
            assertEquals(0, buf.readVarInt()); // single air globalId=0
            assertEquals(0, buf.readVarInt()); // biomes bits=0
            assertEquals(0, buf.readVarInt()); // single plains globalId=0
        }
        assertTrue(buf.readableBytes() >= 3, "剩余字节应含方块实体列表");

        // 3) 方块实体
        assertEquals(1, buf.readVarInt());
        assertEquals((1 << 4) | 2, buf.readByte()); // packedXZ
        assertEquals(64, buf.readUnsignedShort());
        assertEquals(0, buf.readVarInt()); // chest 类型 id
        assertEquals(1, buf.readByte()); // 有 tag
        byte[] tagBytes = buf.readRemaining();
        Nbt.Tag tag = Nbt.fromBytes(tagBytes);
        Map<String, Nbt.Tag> tagFields = tag.asCompound();
        assertEquals("minecraft:chest", tagFields.get("id").asString());
        assertEquals(5, tagFields.get("custom").asInt());
        assertTrue(!tagFields.containsKey("x") && !tagFields.containsKey("y") && !tagFields.containsKey("z"),
                "update tag 应剥离 x/y/z");
    }

    @Test
    void encodesLightWithMaskAndUpdates() {
        RegistryBundle registry = bundle();
        Nbt.Tag root = Nbt.compound();
        Map<String, Nbt.Tag> chunk = root.asCompound();
        chunk.put("xPos", Nbt.intTag(0));
        chunk.put("zPos", Nbt.intTag(0));
        byte[] skyLight = new byte[2048];
        skyLight[0] = (byte) 0xFF;
        List<Nbt.Tag> sections = List.of(
                section(-5, null, null, null, skyLight), // 光照 section y=-5 → index 0
                section(0, blockStateContainer("minecraft:air"), biomeContainer("minecraft:plains"), new byte[2048], null)
        );
        chunk.put("sections", Nbt.list(sections));

        // 经 GZIP 往返（真实路径：SnapshotRepository 装载 gzip 快照）
        Nbt.Tag parsed = Nbt.fromGzip(Nbt.toGzipBytes(root));
        ChunkDataEncoder.EncodedChunk encoded =
                ChunkDataEncoder.encode(parsed, MIN_SECTION_Y, MAX_SECTION_Y, MIN_LIGHT, MAX_LIGHT, registry);
        PacketBuf buf = PacketBuf.wrap(encoded.lightDataPayload());

        // skyYMask: bit0（y=-5）；blockYMask: bit5（y=0）
        assertArrayEquals(new byte[]{0x01}, buf.readByteArray());
        assertArrayEquals(new byte[]{0x20}, buf.readByteArray());
        assertArrayEquals(new byte[0], buf.readByteArray()); // emptySkyYMask
        assertArrayEquals(new byte[0], buf.readByteArray()); // emptyBlockYMask
        assertEquals(1, buf.readVarInt()); // skyUpdates
        byte[] skyLayer = buf.readByteArray();
        assertEquals(2048, skyLayer.length);
        assertEquals((byte) 0xFF, skyLayer[0]);
        assertEquals(1, buf.readVarInt()); // blockUpdates
        byte[] blockLayer = buf.readByteArray();
        assertEquals(2048, blockLayer.length);
        assertEquals(0, blockLayer[0]); // 全零 BlockLight（非空视为有数据）
        assertTrue(!buf.readable());
    }
}

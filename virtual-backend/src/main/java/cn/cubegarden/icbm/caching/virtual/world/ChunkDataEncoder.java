package cn.cubegarden.icbm.caching.virtual.world;

import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 26.3 区块数据包编码（net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java、
 * ClientboundLevelChunkPacketData.java、ClientboundLightUpdatePacketData.java、
 * LevelChunkSection.java 对照）。
 *
 * <p>输入：源服 committed 快照 NBT（{@code SerializableChunkData.write()} 输出，
 * GZIP 压缩）；输出：chunk 数据包 payload 与光照数据。
 *
 * <p>编码要点：
 * <ul>
 *   <li>高度图：只发 sendToClient 类型（WORLD_SURFACE=1 / MOTION_BLOCKING=4），
 *       顺序固定为枚举序（vanilla 用 HashMap 迭代，顺序本身不稳定；客户端按 id 解入
 *       EnumMap，等价，见 PARITY）；</li>
 *   <li>section 缓冲区：minSectionY..maxSectionY 每节 short(nonEmptyBlockCount) +
 *       short(fluidCount) + states + biomes；计数由 states palette 重算
 *       （NBT 不持久化，LevelChunkSection.updateCounts 对照）；</li>
 *   <li>方块实体：BlockEntityInfo（byte packedXZ + short y + VarInt 类型 id +
 *       optional tag）；tag 由 saveWithFullMetadata 转换：剥离 x/y/z
 *       （线上 update tag 无坐标，客户端用包内位置），保留 id/components（渲染等价，PARITY）；</li>
 *   <li>光照：快照 NBT sections 覆盖 minLightSection..maxLightSection；非空 BlockLight/
 *       SkyLight → 对应 mask 位 + 更新列表（空掩码恒空——快照只存非空层，缺失=客户端保持
 *       初始 0，渲染等价）。</li>
 * </ul>
 */
public final class ChunkDataEncoder {

    /** 高度图类型（Heightmap.Types：id + 序列化键）。 */
    private static final int[] CLIENT_HEIGHTMAP_IDS = {1, 4}; // WORLD_SURFACE, MOTION_BLOCKING
    private static final String[] CLIENT_HEIGHTMAP_KEYS = {"WORLD_SURFACE", "MOTION_BLOCKING"};

    /** 区块数据包编码结果。 */
    public record EncodedChunk(byte[] chunkDataPayload, byte[] lightDataPayload) {
    }

    private ChunkDataEncoder() {
    }

    /**
     * 编码一个已提交快照为 chunk 数据包 + 光照数据包 payload。
     *
     * @param root           快照 NBT 根（GZIP 已解压）
     * @param minSectionY    世界最小 section Y（含）
     * @param maxSectionY    世界最大 section Y（含）
     * @param minLightSection 光照最小 section Y（= minSectionY-1）
     * @param maxLightSection 光照最大 section Y（= maxSectionY+1，不含）
     * @param registry       转储数据（block state 表 / biome id / block entity 类型）
     */
    public static EncodedChunk encode(
            Nbt.Tag root, int minSectionY, int maxSectionY, int minLightSection, int maxLightSection,
            RegistryBundle registry
    ) {
        Map<String, Nbt.Tag> chunk = root.asCompound();
        Map<Integer, Section> sections = parseSections(chunk);

        PacketBuf chunkPayload = new PacketBuf();
        writeHeightmaps(chunkPayload, chunk);
        writeSectionBuffer(chunkPayload, sections, minSectionY, maxSectionY, registry);
        writeBlockEntities(chunkPayload, chunk, registry);
        byte[] chunkBytes = chunkPayload.toByteArray();

        byte[] lightBytes = encodeLight(sections, minLightSection, maxLightSection);
        return new EncodedChunk(chunkBytes, lightBytes);
    }

    // ---- sections ----

    private record Section(
            Integer y,
            Nbt.Tag blockStates,
            Nbt.Tag biomes,
            byte[] blockLight,
            byte[] skyLight
    ) {
    }

    private static Map<Integer, Section> parseSections(Map<String, Nbt.Tag> chunk) {
        Map<Integer, Section> result = new LinkedHashMap<>();
        Nbt.Tag sectionsTag = chunk.get("sections");
        if (sectionsTag == null) {
            return result;
        }
        for (Nbt.Tag sectionTag : sectionsTag.asList()) {
            Map<String, Nbt.Tag> section = sectionTag.asCompound();
            // 26.3 SerializableChunkData.write：Y 以 BYTE 存储
            int y = readSectionY(section);
            result.put(y, new Section(
                    y,
                    section.get("block_states"),
                    section.get("biomes"),
                    byteArrayOrNull(section, "BlockLight"),
                    byteArrayOrNull(section, "SkyLight")
            ));
        }
        return result;
    }

    private static int readSectionY(Map<String, Nbt.Tag> section) {
        Nbt.Tag tag = section.get("Y");
        if (tag == null) {
            return 0;
        }
        if (tag.type() == Nbt.TAG_BYTE) {
            return tag.asByte();
        }
        if (tag.type() == Nbt.TAG_INT) {
            return tag.asInt();
        }
        throw new IllegalArgumentException("section Y 类型不支持: " + tag.type());
    }

    private static byte[] byteArrayOrNull(Map<String, Nbt.Tag> section, String key) {
        Nbt.Tag tag = section.get(key);
        return tag == null ? null : tag.asByteArray();
    }

    // ---- 高度图 ----

    private static void writeHeightmaps(PacketBuf buf, Map<String, Nbt.Tag> chunk) {
        Nbt.Tag heightmaps = chunk.get("Heightmaps");
        int count = 0;
        for (String key : CLIENT_HEIGHTMAP_KEYS) {
            Nbt.Tag data = heightmaps == null ? null : heightmaps.asCompound().get(key);
            if (data != null) {
                count++;
            }
        }
        buf.writeVarInt(count);
        for (int i = 0; i < CLIENT_HEIGHTMAP_IDS.length; i++) {
            Nbt.Tag data = heightmaps == null ? null : heightmaps.asCompound().get(CLIENT_HEIGHTMAP_KEYS[i]);
            if (data == null) {
                continue;
            }
            long[] longs = data.asLongArray();
            buf.writeVarInt(CLIENT_HEIGHTMAP_IDS[i]);
            buf.writeVarInt(longs.length);
            for (long value : longs) {
                buf.writeLong(value);
            }
        }
    }

    // ---- section 缓冲区 ----

    private static void writeSectionBuffer(
            PacketBuf buf, Map<Integer, Section> sections, int minSectionY, int maxSectionY, RegistryBundle registry
    ) {
        for (int y = minSectionY; y <= maxSectionY; y++) {
            Section section = sections.get(y);
            if (section == null) {
                writeEmptySection(buf, registry);
                continue;
            }
            PalettedContainer states = decodeStates(section, registry);
            PalettedContainer biomes = decodeBiomes(section, registry);
            int[] nonEmptyAndFluid = countSection(states, registry);
            buf.writeShort(nonEmptyAndFluid[0]);
            buf.writeShort(nonEmptyAndFluid[1]);
            states.write(buf);
            biomes.write(buf);
        }
    }

    private static void writeEmptySection(PacketBuf buf, RegistryBundle registry) {
        buf.writeShort(0);
        buf.writeShort(0);
        PalettedContainer.singleValue(PalettedContainer.Kind.BLOCKS, registry.blockState("minecraft:air", Map.of()).id())
                .write(buf);
        PalettedContainer.singleValue(PalettedContainer.Kind.BIOMES, registry.biomeId("minecraft:plains"))
                .write(buf);
    }

    private static PalettedContainer decodeStates(Section section, RegistryBundle registry) {
        if (section.blockStates() == null) {
            return PalettedContainer.singleValue(PalettedContainer.Kind.BLOCKS, registry.blockState("minecraft:air", Map.of()).id());
        }
        return PalettedContainer.fromNbt(section.blockStates(), PalettedContainer.Kind.BLOCKS,
                registry.blockStateCount(), stateTag -> stateId(stateTag, registry));
    }

    private static PalettedContainer decodeBiomes(Section section, RegistryBundle registry) {
        if (section.biomes() == null) {
            return PalettedContainer.singleValue(PalettedContainer.Kind.BIOMES, registry.biomeId("minecraft:plains"));
        }
        return PalettedContainer.fromNbt(section.biomes(), PalettedContainer.Kind.BIOMES,
                registry.biomeIds().size(), biomeTag -> registry.biomeId(biomeTag.asString()));
    }

    private static int stateId(Nbt.Tag stateTag, RegistryBundle registry) {
        // BlockState.CODEC = Either(blockNameCodec, fullStateCodec)：无属性状态是纯字符串，
        // 有属性状态是 {Name, Properties}
        if (stateTag.type() == Nbt.TAG_STRING) {
            return stateId0(stateTag.asString(), Map.of(), registry);
        }
        Map<String, Nbt.Tag> fields = stateTag.asCompound();
        String name = Nbt.getString(fields, "Name");
        if (name == null) {
            throw new IllegalArgumentException("block state 缺少 Name");
        }
        Map<String, String> props = new LinkedHashMap<>();
        Nbt.Tag properties = fields.get("Properties");
        if (properties != null) {
            for (Map.Entry<String, Nbt.Tag> p : properties.asCompound().entrySet()) {
                props.put(p.getKey(), p.getValue().asString());
            }
        }
        return stateId0(name, props, registry);
    }

    private static int stateId0(String name, Map<String, String> props, RegistryBundle registry) {
        RegistryBundle.BlockStateEntry entry = registry.blockState(name, props);
        if (entry == null) {
            throw new IllegalArgumentException("未知 block state: " + name + " " + props);
        }
        return entry.id();
    }

    /** 重算 nonEmptyBlockCount / fluidCount（LevelChunkSection.updateCounts 对照）。 */
    private static int[] countSection(PalettedContainer states, RegistryBundle registry) {
        int[] globalIds = states.unpackGlobalIds();
        int nonEmpty = 0;
        int fluid = 0;
        for (int globalId : globalIds) {
            RegistryBundle.BlockStateEntry entry = registry.blockStates().get(globalId);
            if (entry == null) {
                throw new IllegalArgumentException("block state 表缺 id: " + globalId);
            }
            if (!entry.air()) {
                nonEmpty++;
            }
            if (entry.fluid()) {
                fluid++;
            }
        }
        return new int[]{nonEmpty, fluid};
    }

    // ---- 方块实体 ----

    private static void writeBlockEntities(PacketBuf buf, Map<String, Nbt.Tag> chunk, RegistryBundle registry) {
        Nbt.Tag blockEntities = chunk.get("block_entities");
        int count = blockEntities == null ? 0 : blockEntities.asList().size();
        buf.writeVarInt(count);
        if (blockEntities == null) {
            return;
        }
        for (Nbt.Tag entityTag : blockEntities.asList()) {
            Map<String, Nbt.Tag> tag = entityTag.asCompound();
            String id = Nbt.getString(tag, "id");
            int typeId = id == null ? -1 : registry.blockEntityTypeId(id);
            if (typeId < 0) {
                // 未知方块实体类型：跳过该实体（不阻塞整个区块；fail closed 语义下仍记录）
                throw new IllegalArgumentException("未知方块实体类型: " + id);
            }
            int x = Nbt.getInt(tag, "x", 0);
            int y = Nbt.getInt(tag, "y", 0);
            int z = Nbt.getInt(tag, "z", 0);
            int packedXz = (x & 15) << 4 | (z & 15);
            buf.writeByte(packedXz);
            buf.writeShort(y);
            buf.writeVarInt(typeId);
            Nbt.Tag updateTag = toUpdateTag(entityTag);
            if (updateTag == null || updateTag.asCompound().isEmpty()) {
                buf.writeByte(0);
            } else {
                buf.writeByte(1);
                byte[] tagBytes = Nbt.toBytes(updateTag);
                buf.writeBytes(tagBytes, 0, tagBytes.length);
            }
        }
    }

    /**
     * saveWithFullMetadata → 线上 update tag：剥离 x/y/z（客户端以包内 packedXZ+y 定位），
     * 保留 id/components/附加数据（渲染等价；与 vanilla 逐字节差异记 PARITY）。
     */
    private static Nbt.Tag toUpdateTag(Nbt.Tag fullTag) {
        Map<String, Nbt.Tag> source = fullTag.asCompound();
        Map<String, Nbt.Tag> out = new LinkedHashMap<>(source);
        out.remove("x");
        out.remove("y");
        out.remove("z");
        return Nbt.compoundFrom(out);
    }

    // ---- 光照 ----

    private static byte[] encodeLight(Map<Integer, Section> sections, int minLightSection, int maxLightSection) {
        int sectionCount = maxLightSection - minLightSection + 1;
        java.util.BitSet skyMask = new java.util.BitSet();
        java.util.BitSet blockMask = new java.util.BitSet();
        List<byte[]> skyUpdates = new ArrayList<>();
        List<byte[]> blockUpdates = new ArrayList<>();
        for (int y = minLightSection; y <= maxLightSection; y++) {
            int index = y - minLightSection;
            Section section = sections.get(y);
            if (section == null) {
                continue;
            }
            if (section.skyLight() != null) {
                skyMask.set(index);
                skyUpdates.add(section.skyLight());
            }
            if (section.blockLight() != null) {
                blockMask.set(index);
                blockUpdates.add(section.blockLight());
            }
        }
        PacketBuf buf = new PacketBuf();
        writeBitSet(buf, skyMask);
        writeBitSet(buf, blockMask);
        writeBitSet(buf, new java.util.BitSet()); // emptySkyYMask（快照不存空层）
        writeBitSet(buf, new java.util.BitSet()); // emptyBlockYMask
        writeByteArrayList(buf, skyUpdates);
        writeByteArrayList(buf, blockUpdates);
        return buf.toByteArray();
    }

    private static void writeBitSet(PacketBuf buf, java.util.BitSet bits) {
        byte[] data = bits.toByteArray();
        buf.writeVarInt(data.length);
        buf.writeBytes(data, 0, data.length);
    }

    private static void writeByteArrayList(PacketBuf buf, List<byte[]> layers) {
        buf.writeVarInt(layers.size());
        for (byte[] layer : layers) {
            buf.writeVarInt(layer.length);
            buf.writeBytes(layer, 0, layer.length);
        }
    }
}

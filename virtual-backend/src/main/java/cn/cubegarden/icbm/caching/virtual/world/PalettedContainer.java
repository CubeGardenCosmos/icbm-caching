package cn.cubegarden.icbm.caching.virtual.world;

import java.util.List;
import java.util.Map;

/**
 * 26.3 PalettedContainer 重实现（net/minecraft/world/level/chunk/PalettedContainer.java、
 * Strategy.java、Configuration.java、Palette 各实现对照）。
 *
 * <p>只实现「从 NBT 解包 → 线上写出」两个方向（虚拟后端不需要可变容器）：
 * <ul>
 *   <li>{@link #fromNbt}: 按 vanilla {@code PalettedContainer.unpack} 规则重建内存态
 *       （palette 大小 → Configuration；bitsInMemory==bitsInStorage 直用；Global
 *       alwaysRepack 重编码），block states 用 4×4×4（4096 格），biomes 用 4×4×4（64 格）；</li>
 *   <li>{@link #write}: byte(bits) + palette（Single: VarInt(globalId)；Linear/HashMap:
 *       VarInt(size)+VarInt(globalId)×n；Global: 无）+ 原始 long 数组。</li>
 * </ul>
 *
 * <p>global id 由调用方提供（block state 表 / biome 注册表顺序），保证与源服一致。
 */
public final class PalettedContainer {

    /** 容器策略（对应 vanilla Strategy.createForBlockStates / createForBiomes）。 */
    public enum Kind {
        BLOCKS(4, 16),
        BIOMES(2, 4);

        private final int bitsPerAxis;
        private final int maxLinearBits;

        Kind(int bitsPerAxis, int maxLinearBits) {
            this.bitsPerAxis = bitsPerAxis;
            this.maxLinearBits = maxLinearBits;
        }
    }

    private enum PaletteType {
        SINGLE, LINEAR, HASHMAP, GLOBAL,
    }

    private static final class Configuration {
        private final int bitsInMemory;
        private final int bitsInStorage;
        private final PaletteType paletteType;

        private Configuration(int bitsInMemory, int bitsInStorage, PaletteType paletteType) {
            this.bitsInMemory = bitsInMemory;
            this.bitsInStorage = bitsInStorage;
            this.paletteType = paletteType;
        }
    }

    private final int entryCount;
    private final int bits;          // 线上 bits（内存 bits）
    private final PaletteType paletteType;
    private final int[] globalIds;   // palette 条目（global id；GLOBAL 为空）
    private final long[] storage;    // 内存存储 raw longs

    private PalettedContainer(int entryCount, int bits, PaletteType paletteType, int[] globalIds, long[] storage) {
        this.entryCount = entryCount;
        this.bits = bits;
        this.paletteType = paletteType;
        this.globalIds = globalIds;
        this.storage = storage;
    }

    /**
     * 从 NBT 容器解包（{@code {palette: [...], data: [...]}}）。
     *
     * @param container      NBT compound（palette 必需，data 可选）
     * @param kind           容器策略
     * @param globalMapSize  全局表大小（global bits = ceil(log2(size))）
     * @param toGlobalId     palette 条目 → 全局 id（block state / biome holder id）
     */
    public static PalettedContainer fromNbt(
            Nbt.Tag container,
            Kind kind,
            int globalMapSize,
            java.util.function.ToIntFunction<Nbt.Tag> toGlobalId
    ) {
        if (container == null || container.type() != Nbt.TAG_COMPOUND) {
            throw new IllegalArgumentException("container is not a compound");
        }
        Map<String, Nbt.Tag> fields = container.asCompound();
        List<Nbt.Tag> entries = Nbt.get(fields, "palette") == null
                ? List.of()
                : Nbt.get(fields, "palette").asList();
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("empty palette");
        }
        Nbt.Tag dataTag = Nbt.get(fields, "data");
        int[] globalIds = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            globalIds[i] = toGlobalId.applyAsInt(entries.get(i));
        }
        return fromEntries(kind, globalMapSize, entries.size(), globalIds, dataTag == null ? null : dataTag.asLongArray());
    }

    /** 从条目数/全局 id/data 直接重建（单测与编码共用）。 */
    public static PalettedContainer fromEntries(
            Kind kind, int globalMapSize, int entryCount, int[] globalIds, long[] data
    ) {
        int containerCount = 1 << (kind.bitsPerAxis * 3);
        int globalBits = ceillog2(globalMapSize);
        Configuration config = configurationFor(kind, globalBits, ceillog2(Math.max(1, entryCount)));
        if (config.bitsInStorage == 0) {
            // 单值：ZeroBitStorage（无线数据）
            return new PalettedContainer(containerCount, 0, PaletteType.SINGLE, new int[]{globalIds[0]}, new long[0]);
        }
        if (data == null) {
            throw new IllegalArgumentException("missing data for non-zero storage");
        }
        if (!config.paletteType.equals(PaletteType.GLOBAL) && config.bitsInMemory == config.bitsInStorage) {
            // 直用：SimpleBitStorage(bitsInMemory, count, data)
            new SimpleBitStorage(config.bitsInMemory, containerCount, data);
            return new PalettedContainer(containerCount, config.bitsInMemory, config.paletteType, globalIds, data);
        }
        // Global alwaysRepack：旧存储按 bitsInStorage 解包，逐格重编码为全局 id
        SimpleBitStorage oldStorage = new SimpleBitStorage(config.bitsInStorage, containerCount, data);
        int[] newContents = new int[containerCount];
        for (int i = 0; i < containerCount; i++) {
            newContents[i] = globalIds[oldStorage.get(i)];
        }
        long[] packed = SimpleBitStorage.pack(globalBits, newContents);
        return new PalettedContainer(containerCount, globalBits, PaletteType.GLOBAL, new int[0], packed);
    }

    private static Configuration configurationFor(Kind kind, int globalBits, int bits) {
        if (kind == Kind.BLOCKS) {
            return switch (bits) {
                case 0 -> new Configuration(0, 0, PaletteType.SINGLE);
                case 1, 2, 3, 4 -> new Configuration(4, 4, PaletteType.LINEAR);
                case 5 -> new Configuration(5, 5, PaletteType.HASHMAP);
                case 6 -> new Configuration(6, 6, PaletteType.HASHMAP);
                case 7 -> new Configuration(7, 7, PaletteType.HASHMAP);
                case 8 -> new Configuration(8, 8, PaletteType.HASHMAP);
                default -> new Configuration(globalBits, bits, PaletteType.GLOBAL);
            };
        }
        return switch (bits) {
            case 0 -> new Configuration(0, 0, PaletteType.SINGLE);
            case 1 -> new Configuration(1, 1, PaletteType.LINEAR);
            case 2 -> new Configuration(2, 2, PaletteType.LINEAR);
            case 3 -> new Configuration(3, 3, PaletteType.LINEAR);
            default -> new Configuration(globalBits, bits, PaletteType.GLOBAL);
        };
    }

    /** 写入区块数据缓冲（vanilla PalettedContainer.Data.write 同构）。 */
    public void write(PacketBuf buf) {
        buf.writeByte(this.bits);
        switch (this.paletteType) {
            case SINGLE -> buf.writeVarInt(this.globalIds[0]);
            case LINEAR, HASHMAP -> {
                buf.writeVarInt(this.globalIds.length);
                for (int id : this.globalIds) {
                    buf.writeVarInt(id);
                }
            }
            case GLOBAL -> {
                // 无 palette
            }
        }
        for (long value : this.storage) {
            buf.writeLong(value);
        }
    }

    /** 便于测试：section 内各全局 id（按索引顺序解包）。 */
    public int[] unpackGlobalIds() {
        if (this.paletteType == PaletteType.SINGLE) {
            int[] values = new int[this.entryCount];
            java.util.Arrays.fill(values, this.globalIds[0]);
            return values;
        }
        SimpleBitStorage storage = new SimpleBitStorage(this.bits, this.entryCount, this.storage);
        int[] values = new int[this.entryCount];
        for (int i = 0; i < this.entryCount; i++) {
            values[i] = this.globalIds[storage.get(i)];
        }
        return values;
    }

    public int bits() {
        return this.bits;
    }

    /** 构造一个全默认单值容器（空 section：air / 默认 biome）。 */
    public static PalettedContainer singleValue(Kind kind, int globalId) {
        int containerCount = 1 << (kind.bitsPerAxis * 3);
        return new PalettedContainer(containerCount, 0, PaletteType.SINGLE, new int[]{globalId}, new long[0]);
    }

    /** 仅测试用：校验两个容器写出字节一致。 */
    public byte[] writeToBytes() {
        PacketBuf buf = new PacketBuf(java.nio.ByteBuffer.allocate(64 + this.storage.length * 8 + this.globalIds.length * 8));
        this.write(buf);
        return buf.toByteArray();
    }

    private static int ceillog2(int value) {
        if (value <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}

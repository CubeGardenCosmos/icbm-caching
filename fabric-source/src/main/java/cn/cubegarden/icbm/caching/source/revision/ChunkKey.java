package cn.cubegarden.icbm.caching.source.revision;

import net.minecraft.resources.Identifier;

/**
 * 缓存键：维度 + 区块坐标（对应 CACHE_PROTOCOL.md 缓存键的 dimensionId/chunkX/chunkZ）。
 *
 * <p>持久化中 revision 以 "x,z" 字符串作为维度内键（见 {@link #keyString()}）。
 */
public record ChunkKey(Identifier dimension, int x, int z) {

    public static ChunkKey of(Identifier dimension, int x, int z) {
        return new ChunkKey(dimension, x, z);
    }

    /** 持久化键："{x},{z}"。 */
    public String keyString() {
        return this.x + "," + this.z;
    }

    /** 从持久化键解析（非法格式抛 IllegalArgumentException，不静默回退）。 */
    public static ChunkKey fromKeyString(Identifier dimension, String key) {
        int comma = key.indexOf(',');
        if (comma <= 0 || comma == key.length() - 1) {
            throw new IllegalArgumentException("invalid chunk key: " + key);
        }
        int x = Integer.parseInt(key.substring(0, comma));
        int z = Integer.parseInt(key.substring(comma + 1));
        return new ChunkKey(dimension, x, z);
    }

    @Override
    public String toString() {
        return this.dimension + "@" + this.x + "," + this.z;
    }
}

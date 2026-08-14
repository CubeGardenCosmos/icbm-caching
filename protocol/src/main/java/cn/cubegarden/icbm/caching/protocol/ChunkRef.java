package cn.cubegarden.icbm.caching.protocol;

/**
 * 区块缓存键（维度 + 区块坐标），对应 CACHE_PROTOCOL.md 缓存键的
 * dimensionId/chunkX/chunkZ。跨组件唯一共享表示（AGENTS.md「同一格式只在一处实现」）。
 */
public record ChunkRef(DimensionRef dimension, int x, int z) {

    public ChunkRef {
        if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000) {
            throw new IllegalArgumentException("chunk coordinate out of range: " + x + "," + z);
        }
    }

    public static ChunkRef of(DimensionRef dimension, int x, int z) {
        return new ChunkRef(dimension, x, z);
    }

    /** 持久化键："{x},{z}"（与 source 侧 ChunkKey.keyString 一致）。 */
    public String keyString() {
        return this.x + "," + this.z;
    }

    @Override
    public String toString() {
        return this.dimension + "@" + this.x + "," + this.z;
    }
}

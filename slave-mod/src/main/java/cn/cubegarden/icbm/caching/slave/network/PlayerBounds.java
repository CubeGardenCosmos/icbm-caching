package cn.cubegarden.icbm.caching.slave.network;

import cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader;

/**
 * 玩家移动边界（草稿 slave-fabric-backend §4.2）：slave 不做 worldgen，未命中区块保持
 * EMPTY（无内容）；玩家只能活动在缓存可服务区块内（fail closed，不展示旧区块）。目标
 * 位置所在区块不可服务 → 拒绝移动（撞墙语义，服务器位置不变）。
 */
public final class PlayerBounds {

    private final SnapshotChunkLoader loader;

    public PlayerBounds(SnapshotChunkLoader loader) {
        this.loader = loader;
    }

    /** 目标位置（方块坐标）所在区块是否可进入。 */
    public boolean canEnter(double x, double z) {
        int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        return this.loader.isChunkServiceable(chunkX, chunkZ);
    }
}

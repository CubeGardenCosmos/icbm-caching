package cn.cubegarden.icbm.caching.slave.world;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 维度出生点注册表：slave 收到 WORLD_EPOCH（携带源服 RespawnData 快照，里程碑 4 协议变更）
 * 后记录；玩家 join 时按维度 teleport（缓存世界与源服世界坐标一致）。
 *
 * <p>默认 0/64/0；无 spawn 记录时玩家留在世界原始出生点（fail closed：不猜测源服位置，
 * 但放行前 master 已保证 spawn 区块推送——正常路径 spawn 必有记录）。
 */
public final class SpawnRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpawnRegistry.class);

    private final Map<String, Messages.SpawnPoint> spawns = new ConcurrentHashMap<>();

    public void onWorldEpoch(DimensionRef dimension, Messages.SpawnPoint spawn) {
        this.spawns.put(dimension.id(), spawn);
    }

    public Messages.SpawnPoint spawnFor(DimensionRef dimension) {
        return this.spawns.getOrDefault(dimension.id(), Messages.SpawnPoint.DEFAULT);
    }

    /** 玩家 join 时按当前维度 spawn teleport（ServerPlayConnectionEvents.JOIN 驱动）。 */
    public void placePlayer(ServerPlayer player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        Messages.SpawnPoint spawn = this.spawnFor(
                DimensionRef.of(level.dimension().identifier().getNamespace(),
                        level.dimension().identifier().getPath()));
        player.teleportTo(level, spawn.x(), spawn.y(), spawn.z(),
                java.util.Set.of(), spawn.yaw(), spawn.pitch(), false);
        LOGGER.info("[icbm-slave] 玩家 {} 已放置到维度出生点 {} ({},{},{})",
                player.getGameProfile().name(), level.dimension().identifier(), spawn.x(), spawn.y(), spawn.z());
    }

    /** 供移动边界校验：目标区块是否在任一已知 spawn 附近可服务由对象库水位决定（此处仅记录）。 */
    public int size() {
        return this.spawns.size();
    }
}

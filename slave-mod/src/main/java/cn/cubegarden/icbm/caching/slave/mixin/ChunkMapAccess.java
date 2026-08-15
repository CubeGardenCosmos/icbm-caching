package cn.cubegarden.icbm.caching.slave.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


/**
 * ChunkMap 内部依赖访问（快照解析需要 level/poiManager/storageInfo；
 * 26.3 ChunkMap.java:133/139/171，storageInfo 继承自 SimpleRegionStorage）。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {

    @Accessor("level")
    ServerLevel icbm_slave$level();

    @Accessor("poiManager")
    PoiManager icbm_slave$poiManager();

    // storageInfo() 为 SimpleRegionStorage 继承的 public 方法（ChunkMap.java:581），
    // 直接 ((SimpleRegionStorage) this).storageInfo() 调用，无需 accessor（@Invoker 只认自身声明）。
    // 注意：@Shadow 会破坏 accessor 接口 subtype（目标为类时报 is not an interface）。
}

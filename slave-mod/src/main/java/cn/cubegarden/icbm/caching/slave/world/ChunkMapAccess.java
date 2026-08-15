package cn.cubegarden.icbm.caching.slave.world;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

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

    /** storageInfo() 声明于 SimpleRegionStorage（public），此处以 invoker 显式转发。 */
    @Invoker("storageInfo")
    RegionStorageInfo icbm_slave$storageInfo();
}

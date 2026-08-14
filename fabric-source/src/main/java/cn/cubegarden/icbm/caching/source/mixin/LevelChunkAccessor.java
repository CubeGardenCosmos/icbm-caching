package cn.cubegarden.icbm.caching.source.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@code LevelChunk.level}（private final，声明于 LevelChunk 本身，
 * 与 Fabric 官方 mixin 的 {@code @Shadow getLevel()} 同源）。
 */
@Mixin(LevelChunk.class)
public interface LevelChunkAccessor {

    @Accessor("level")
    Level icbm_source$getLevel();
}

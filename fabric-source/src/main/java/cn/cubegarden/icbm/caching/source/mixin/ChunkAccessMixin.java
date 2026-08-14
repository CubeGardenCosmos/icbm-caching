package cn.cubegarden.icbm.caching.source.mixin;

import cn.cubegarden.icbm.caching.source.SourceRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 钩住 26.3 区块脏标记权威路径 {@code ChunkAccess.markUnsaved()}（LevelChunk.java /
 * ChunkAccess.java / ChunkHolder.java 对照）。
 *
 * <p>覆盖全部权威脏标记来源：
 * <ul>
 *   <li>方块修改：{@code LevelChunk.setBlockState} → {@code markUnsaved()}；</li>
 *   <li>方块实体修改：{@code Level.blockEntityChanged} → {@code markUnsaved()}（Level.java:871）；</li>
 *   <li>光照变化：{@code ChunkHolder.sectionLightChanged} → {@code markUnsaved()}（ChunkHolder.java:148），
 *       光照跨区块边界传播会使相邻区块 revision 递增——与原版脏标记/保存行为一致（parity matrix 记录）；</li>
 *   <li>命令（setblock/fill/fillbiome）与模组修改同样汇聚于此。</li>
 * </ul>
 *
 * <p><b>不做 {@code isUnsaved()} 守卫</b>：官方 {@code UnsavedListener} 仅在 false→true 转换时
 * 通知（面向保存调度，一次足够），但快照模型需要感知每次内容变化以产生新 revision——
 * 区块可能持续处于 unsaved 状态（直到 vanilla 保存），期间的每次 markUnsaved 都对应一次
 * 真实修改，必须记录（草稿 phase1-source-revision §3 修订说明）。
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {

    @Shadow
    public abstract ChunkPos getPos();

    @Inject(method = "markUnsaved", at = @At("HEAD"))
    private void icbm_source$onMarkUnsaved(CallbackInfo ci) {
        if ((Object) this instanceof LevelChunk levelChunk) {
            Level level = ((LevelChunkAccessor) levelChunk).icbm_source$getLevel();
            if (level instanceof ServerLevel serverLevel) {
                SourceRuntime runtime = SourceRuntime.current();
                if (runtime != null) {
                    runtime.tracker().onChunkUnsaved(serverLevel, this.getPos());
                }
            }
        }
    }
}

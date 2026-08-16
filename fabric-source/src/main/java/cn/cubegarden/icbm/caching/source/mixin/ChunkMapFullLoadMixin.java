package cn.cubegarden.icbm.caching.source.mixin;

import cn.cubegarden.icbm.caching.source.SourceRuntime;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 阶段 9.2 F3：NeedsChunk 加载完成同 tick 续裁（草稿 fast-operation-sync §3.3）。26.3 无
 * 1.21 的 {@code ChunkHolder.onFullChunkLoad} 回调；官方在区块 FULL 状态变化时经
 * mainThreadExecutor 调 {@code ChunkMap.onFullChunkStatusChange(ChunkPos, FullChunkStatus)}
 * （ChunkMap.java:1302；ChunkHolder.java:263/270，主线程）。区块经 setChunkForced 加载完成
 * 必经 INACCESSIBLE→FULL 转换触发本回调 → 通知操作队列按 chunk 同 tick 续裁（去掉
 * 1 tick/次的轮询等待）。降级（ENTITY_TICKING→BLOCK_TICKING）等也会触发
 * isOrAfter(FULL)——无该 chunk 重试时 no-op，无害。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapFullLoadMixin {

    @Inject(method = "onFullChunkStatusChange", at = @At("HEAD"))
    private void icbm_source$onChunkFull(ChunkPos pos, FullChunkStatus status, CallbackInfo ci) {
        if (!status.isOrAfter(FullChunkStatus.FULL)) {
            return;
        }
        SourceRuntime runtime = SourceRuntime.current();
        if (runtime != null) {
            runtime.operationQueue().onChunkFullLoaded(pos.x(), pos.z());
        }
    }
}

package cn.cubegarden.icbm.caching.slave.world;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 区块来源接管（草稿 slave-fabric-backend §4.1/§4.2）：
 * <ul>
 *   <li>{@code scheduleChunkLoad}（ChunkMap.java:565）：磁盘读前先查对象库——命中
 *       （master 连接存活 + committed&gt;=required + epoch 匹配）→ 注入 FULL 快照区块
 *       （persistedStatus=FULL ⇒ 生成管道 canLoadWithoutGeneration 跳过生成）；未命中
 *       → 走官方 createEmptyChunk（EMPTY，不展示旧数据）。</li>
 *   <li>{@code applyStep}（ChunkMap.java:634）：跳过全部非 FULL 生成步骤（不 worldgen）；
 *       FULL 步骤放行（ChunkStatusTasks.full 把 ProtoChunk→LevelChunk，未命中区块成为
 *       空 LevelChunk——玩家由 PlayerBounds 边界拦截，不会进入）。</li>
 * </ul>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$loadFromObjectStore(ChunkPos pos,
                                                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            return;
        }
        ServerLevel level = ((ChunkMapAccess) this).icbm_slave$level();
        ChunkAccess chunk = runtime.chunkLoader().loadChunk(level, pos);
        if (chunk != null) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        }
    }

    @Inject(method = "applyStep", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$skipGeneration(GenerationChunkHolder chunkHolder, ChunkStep step,
                                           StaticCache2D<GenerationChunkHolder> cache,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            return;
        }
        if (step.targetStatus() == ChunkStatus.FULL) {
            return; // FULL 转换必须执行（ImposterProtoChunk/ProtoChunk → LevelChunk）
        }
        // 跳过该层生成：返回中心区块现状（内容不 worldgen，保持 EMPTY/已注入状态）
        ChunkAccess center = cache.get(chunkHolder.getPos().x(), chunkHolder.getPos().z())
                .getChunkIfPresentUnchecked(step.targetStatus().getParent());
        if (center != null) {
            cir.setReturnValue(CompletableFuture.completedFuture(center));
        }
    }
}

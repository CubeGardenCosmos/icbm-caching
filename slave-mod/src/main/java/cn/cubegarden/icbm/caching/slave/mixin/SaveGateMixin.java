package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁持久化（草稿 slave-fabric-backend §4.5）：slave 世界目录独立、不写回——缓存（磁盘
 * 对象库）是唯一持久层（AGENTS.md「持久缓存只保存版本化的区块 NBT 快照与元数据，不保存
 * Anvil 的 .mca」）。拦截真正写盘点 {@code ChunkMap.save(ChunkAccess)}（ChunkMap.java:759）
 * 与关服全量保存：区块写 region 全部跳过（重启后世界从空启动，区块全部来自对象库）。
 *
 * <p>不能拦截 {@code saveChunksEagerly}（ChunkMap.java:507）：它是卸载流程的一部分——
 * 消费 {@code chunksToEagerlySave}（脏标记队列，ChunkMap.setChunkUnsaved :220）并调用
 * {@code saveChunkIfNeeded → save()}，拦截后队列不消费且 {@code ChunkHolder.saveSync}
 * （ChunkHolder.java:117 写盘依赖）不完成，{@code scheduleUnload}（ChunkMap.java:526）
 * 永久等待 → 区块卸载卡死（真机日志「区块刷新超时」10s 兜底）。拦截 save() 本身：
 * {@code tryMarkSaved} 伪装保存完成 → saveChunkIfNeeded 返回 true 消费队列、saveSync
 * 完成、卸载立即继续，且不写磁盘。
 */
@Mixin(ChunkMap.class)
public abstract class SaveGateMixin {

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$blockSave(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // slave 禁持久化：不写 region（跳过 poiManager.flushAll/synchronize）
        }
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$blockWrite(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (SlaveRuntime.current() != null) {
            chunk.tryMarkSaved(); // 与真实保存语义一致：清除 unsaved，避免常驻区块每 tick 重复触发
            cir.setReturnValue(true); // 伪装保存完成：消费保存队列 + saveSync 完成 + 卸载继续
        }
    }
}

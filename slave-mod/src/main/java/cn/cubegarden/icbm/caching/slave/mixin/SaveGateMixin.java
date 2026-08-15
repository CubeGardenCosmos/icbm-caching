package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 禁持久化（草稿 slave-fabric-backend §4.5）：slave 世界目录独立、不写回——缓存（磁盘
 * 对象库）是唯一持久层（AGENTS.md「持久缓存只保存版本化的区块 NBT 快照与元数据，不保存
 * Anvil 的 .mca」）。拦截 {@code ChunkMap.saveAllChunks}（ChunkMap.java:423）及其它保存
 * 调度：区块写 region 全部跳过（重启后世界从空启动，区块全部来自对象库）。
 */
@Mixin(ChunkMap.class)
public abstract class SaveGateMixin {

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$blockSave(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // slave 禁持久化：不写 region
        }
    }

    @Inject(method = "saveChunksEagerly", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$blockEagerSave(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel();
        }
    }
}

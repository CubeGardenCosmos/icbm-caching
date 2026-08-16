package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 边缘世界计算禁用：方块实体 tick（阶段 9.1，草稿 edge-no-world-sim §2.4）。26.3
 * {@code Level.tickBlockEntities()}（Level.java:527，ServerLevel.tick 内 :456 调用）驱动
 * 熔炉/漏斗/刷怪笼/命令方块等 TickingBlockEntity——无 gamerule 控制。slave 的方块实体
 * 状态来自快照（容器内容等），只读展示；tick 不执行。ticker 列表保留（removeBlockEntityTicker
 * rebind NULL_TICKER 语义不变，LevelChunk.java:505），不消费队列避免与快照刷新重建 BE 冲突。
 */
@Mixin(Level.class)
public abstract class LevelWorldSimMixin {

    @Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$noBlockEntityTicks(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 方块实体不 tick（熔炉/漏斗/红石机器等；世界演化归源服）
        }
    }
}

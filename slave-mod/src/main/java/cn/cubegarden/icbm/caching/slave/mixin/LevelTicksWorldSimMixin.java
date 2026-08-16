package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * 边缘世界计算禁用：计划刻/红石 + 流体计划刻（阶段 9.1，草稿 edge-no-world-sim §2.1）。
 * 26.3 ServerLevel 持有两个 LevelTicks 实例（ServerLevel.java:210/211）：blockTicks（红石
 * 计划刻、TNT fuse、沙砾下落、中继器、命令方块延迟等）与 fluidTicks（水/岩浆流动扩散），
 * 在 ServerLevel.tick 内以 {@code blockTicks.tick(tick, 65536, this::tickBlock)} /
 * {@code fluidTicks.tick(tick, 65536, this::tickFluid)} 执行（ServerLevel.java:395/397）——
 * 无 gamerule 控制，必须在此拦截。ticker 队列不消费（collectTicks 保留：快照刷新时计划刻
 * 数据不丢失，仅不执行）。
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksWorldSimMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$noScheduledTicks(long currentTick, int maxTicksToProcess,
                                             BiConsumer<BlockPos, ?> output, CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 计划刻/红石与流体计划刻不执行（世界演化归源服）
        }
    }
}

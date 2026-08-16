package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 边缘世界计算禁用：生物 despawn（阶段 9.1，草稿 edge-no-world-sim §2.4）。26.3
 * ServerLevel.tick 实体循环里每个实体先 {@code entity.checkDespawn()}（ServerLevel.java:441）
 * 再 tick；Mob.checkDespawn（Mob.java:690 覆写，Entity.java:3972 默认空实现）按距离/时间
 * 移除生物——与源服分叉（快照世界无权威实体生命周期）。禁 tick（ServerLevelWorldSimMixin
 * 只放行 ServerPlayer）后生物不演化，但仍会被 checkDespawn 凭空移除 → 一并拦截。实体
 * despawn 是实体生命周期而非世界计算，但 slave 没有权威实体状态，fail closed 保持装载态。
 */
@Mixin(Mob.class)
public abstract class MobDespawnWorldSimMixin {

    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$noMobDespawn(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 生物不 despawn（快照世界实体状态以装载时为准）
        }
    }
}

package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 边缘世界计算禁用：ServerLevel 上 gamerule 不可覆盖的世界演化路径（阶段 9.1，草稿
 * edge-no-world-sim §2.1/§2.4，类名/行号均对照 26.3 minecraft-assets）：
 *
 * <ul>
 *   <li>{@code runBlockEvents}（ServerLevel.java:1263，private）：方块事件（活塞动画/音符盒
 *       音效等）；</li>
 *   <li>{@code tickThunder}（:546）：闪电/骷髅马陷阱（advance_weather=false 已禁天气演化，
 *       此处防快照装载时恰处雷暴状态的残余闪电）；</li>
 *   <li>{@code tickTime}（:480，protected）：时间冻结 v1——gameTime 不前进、scheduled
 *       functions 不执行（slave 无 /schedule 场景）；客户端昼夜恒定在装载时刻（parity）；</li>
 *   <li>{@code tickNonPassenger}（:829）/ {@code tickPassenger}（:842，private）：实体 tick
 *       主体，只保留 ServerPlayer（移动/碰撞/视角）；非玩家实体不 tick（快照世界无权威
 *       实体，存在即视为缓存数据，不得演化）；</li>
 * </ul>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelWorldSimMixin {

    @Inject(method = "runBlockEvents", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$noBlockEvents(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 方块事件（活塞动画/音符盒等）不执行
        }
    }

    @Inject(method = "tickThunder", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$noThunder(LevelChunk chunk, CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 闪电不生成（天气演化归源服）
        }
    }

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$freezeTime(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 时间冻结 v1：gameTime/scheduled functions 不推进
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$onlyPlayerTicks(Entity entity, CallbackInfo ci) {
        if (SlaveRuntime.current() != null && !(entity instanceof ServerPlayer)) {
            ci.cancel(); // 非玩家实体不 tick（含其乘客递归）
        }
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$onlyPlayerPassenger(Entity vehicle, Entity entity, CallbackInfo ci) {
        if (SlaveRuntime.current() != null && !(entity instanceof ServerPlayer)) {
            ci.cancel(); // 玩家骑乘的非玩家乘客不 tick（v1 无骑乘场景，防御性）
        }
    }
}

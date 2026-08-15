package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移动边界（草稿 slave-fabric-backend §4.2，ServerGamePacketListenerImpl.handleMovePlayer
 * 对照，ServerGamePacketListenerImpl.java:1062）：目标位置所在区块不可服务（未命中/落后/
 * epoch 不匹配/master 断开）→ 拒绝移动（teleport 回服务器位置，不进入不确定区块）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerBoundsMixin {

    @Shadow
    @Final
    private ServerPlayer player;

    @Shadow
    public void teleport(double x, double y, double z, float yRot, float xRot) {
        throw new AssertionError("shadow");
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$enforceBounds(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null || !packet.hasPosition()) {
            return; // 纯旋转包不涉位置
        }
        double targetX = packet.getX(this.player.getX());
        double targetZ = packet.getZ(this.player.getZ());
        if (!runtime.playerBounds().canEnter(targetX, targetZ)) {
            // 8.2 按需物化：撞墙时请求目标区块生成（节流/去重/冷却由请求器保证；
            // 目标区块随后经 SNAPSHOT_PUSH 装载，玩家可继续前进）
            runtime.materializeRequester().onBlockedMove(
                    Math.floorDiv((int) Math.floor(targetX), 16),
                    Math.floorDiv((int) Math.floor(targetZ), 16));
            this.teleport(this.player.getX(), this.player.getY(), this.player.getZ(),
                    this.player.getYRot(), this.player.getXRot());
            ci.cancel();
        }
    }
}

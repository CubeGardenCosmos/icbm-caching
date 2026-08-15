package cn.cubegarden.icbm.caching.slave.network;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 旁观闸门（草稿 slave-fabric-backend §4.3）：缓存会话只读（旁观），拦截全部互动——
 * 使用方块/使用物品/交互实体/玩家动作（挖掘/放置/丢弃等）→ 忽略包（无副作用）。预测
 * 占位留阶段 7 操作；移动/聊天等非互动包不受影响。
 *
 * <p>对照 26.3 ServerGamePacketListenerImpl.handleUseItemOn/handleUseItem/handleInteract/
 * handlePlayerAction（ServerGamePacketListenerImpl.java:1267/1337/1407/1834）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SpectateGateMixin {

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$denyUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        this.icbm_slave$deny(ci);
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$denyUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        this.icbm_slave$deny(ci);
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$denyInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        this.icbm_slave$deny(ci);
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$denyPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        this.icbm_slave$deny(ci);
    }

    private void icbm_slave$deny(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 旁观：忽略互动包（服务器状态不变）
        }
    }
}

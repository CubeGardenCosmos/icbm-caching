package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 互动闸门（草稿 real-player-operations §3；原旁观全拒改造）：真实玩家互动包只放行
 * BREAK/PLACE 进乐观操作管道，其余互动（使用物品/交互实体/丢弃/换手/挖掘中止等）保持拒绝
 * （fail closed，客户端以回显撤销其预测）。
 *
 * <p>线程：handlePlayerAction/handleUseItemOn 以 PacketUtils.ensureRunningOnSameThread 开头
 * （网络线程先进 → Server thread 重分发），@Inject HEAD 触发两次；真实路径（世界修改）只在
 * {@code server.isSameThread()}（Server thread）执行，非主线程直接放行让 vanilla 重分发。
 * 对照 26.3 ServerGamePacketListenerImpl.handleUseItemOn/handleUseItem/handleInteract/
 * handlePlayerAction（ServerGamePacketListenerImpl.java:1267/1337/1407/1834）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SpectateGateMixin {

    @Shadow
    @Final
    private ServerPlayer player;

    @Shadow
    public void send(Packet<?> packet) {
        throw new AssertionError("shadow");
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            return;
        }
        MinecraftServer server = this.player.level().getServer();
        if (server == null || !server.isSameThread()) {
            return; // 等待 ensureRunningOnSameThread 重分发到 Server thread
        }
        ci.cancel(); // v1：放置意图进操作管道；其余（使用非方块物品等）拒绝
        runtime.operationHandler().handlePlace(this.player, (ServerGamePacketListenerImpl) (Object) this, packet);
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
    private void icbm_slave$routePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            return;
        }
        MinecraftServer server = this.player.level().getServer();
        if (server == null || !server.isSameThread()) {
            return; // 等待 ensureRunningOnSameThread 重分发到 Server thread
        }
        ci.cancel(); // v1：挖掘意图进操作管道；其余动作保持拒绝
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK ->
                    runtime.operationHandler().handleBreak(this.player,
                            (ServerGamePacketListenerImpl) (Object) this,
                            packet.getPos(), packet.getSequence());
            case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                // v1 无挖掘进度：回显当前状态 + ack（客户端撤销其挖掘预测，fail closed）
                this.send(new ClientboundBlockUpdatePacket(this.player.level(), packet.getPos()));
                this.ackBlockChangesUpTo(packet.getSequence());
            }
            default -> {
                // 丢弃/换手/冲刺/睡眠等：保持拒绝（无世界副作用）
            }
        }
    }

    /** ack 确认客户端块变更序列（ServerGamePacketListenerImpl.java:1470，public）。 */
    @Shadow
    public void ackBlockChangesUpTo(int sequence) {
        throw new AssertionError("shadow");
    }

    private void icbm_slave$deny(CallbackInfo ci) {
        if (SlaveRuntime.current() != null) {
            ci.cancel(); // 旁观：忽略互动包（服务器状态不变）
        }
    }
}

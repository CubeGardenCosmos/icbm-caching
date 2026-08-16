package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 互动闸门（阶段 9.4 直传化；原 real-player-operations §3 全拒 + 9.2 放置/挖掘快路径改造）：
 * 真实玩家互动包分流——放置/挖掘保留乐观操作管道（9.2 SUBMIT_OPERATION 快路径）；其余互动
 * （使用物品/实体互动/背包/容器/快捷栏）经 {@link SlaveRuntime#passthrough()} 直传 master →
 * 源服虚拟玩家权威处理（结果下行注入 + 快照回传）；未连接/直传失败 → fail closed 拒绝
 * （客户端以回显撤销其预测）。
 *
 * <p>线程：handleXxx 以 PacketUtils.ensureRunningOnSameThread 开头（网络线程先进 → Server
 * thread 重分发），@Inject HEAD 触发两次；真实路径只在 {@code server.isSameThread()} 执行，
 * 非主线程直接放行让 vanilla 重分发。对照 26.3 ServerGamePacketListenerImpl
 * （handleUseItemOn:1337 / handleUseItem:1407 / handleInteract:1834 / handlePlayerAction:1267 /
 * handleContainerClick:1938 / handleContainerClose:1932 / handleContainerButtonClick:2008 /
 * handleContainerSlotStateChanged:1042 / handleSetCarriedItem:1479）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SpectateGateMixin {

    @Shadow
    @Final
    private ServerPlayer player;

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
        ci.cancel();
        ItemStack stack = this.player.getItemInHand(packet.getHand());
        if (stack.getItem() instanceof BlockItem) {
            // 放置意图：乐观操作管道（9.2 快路径，源服裁决）
            runtime.operationHandler().handlePlace(this.player,
                    (ServerGamePacketListenerImpl) (Object) this, packet);
        } else {
            // 互动方块（空手/非方块物品右键箱子/拉杆等）：直传源服权威处理
            this.icbm_slave$passthrough(runtime, packet);
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (!this.icbm_slave$passthroughGuard(ci)) {
            return;
        }
        this.icbm_slave$passthrough(SlaveRuntime.current(), packet);
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (!this.icbm_slave$passthroughGuard(ci)) {
            return;
        }
        this.icbm_slave$passthrough(SlaveRuntime.current(), packet);
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (!this.icbm_slave$passthroughGuard(ci)) {
            return;
        }
        this.icbm_slave$passthrough(SlaveRuntime.current(), packet);
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeContainerClose(ServerboundContainerClosePacket packet, CallbackInfo ci) {
        if (!this.icbm_slave$passthroughGuard(ci)) {
            return;
        }
        this.icbm_slave$passthrough(SlaveRuntime.current(), packet);
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeContainerButtonClick(ServerboundContainerButtonClickPacket packet, CallbackInfo ci) {
        if (!this.icbm_slave$passthroughGuard(ci)) {
            return;
        }
        this.icbm_slave$passthrough(SlaveRuntime.current(), packet);
    }

    @Inject(method = "handleContainerSlotStateChanged", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeContainerSlotStateChanged(ServerboundContainerSlotStateChangedPacket packet, CallbackInfo ci) {
        if (!this.icbm_slave$passthroughGuard(ci)) {
            return;
        }
        this.icbm_slave$passthrough(SlaveRuntime.current(), packet);
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$routeSetCarriedItem(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (!this.icbm_slave$passthroughGuard(ci)) {
            return;
        }
        this.icbm_slave$passthrough(SlaveRuntime.current(), packet);
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
        ci.cancel(); // v1：挖掘意图进操作管道；其余动作直传或拒绝
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK ->
                    runtime.operationHandler().handleBreak(this.player,
                            (ServerGamePacketListenerImpl) (Object) this,
                            packet.getPos(), packet.getSequence());
            case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                // v1 无挖掘进度：回显当前状态 + ack（客户端撤销其挖掘预测，fail closed）
                this.icbm_slave$send(new ClientboundBlockUpdatePacket(this.player.level(), packet.getPos()));
                this.ackBlockChangesUpTo(packet.getSequence());
            }
            default -> {
                // 丢弃/换手/冲刺/睡眠等：直传源服权威处理（掉落物/物品交换等世界副作用在源服）
                this.icbm_slave$passthrough(runtime, packet);
            }
        }
    }

    /** 直传前置（主线程 + 运行时就绪）；未就绪 → cancel（拒绝，fail closed）。 */
    @Unique
    private boolean icbm_slave$passthroughGuard(CallbackInfo ci) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            return false;
        }
        MinecraftServer server = this.player.level().getServer();
        if (server == null || !server.isSameThread()) {
            return false; // 非主线程：不 cancel，放行 vanilla 重分发（@Inject HEAD 二次进入）
        }
        ci.cancel();
        return true;
    }

    /** 直传 master → 源服虚拟玩家（权威处理）；失败 → 已 cancel（拒绝，客户端回显撤销）。 */
    @Unique
    private void icbm_slave$passthrough(SlaveRuntime runtime, Packet<?> packet) {
        boolean sent = runtime.passthrough().sendServerbound(this.player, packet);
        if (!sent) {
            LOGGER_WARN(runtime, packet);
        }
    }

    @Unique
    private static void LOGGER_WARN(SlaveRuntime runtime, Packet<?> packet) {
        org.slf4j.LoggerFactory.getLogger("icbm-slave")
                .warn("[icbm-slave] 直传失败（fail closed 拒绝）: {}", packet.getClass().getSimpleName());
    }

    /** ack 确认客户端块变更序列（ServerGamePacketListenerImpl.java:1470，public）。 */
    @Shadow
    public void ackBlockChangesUpTo(int sequence) {
        throw new AssertionError("shadow");
    }

    /** 发送包（send 声明在父类 ServerCommonPacketListenerImpl，@Shadow 不解析继承成员——
     *  26.3 ServerCommonPacketListenerImpl.java:156；cast 调用替代，运行时类型即 target 类）。 */
    @Unique
    private void icbm_slave$send(Packet<?> packet) {
        ((ServerGamePacketListenerImpl) (Object) this).send(packet);
    }
}

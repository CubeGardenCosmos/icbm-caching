package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务器品牌（F3「Brand」行）：覆盖 {@code MinecraftServer.getServerModName()}（26.3
 * MinecraftServer.java:1195，默认 "vanilla"，Fabric loader 会改为 "fabric"）——客户端在
 * 配置阶段收到 {@code ClientboundCustomPayloadPacket(BrandPayload(serverModName))}
 * （ServerConfigurationPacketListenerImpl.java:84）并在 F3 显示。
 *
 * <p>slave 是玩家唯一入口，品牌改为配置的地段名（SlaveConfig.icbm.brandLocation）：
 * 默认 {@code ICBM Caching Server}；配置后如 {@code ICBM Caching Server (HongKong:Kowloon)}。
 * 品牌只影响展示标识，不承担缓存/身份语义；运行时未就绪（装配前）走原逻辑，不阻断启动。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerBrandMixin {

    @Inject(method = "getServerModName", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$brand(CallbackInfoReturnable<String> cir) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime != null) {
            cir.setReturnValue(runtime.brand());
        }
    }
}

package cn.cubegarden.icbm.caching.slave.mixin;

import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ServerboundKeyPacket 原始字段访问（登录握手透传，CACHE_PROTOCOL §9）：RSA 密钥对在
 * master，slave 把客户端加密响应（keybytes/encryptedChallenge）原样递交给 master 解密，
 * 不接触私钥。26.3 ServerboundKeyPacket.java 对照（字段名与官方 mojmap 一致）。
 */
@Mixin(ServerboundKeyPacket.class)
public interface ServerboundKeyPacketAccessor {

    @Accessor("keybytes")
    byte[] icbm_slave$keybytes();

    @Accessor("encryptedChallenge")
    byte[] icbm_slave$encryptedChallenge();
}

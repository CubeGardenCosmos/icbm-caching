package cn.cubegarden.icbm.caching.slave.network;

import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import com.mojang.authlib.GameProfile;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 登录验证委托（草稿 slave-fabric-backend §4.4）：Mixin {@code ServerLoginPacketListenerImpl}
 * —— handleHello 记录客户端声明身份；handleKey 完整接管（26.3 ServerLoginPacketListenerImpl.java:
 * 168-232 对照）：解密会话密钥 → 计算 serverHash → 设加密 → 验证线程委托 master
 * （LOGIN_VERIFY_REQ，阻塞等 RES；在登录线程，不阻塞主线程 tick）；master 验证通过
 * （verified=true）→ 按 master 身份断言构造 GameProfile → 继续官方流程（VERIFYING →
 * 配置阶段）；拒绝 → disconnect（fail closed，不猜测兼容身份）。
 *
 * <p>Mixin 目标缺失自检见 {@link cn.cubegarden.icbm.caching.slave.selfcheck.MixinTargetVerifier}。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginVerifyGateMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginVerifyGateMixin.class);

    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    @Final
    private Connection connection;

    @Shadow
    @Final
    private byte[] challenge;

    @Shadow
    private String requestedUsername;

    @Shadow
    private void startClientVerification(GameProfile profile) {
        throw new AssertionError("shadow");
    }

    @Unique
    private UUID icbm_slave$clientProfileId = UUID.randomUUID();

    @Inject(method = "handleHello", at = @At("HEAD"))
    private void icbm_slave$captureProfile(ServerboundHelloPacket packet, CallbackInfo ci) {
        this.requestedUsername = packet.name();
        this.icbm_slave$clientProfileId = packet.profileId();
    }

    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$delegateVerify(ServerboundKeyPacket packet, CallbackInfo ci) {
        // 协议状态由官方 handleKey 的 Validate.validState 校验（本注入在 HEAD，官方逻辑被 cancel）
        final String name;
        final String digest;
        final byte[] sharedSecret;
        final InetSocketAddress remote;
        try {
            PrivateKey privateKey = this.server.getKeyPair().getPrivate();
            if (!packet.isChallengeValid(this.challenge, privateKey)) {
                throw new IllegalStateException("Protocol error");
            }
            SecretKey secretKey = packet.getSecretKey(privateKey);
            sharedSecret = secretKey.getEncoded();
            digest = new BigInteger(Crypt.digestData("", this.server.getKeyPair().getPublic(), secretKey))
                    .toString(16);
            Cipher decryptCipher = Crypt.getCipher(2, secretKey);
            Cipher encryptCipher = Crypt.getCipher(1, secretKey);
            this.connection.setEncryptionKey(decryptCipher, encryptCipher);
            name = Objects.requireNonNull(this.requestedUsername, "Player name not initialized");
            remote = this.connection.getRemoteAddress() instanceof InetSocketAddress addr ? addr : null;
        } catch (Exception e) {
            throw new IllegalStateException("Protocol error", e);
        }

        // 验证线程委托 master（LOGIN_VERIFY 流；阻塞等 RES）
        Thread thread = new Thread(() -> {
            SlaveRuntime runtime = SlaveRuntime.current();
            if (runtime == null) {
                this.icbm_slave$reject();
                return;
            }
            Messages.LoginVerifyRes res = runtime.loginVerifyGate().verify(
                    name, this.icbm_slave$clientProfileId, digest, sharedSecret, remote);
            if (res != null && res.verified() && res.identity() != null) {
                Messages.MasterVerifiedIdentity identity = res.identity();
                UUID uuid = uuidFromBytes(identity.playerUuid());
                this.icbm_slave$accept(new GameProfile(uuid, name), res);
            } else {
                String reason = res == null ? "master 未响应" : res.reason();
                LOGGER.warn("[icbm-slave] 登录验证拒绝 {}: {}", name, reason);
                this.icbm_slave$reject();
            }
        }, "icbm-slave-auth");
        thread.setDaemon(true);
        thread.start();
        ci.cancel();
    }

    @Unique
    private void icbm_slave$accept(GameProfile profile, Messages.LoginVerifyRes res) {
        this.startClientVerification(profile); // 官方流程继续（VERIFYING → 配置阶段）
        LOGGER.info("[icbm-slave] 登录验证通过: {} session={}", profile.name(),
                res.identity() == null ? "?" : res.identity().sessionId());
    }

    @Unique
    private void icbm_slave$reject() {
        try {
            this.connection.send(new net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket(
                    Component.translatable("multiplayer.disconnect.unverified_username")));
            this.connection.disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
        } catch (Exception e) {
            LOGGER.error("[icbm-slave] 拒绝登录断开失败: {}", e.toString());
        }
    }

    /** 16 字节定长 UUID → java.util.UUID（与 protocol 模块字节序一致）。 */
    @Unique
    private static UUID uuidFromBytes(byte[] bytes) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFFL);
            lsb = (lsb << 8) | (bytes[8 + i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }
}

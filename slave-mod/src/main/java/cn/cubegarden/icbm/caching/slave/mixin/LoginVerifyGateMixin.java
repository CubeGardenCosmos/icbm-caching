package cn.cubegarden.icbm.caching.slave.mixin;

import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import com.mojang.authlib.GameProfile;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
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
 * 登录握手透传（CACHE_PROTOCOL §9 重构：RSA 密钥对在 master，slave 只透传握手，借
 * Mojang authlib 验证）：Mixin {@code ServerLoginPacketListenerImpl}
 * —— handleHello 完整接管：向 master 递交登录开始（LOGIN_VERIFY_REQ，阻塞等 RES）拿回
 * 每登录 challenge + master 公钥，用 master 公钥构造 ClientboundHelloPacket 发客户端
 * （26.3 ServerLoginPacketListenerImpl.java:125-127 对照：state→KEY + 发加密请求）；
 * —— handleKey 完整接管：把客户端加密响应（ServerboundKeyPacket 原样 keybytes/
 * encryptedChallenge，经 {@link ServerboundKeyPacketAccessor}）递交给 master
 * （LOGIN_KEY_REQ，阻塞等 RES；在登录线程外，不阻塞主线程 tick）；verified=true →
 * 以 sharedSecret 建 AES/CFB8 Cipher（Crypt.getCipher 对照）设加密、按断言构造
 * GameProfile（皮肤属性随断言）→ startClientVerification 继续官方流程（VERIFYING →
 * 配置阶段）；拒绝 → disconnect（fail closed，不猜测兼容身份）。
 *
 * <p>Mixin 目标缺失自检见 {@link cn.cubegarden.icbm.caching.slave.selfcheck.MixinTargetVerifier}。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginVerifyGateMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginVerifyGateMixin.class);

    @Shadow
    @Final
    private Connection connection;

    @Shadow
    private String requestedUsername;

    @Shadow
    private void startClientVerification(GameProfile profile) {
        throw new AssertionError("shadow");
    }

    @Unique
    private UUID icbm_slave$clientProfileId = UUID.randomUUID();

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$requestChallenge(ServerboundHelloPacket packet, CallbackInfo ci) {
        this.requestedUsername = packet.name();
        this.icbm_slave$clientProfileId = packet.profileId();
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            this.icbm_slave$reject();
            ci.cancel();
            return;
        }
        InetSocketAddress remote = this.connection.getRemoteAddress() instanceof InetSocketAddress addr ? addr : null;
        // 阶段 A：master 发挑战 + 公钥（阻塞等 RES；登录线程，不阻塞主线程 tick）
        Messages.LoginVerifyRes res = runtime.loginVerifyGate()
                .requestChallenge(packet.name(), packet.profileId(), remote);
        if (res == null || !res.ok()) {
            String reason = res == null ? "master 未响应" : res.reason();
            LOGGER.warn("[icbm-slave] 登录挑战被拒 {}: {}", packet.name(), reason);
            this.icbm_slave$reject();
            ci.cancel();
            return;
        }
        // 官方 handleHello 语义（26.3 ServerLoginPacketListenerImpl.java:125-127）：
        // 发加密请求（serverId=""、master 公钥、master challenge）；state 由官方
        // handleKey 的 validState 校验，本注入在 HEAD 且 cancel 官方逻辑，无需写 state
        this.connection.send(new ClientboundHelloPacket("", res.publicKeyDer(), res.challenge(), true));
        ci.cancel();
    }

    @Inject(method = "handleKey", at = @At("HEAD"), cancellable = true)
    private void icbm_slave$delegateKey(ServerboundKeyPacket packet, CallbackInfo ci) {
        // 协议状态由官方 handleKey 的 Validate.validState 校验（本注入在 HEAD，官方逻辑被 cancel）
        final String name = Objects.requireNonNull(this.requestedUsername, "Player name not initialized");
        final byte[] keybytes = ((ServerboundKeyPacketAccessor) packet).icbm_slave$keybytes();
        final byte[] encryptedChallenge = ((ServerboundKeyPacketAccessor) packet).icbm_slave$encryptedChallenge();

        // 验证线程委托 master（LOGIN_KEY_REQ；阻塞等 RES，不阻塞主线程 tick）
        Thread thread = new Thread(() -> {
            SlaveRuntime runtime = SlaveRuntime.current();
            if (runtime == null) {
                this.icbm_slave$reject();
                return;
            }
            Messages.LoginKeyRes res = runtime.loginVerifyGate()
                    .submitKey(this.icbm_slave$clientProfileId, keybytes, encryptedChallenge);
            if (res != null && res.verified() && res.sharedSecret().length == 16 && res.identity() != null) {
                this.icbm_slave$accept(res);
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
    private void icbm_slave$accept(Messages.LoginKeyRes res) {
        try {
            // 26.3 Crypt.getCipher 对照：AES/CFB8/NoPadding，IV = 密钥本身
            SecretKey secretKey = new SecretKeySpec(res.sharedSecret(), "AES");
            Cipher decryptCipher = Crypt.getCipher(2, secretKey);
            Cipher encryptCipher = Crypt.getCipher(1, secretKey);
            this.connection.setEncryptionKey(decryptCipher, encryptCipher);
            Messages.MasterVerifiedIdentity identity = res.identity();
            UUID uuid = uuidFromBytes(identity.playerUuid());
            GameProfile profile = new GameProfile(uuid, this.requestedUsername);
            for (Messages.ProfileProperty property : identity.profileProperties()) {
                // 26.3 authlib 10.0.76：GameProfile 为 record，properties() 访问
                profile.properties().put(property.name(),
                        new com.mojang.authlib.properties.Property(
                                property.name(), property.value(), property.signature()));
            }
            this.startClientVerification(profile); // 官方流程继续（VERIFYING → 配置阶段）
            LOGGER.info("[icbm-slave] 登录验证通过: {} session={}", profile.name(), identity.sessionId());
            SlaveRuntime runtime = SlaveRuntime.current();
            if (runtime != null) {
                runtime.loginVerifyGate().recordSession(uuid, identity.sessionId());
            }
        } catch (Exception e) {
            LOGGER.error("[icbm-slave] 建立会话密钥失败（fail closed，拒登录）: {}", e.toString());
            this.icbm_slave$reject();
        }
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

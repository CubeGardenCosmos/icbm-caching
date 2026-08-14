package cn.cubegarden.icbm.caching.virtual.session;

import cn.cubegarden.icbm.caching.virtual.net.ClientConnection;
import cn.cubegarden.icbm.caching.virtual.net.PacketIds;
import cn.cubegarden.icbm.caching.virtual.net.ProtocolState;
import cn.cubegarden.icbm.caching.virtual.net.SessionHandler;
import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LOGIN 会话（net/minecraft/server/network/ServerLoginPacketListenerImpl.java 对照）：
 * 26.3 offline 登录：ServerboundHelloPacket(name, profileId) → 校验名字 →
 * LoginCompression(256) → LoginFinished(offline profile, sessionId) →
 * ServerboundLoginAcknowledged → CONFIGURATION。
 *
 * <p>阶段3 无 Auth Gateway：offline 模式（离线 UUID = 名字哈希，vanilla
 * {@code UUIDUtil.createOfflineProfile} 同构）；Auth Gateway 接入为阶段0/4 扩展点。
 */
public final class LoginSession implements SessionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginSession.class);
    private static final int COMPRESSION_THRESHOLD = 256;

    private enum State {
        HELLO, PROTOCOL_SWITCHING,
    }

    private final ClientConnection connection;
    private final SessionFactory factory;
    private State state = State.HELLO;
    private String playerName;
    private UUID playerId;

    public interface SessionFactory {
        SessionHandler newSession(ProtocolState state, ClientConnection connection);
    }

    public LoginSession(ClientConnection connection, SessionFactory factory) {
        this.connection = connection;
        this.factory = factory;
    }

    @Override
    public boolean handle(int packetId, PacketBuf body) {
        switch (packetId) {
            case PacketIds.SERVERBOUND_HELLO -> {
                if (this.state != State.HELLO) {
                    this.connection.disconnect("unexpected hello");
                    return true;
                }
                this.playerName = body.readUtf();
                this.playerId = body.readUUID();
                if (!isValidPlayerName(this.playerName)) {
                    this.connection.disconnect("非法玩家名: " + this.playerName);
                    return true;
                }
                this.sendLoginFinished();
                this.state = State.PROTOCOL_SWITCHING;
                return true;
            }
            case PacketIds.SERVERBOUND_LOGIN_ACKNOWLEDGED -> {
                if (this.state != State.PROTOCOL_SWITCHING) {
                    this.connection.disconnect("unexpected login acknowledged");
                    return true;
                }
                this.connection.setupState(ProtocolState.CONFIGURATION,
                        this.factory.newSession(ProtocolState.CONFIGURATION, this.connection));
                return true;
            }
            default -> {
                // custom query answer / cookie response 等：忽略
                LOGGER.debug("[vb] 登录阶段忽略包 id={}", packetId);
                return true;
            }
        }
    }

    private void sendLoginFinished() {
        // 1) LoginCompression（发送后启用入站解压与出站压缩）
        PacketBuf compression = new PacketBuf();
        compression.writeVarInt(COMPRESSION_THRESHOLD);
        this.connection.send(PacketIds.CLIENTBOUND_LOGIN_COMPRESSION, compression);
        this.connection.setupCompression(COMPRESSION_THRESHOLD);

        // 2) LoginFinished(GameProfile(UUID, name, 无属性), sessionId)
        UUID offlineUuid = createOfflineUuid(this.playerName);
        PacketBuf finished = new PacketBuf();
        finished.writeUUID(offlineUuid);
        finished.writeUtf(this.playerName);
        finished.writeVarInt(0); // profile properties 空
        finished.writeUUID(UUID.randomUUID()); // sessionId
        this.connection.send(PacketIds.CLIENTBOUND_LOGIN_FINISHED, finished);
        LOGGER.info("[vb] 登录: {} ({})", this.playerName, offlineUuid);
    }

    /** vanilla UUIDUtil.createOfflineProfile 同构（名字 → 离线 UUID）。 */
    static UUID createOfflineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** vanilla StringUtil.isValidPlayerName 同构（1-16 位字母数字下划线）。 */
    static boolean isValidPlayerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    public String playerName() {
        return this.playerName;
    }

    public UUID playerId() {
        return this.playerId;
    }
}

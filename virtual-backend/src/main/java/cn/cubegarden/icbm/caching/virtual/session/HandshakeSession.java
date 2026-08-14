package cn.cubegarden.icbm.caching.virtual.session;

import cn.cubegarden.icbm.caching.virtual.config.VirtualBackendConfig;
import cn.cubegarden.icbm.caching.virtual.net.ClientConnection;
import cn.cubegarden.icbm.caching.virtual.net.PacketIds;
import cn.cubegarden.icbm.caching.virtual.net.ProtocolState;
import cn.cubegarden.icbm.caching.virtual.net.SessionHandler;
import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HANDSHAKING 会话（net/minecraft/server/network/ServerHandshakePacketListenerImpl.java 对照）：
 * 读 ClientIntentionPacket（protocolVersion/host/port/intention），校验协议版本
 * （非冻结版本确定性拒绝，AGENTS.md），按 intention 切到 STATUS 或 LOGIN。
 */
public final class HandshakeSession implements SessionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandshakeSession.class);

    private static final int INTENTION_STATUS = 1;
    private static final int INTENTION_LOGIN = 2;

    private final ClientConnection connection;
    private final VirtualBackendConfig config;
    private final SessionFactory factory;

    public interface SessionFactory {
        SessionHandler newSession(ProtocolState state, ClientConnection connection);
    }

    public HandshakeSession(ClientConnection connection, VirtualBackendConfig config, SessionFactory factory) {
        this.connection = connection;
        this.config = config;
        this.factory = factory;
    }

    @Override
    public boolean handle(int packetId, PacketBuf body) {
        if (packetId != PacketIds.SERVERBOUND_INTENTION) {
            this.connection.disconnect("unexpected packet in handshake: " + packetId);
            return true;
        }
        int protocolVersion = body.readVarInt();
        String host = body.readUtf();
        int port = body.readUnsignedShort();
        int intention = body.readVarInt();
        LOGGER.debug("[vb] 握手: protocol={} host={} port={} intention={}",
                protocolVersion, host, port, intention);
        if (protocolVersion != this.config.runtimeProtocol()) {
            this.connection.disconnect("不支持的协议版本 " + protocolVersion
                    + "（冻结 26.3-snapshot-8 = " + this.config.runtimeProtocol() + "）");
            return true;
        }
        switch (intention) {
            case INTENTION_STATUS -> this.connection.setupState(ProtocolState.STATUS,
                    this.factory.newSession(ProtocolState.STATUS, this.connection));
            case INTENTION_LOGIN -> this.connection.setupState(ProtocolState.LOGIN,
                    this.factory.newSession(ProtocolState.LOGIN, this.connection));
            default -> this.connection.disconnect("未知 intention: " + intention);
        }
        return true;
    }
}

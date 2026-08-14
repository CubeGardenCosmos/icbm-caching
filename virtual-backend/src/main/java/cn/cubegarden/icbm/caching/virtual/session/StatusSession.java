package cn.cubegarden.icbm.caching.virtual.session;

import cn.cubegarden.icbm.caching.virtual.config.VirtualBackendConfig;
import cn.cubegarden.icbm.caching.virtual.net.ClientConnection;
import cn.cubegarden.icbm.caching.virtual.net.PacketIds;
import cn.cubegarden.icbm.caching.virtual.net.SessionHandler;
import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;

/**
 * STATUS 会话：服务器列表 ping（最小实现：版本/协议/描述/在线数 + pong 回显）。
 */
public final class StatusSession implements SessionHandler {

    private final ClientConnection connection;
    private final VirtualBackendConfig config;

    public StatusSession(ClientConnection connection, VirtualBackendConfig config) {
        this.connection = connection;
        this.config = config;
    }

    @Override
    public boolean handle(int packetId, PacketBuf body) {
        switch (packetId) {
            case PacketIds.SERVERBOUND_STATUS_REQUEST -> {
                String status = "{\"version\":{\"name\":\"" + this.config.minecraftVersion()
                        + "\",\"protocol\":" + this.config.runtimeProtocol()
                        + "},\"players\":{\"max\":0,\"online\":0,\"sample\":[]},"
                        + "\"description\":{\"text\":\"ICBM Virtual Backend (spectator cache)\"}}";
                PacketBuf out = new PacketBuf();
                out.writeUtf(status);
                this.connection.send(PacketIds.CLIENTBOUND_STATUS_RESPONSE, out);
                return true;
            }
            case PacketIds.SERVERBOUND_PING_REQUEST -> {
                long payload = body.readLong();
                PacketBuf out = new PacketBuf();
                out.writeLong(payload);
                this.connection.send(PacketIds.CLIENTBOUND_PONG_RESPONSE, out);
                this.connection.disconnect("ping 完成");
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}

package cn.cubegarden.icbm.caching.slave.passthrough;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权威会话 v2 直传处理（阶段 9.4；草稿 authoritative-session-v2 §3）：slave 侧互动/背包报文
 * 上行与源服权威下行注入。
 *
 * <p>上行：{@link #sendServerbound} 把 Serverbound 对象编码为 [packetId VarInt + fields]
 * （GameProtocols.SERVERBOUND_TEMPLATE codec，无长度——源服 Connection 管线加 framing）
 * 并组装 body v2 = [playerUuid(16)][流序号(8)][长度(4)][payload] 发 MC_PASSTHROUGH。
 *
 * <p>下行：{@link #onDownstream}（client netty 线程）解析 body → decode 为 Clientbound 对象
 * （GameProtocols.CLIENTBOUND_TEMPLATE codec）→ {@code player.connection.send(packet)}（走
 * 标准 outbound：编码 + 压缩 + VarInt 长度，不手动拼帧）；本地玩家 UUID 与 body 不一致
 * （串扰）或本地无玩家（e2e 无头）→ fail closed 丢弃。
 */
public final class PassthroughHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PassthroughHandler.class);
    private static final int BODY_HEADER = 16 + 8 + 4;

    /** play 协议 Context（GameProtocols.java:302）：非创造/非命令方块。 */
    private static final GameProtocols.Context DEFAULT_GAME_CONTEXT = new GameProtocols.Context() {
        @Override
        public boolean hasInfiniteMaterials() {
            return false;
        }

        @Override
        public boolean canUseCommandBlocks() {
            return false;
        }
    };

    private final SlaveControlClient client;
    private final AtomicLong streamSequence = new AtomicLong();

    public PassthroughHandler(SlaveControlClient client) {
        this.client = client;
    }

    /** 上行：serverbound 对象 → 字节 → MC_PASSTHROUGH（带玩家 UUID）。未连接 → false
     *  （调用方按 fail closed 回退：拒绝/本地回显撤销）。 */
    public boolean sendServerbound(ServerPlayer player, Packet<?> packet) {
        return this.sendServerbound(player.getUUID(), packet);
    }

    /** 上行（无头/调试：e2e RCON 模拟玩家用固定 UUID，与 operate 命令一致）。 */
    public boolean sendServerbound(UUID playerUuid, Packet<?> packet) {
        if (!this.client.connected()) {
            return false;
        }
        byte[] payload = encodeServerbound(packet);
        if (payload == null) {
            return false;
        }
        return this.client.sendMcPassthrough(wrapBody(playerUuid, payload));
    }

    /** 下行（client netty 线程）：body v2 → clientbound 对象 → 注入本地玩家连接。 */
    public void onDownstream(byte[] body) {
        if (body.length < BODY_HEADER) {
            LOGGER.warn("[icbm-slave] 权威下行 body 过短（{} 字节，fail closed 丢弃）", body.length);
            return;
        }
        UUID playerUuid = new UUID(
                ByteBuffer.wrap(body, 0, 16).getLong(),
                ByteBuffer.wrap(body, 16, 8).getLong());
        int payloadLength = ByteBuffer.wrap(body, 24, 4).getInt();
        if (payloadLength < 0 || BODY_HEADER + payloadLength > body.length) {
            LOGGER.error("[icbm-slave] 权威下行长度越界（{}，fail closed 丢弃）", payloadLength);
            return;
        }
        MinecraftServer server = this.client.serverRef();
        if (server == null) {
            LOGGER.debug("[icbm-slave] 权威下行丢弃（服务器未就绪）");
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            LOGGER.debug("[icbm-slave] 权威下行丢弃（无本地玩家连接，e2e 无头）");
            return;
        }
        ServerPlayer player = players.get(0); // slave 单玩家边缘节点
        if (!player.getUUID().equals(playerUuid)) {
            LOGGER.warn("[icbm-slave] 权威下行 UUID 与本地玩家不符（串扰，fail closed 丢弃）: {}",
                    playerUuid);
            return;
        }
        byte[] payload = Arrays.copyOfRange(body, BODY_HEADER, BODY_HEADER + payloadLength);
        Packet<ClientGamePacketListener> packet = decodeClientbound(server, payload);
        if (packet == null) {
            return;
        }
        player.connection.send(packet);
        LOGGER.info("[icbm-slave] 权威下行注入玩家连接: {}", packet.getClass().getSimpleName());
    }

    private byte[] encodeServerbound(Packet<?> packet) {
        MinecraftServer server = this.client.serverRef();
        if (server == null) {
            return null;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            ((net.minecraft.network.codec.StreamCodec) GameProtocols.SERVERBOUND_TEMPLATE
                    .bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()), DEFAULT_GAME_CONTEXT)
                    .codec()).encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } catch (Exception e) {
            LOGGER.error("[icbm-slave] 上行编码失败（fail closed 丢弃）: {}", e.getMessage());
            return null;
        } finally {
            buf.release();
        }
    }

    private Packet<ClientGamePacketListener> decodeClientbound(MinecraftServer server, byte[] payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(payload), server.registryAccess());
        try {
            return (Packet<ClientGamePacketListener>) ((net.minecraft.network.codec.StreamCodec) GameProtocols.CLIENTBOUND_TEMPLATE
                    .bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()))
                    .codec()).decode(buf);
        } catch (Exception e) {
            LOGGER.error("[icbm-slave] 权威下行解码失败（fail closed 丢弃）: {}", e.getMessage());
            return null;
        } finally {
            buf.release();
        }
    }

    /** body v2 = [playerUuid(16)][流序号(8)][长度(4)][payload]（CACHE_PROTOCOL §10 v2）。 */
    private byte[] wrapBody(UUID playerUuid, byte[] payload) {
        byte[] body = new byte[BODY_HEADER + payload.length];
        ByteBuffer.wrap(body)
                .putLong(0, playerUuid.getMostSignificantBits())
                .putLong(8, playerUuid.getLeastSignificantBits())
                .putLong(16, this.streamSequence.incrementAndGet())
                .putInt(24, payload.length);
        System.arraycopy(payload, 0, body, BODY_HEADER, payload.length);
        return body;
    }
}

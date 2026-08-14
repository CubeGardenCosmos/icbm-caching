package cn.cubegarden.icbm.caching.virtual.net;

import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import java.util.zip.Deflater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每连接会话（net/minecraft/network/Connection.java + ServerConnectionListener 对照）：
 * 入站帧解码 → 按 {@link ProtocolState} 分发；出站包编码（id + payload）→ 可选压缩 → 帧。
 *
 * <p>一致性（fail closed）：
 * <ul>
 *   <li>未知/超限包、压缩违规 → 记录并断连，不猜测格式；</li>
 *   <li>状态切换走 {@link #setupState}（替换会话处理器并回调 onActivated）；</li>
 *   <li>断线/异常 → {@link SessionHandler#onDisconnect} 清理（玩家位置/已加载区块）。</li>
 * </ul>
 */
public final class ClientConnection extends io.netty.channel.ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConnection.class);
    private static final int MAX_PACKET_SIZE = 8388608;
    private static final int MAX_FRAME_LENGTH = 0x1FFFFF; // 3 字节 VarInt 上限

    private ChannelHandlerContext ctx;
    private ProtocolState state = ProtocolState.HANDSHAKE;
    private SessionHandler session;
    private int compressionThreshold = -1;
    private final Deflater deflater = new Deflater();
    private String disconnectReason;

    private final GameServer server;

    public ClientConnection(GameServer server) {
        this.server = server;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /** 状态切换：替换会话处理器并激活。 */
    public void setupState(ProtocolState newState, SessionHandler newSession) {
        this.state = newState;
        SessionHandler previous = this.session;
        this.session = newSession;
        if (previous != null) {
            previous.onDisconnect("state switch");
        }
        newSession.onActivated();
    }

    public ProtocolState state() {
        return this.state;
    }

    public SessionHandler session() {
        return this.session;
    }

    /** 登录阶段启用压缩（ClientboundLoginCompressionPacket 后）。 */
    public void setupCompression(int threshold) {
        this.compressionThreshold = threshold;
        if (this.ctx != null && this.ctx.pipeline().get("decompress") == null) {
            // 入站解压插在帧切分之后、会话之前（官方 setupCompression 同位置）
            this.ctx.pipeline().addAfter("splitter", "decompress", new DecompressionHandler(threshold));
        }
    }

    public boolean compressionEnabled() {
        return this.compressionThreshold >= 0;
    }

    public int compressionThreshold() {
        return this.compressionThreshold;
    }

    public boolean isConnected() {
        return this.ctx != null && this.ctx.channel().isActive();
    }

    /** 测试/服务层访问 netty 上下文。 */
    public ChannelHandlerContext ctx() {
        return this.ctx;
    }

    /** 发送出站包（编码 → 压缩 → 成帧后写入管道）。 */
    public void send(OutboundPacket packet) {
        if (this.ctx == null || !this.ctx.channel().isActive()) {
            return;
        }
        this.ctx.writeAndFlush(this.encodeFrame(packet));
    }

    public void send(int packetId, PacketBuf payload) {
        this.send(OutboundPacket.of(packetId, payload));
    }

    /** 断连（带原因，记录并关闭）。 */
    public void disconnect(String reason) {
        this.disconnectReason = reason;
        if (this.ctx != null) {
            this.ctx.close();
        }
    }

    public String disconnectReason() {
        return this.disconnectReason;
    }

    // ---- 入站 ----

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf frame = (ByteBuf) msg;
        try {
            if (!frame.isReadable()) {
                return;
            }
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            PacketBuf buf = PacketBuf.wrap(bytes);
            int packetId = buf.readVarInt();
            if (this.session == null) {
                throw new IllegalStateException("packet before session: " + packetId);
            }
            boolean handled = this.session.handle(packetId, buf);
            if (!handled && this.state == ProtocolState.PLAY) {
                // 未知 play 包：记录（不主动断连，避免误伤合法客户端包）
                LOGGER.debug("[vb] 未处理 play 包 id={}", packetId);
            }
        } catch (Exception e) {
            LOGGER.warn("[vb] 入站处理失败，断连: {}", e.toString());
            this.disconnect("protocol error: " + e.getMessage());
        } finally {
            frame.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warn("[vb] 连接异常: {}", cause.toString());
        this.notifyDisconnect(cause.toString());
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.notifyDisconnect("disconnected");
    }

    private void notifyDisconnect(String reason) {
        if (this.disconnectReason != null) {
            reason = this.disconnectReason;
        }
        if (this.session != null) {
            this.session.onDisconnect(reason);
            this.session = null;
        }
        this.server.onDisconnected(this);
    }

    /** 编码：id + payload → 可选压缩 → VarInt 帧。 */
    private ByteBuf encodeFrame(OutboundPacket packet) {
        ByteBuf frame = this.ctx.alloc().buffer();
        byte[] payload = packet.payload();
        int idSize = VarIntSize.sizeOf(packet.packetId());
        int totalLength = idSize + payload.length;
        if (totalLength > MAX_PACKET_SIZE) {
            throw new EncoderException("Packet too big: " + totalLength);
        }
        ByteBuf packetBuf = this.ctx.alloc().buffer(totalLength);
        VarInt.write(packetBuf, packet.packetId());
        packetBuf.writeBytes(payload);
        byte[] packetBytes = new byte[totalLength];
        packetBuf.readBytes(packetBytes);
        packetBuf.release();

        if (this.compressionThreshold >= 0 && packetBytes.length >= this.compressionThreshold) {
            byte[] compressed = new byte[packetBytes.length + 64];
            this.deflater.setInput(packetBytes);
            this.deflater.finish();
            int written = 0;
            while (!this.deflater.finished()) {
                written += this.deflater.deflate(compressed, written, compressed.length - written);
            }
            this.deflater.reset();
            VarInt.write(frame, packetBytes.length);
            frame.writeBytes(compressed, 0, written);
        } else {
            VarInt.write(frame, 0);
            frame.writeBytes(packetBytes);
        }
        if (frame.readableBytes() > MAX_FRAME_LENGTH) {
            throw new EncoderException("Frame too big: " + frame.readableBytes());
        }
        ByteBuf wire = this.ctx.alloc().buffer(frame.readableBytes() + 3);
        VarInt.write(wire, frame.readableBytes());
        wire.writeBytes(frame);
        frame.release();
        return wire;
    }

    /** 计算 VarInt 字节数的小工具。 */
    private static final class VarIntSize {
        private static int sizeOf(int value) {
            int size = 1;
            while ((value & ~0x7F) != 0) {
                value >>>= 7;
                size++;
            }
            return size;
        }
    }
}

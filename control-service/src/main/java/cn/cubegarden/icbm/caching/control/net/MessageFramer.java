package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.List;

/**
 * 控制通道帧编解码（netty pipeline，草稿 phase2-control-protocol §3）：
 * 入站 {@code LengthFieldBasedFrameDecoder(4字节长度)} → {@link MessageDecoder}；
 * 出站 {@link MessageEncoder} → {@code LengthFieldPrepender(4)}。
 *
 * <p>帧/消息大小在帧层按配置上限拦截（对应 CACHE_PROTOCOL「请求大小限制、解压后大小限制」）。
 */
public final class MessageFramer {

    private MessageFramer() {
    }

    /** 入站帧切分器：4 字节大端长度前缀，剥离后交给解码器；超限直接丢弃连接（fail closed）。 */
    public static LengthFieldBasedFrameDecoder inboundFrameDecoder(int maxFrameBytes) {
        return new LengthFieldBasedFrameDecoder(maxFrameBytes + 4, 0, 4, 0, 4);
    }

    /** 出站长度前缀（与 {@link #inboundFrameDecoder} 对称）。 */
    public static LengthFieldPrepender outboundFramePrepender() {
        return new LengthFieldPrepender(4);
    }

    /** 单帧 → {@link Envelope}；任何解析错误抛异常由上层断连。 */
    public static final class MessageDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            byte[] frame = new byte[in.readableBytes()];
            in.readBytes(frame);
            out.add(FrameCodec.decodeFrame(frame));
        }
    }

    /** {@link Envelope} → 帧字节。 */
    public static final class MessageEncoder extends MessageToByteEncoder<Envelope> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Envelope msg, ByteBuf out) {
            out.writeBytes(FrameCodec.encodeFrame(msg));
        }
    }
}

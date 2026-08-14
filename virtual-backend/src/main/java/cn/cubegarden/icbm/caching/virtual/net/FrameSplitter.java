package cn.cubegarden.icbm.caching.virtual.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * 26.3 Varint21FrameDecoder 重实现：3 字节 VarInt 长度前缀帧切分（
 * net/minecraft/network/Varint21FrameDecoder.java 对照）。
 *
 * <p>长度 0 → CorruptedFrameException（fail closed）；未收齐时保留累积等待。
 */
public final class FrameSplitter extends ByteToMessageDecoder {

    private static final int MAX_FRAME_LENGTH = 3;

    private boolean readingLength = true;
    private int frameLength;
    private int varintAccum;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (this.readingLength) {
            int readable = in.readableBytes();
            if (readable < 1) {
                return;
            }
            this.varintAccum = 0;
            int maxBytes = Math.min(MAX_FRAME_LENGTH, readable);
            int consumed = 0;
            int length = 0;
            int shift = 0;
            boolean complete = false;
            while (consumed < maxBytes) {
                int b = in.readUnsignedByte();
                consumed++;
                length |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    complete = true;
                    break;
                }
                shift += 7;
                if (shift > 21) {
                    throw new io.netty.handler.codec.CorruptedFrameException("VarInt too big");
                }
            }
            if (!complete) {
                // 需要更多字节：回退已读
                in.readerIndex(in.readerIndex() - consumed);
                return;
            }
            if (length == 0) {
                throw new io.netty.handler.codec.CorruptedFrameException("Empty packet");
            }
            this.frameLength = length;
            this.readingLength = false;
        }
        if (in.readableBytes() < this.frameLength) {
            return;
        }
        out.add(in.readRetainedSlice(this.frameLength));
        this.readingLength = true;
    }
}

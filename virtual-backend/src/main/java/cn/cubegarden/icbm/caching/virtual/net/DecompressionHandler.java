package cn.cubegarden.icbm.caching.virtual.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 26.3 CompressionDecoder 重实现（net/minecraft/network/CompressionDecoder.java 对照）：
 * 帧内容 = VarInt(uncompressedLength) + (0 → 原样 | n → zlib 解压到 n 字节)。
 *
 * <p>校验（fail closed）：解压后长度 &lt; 阈值或 &gt; 8 MiB → 抛异常断连。
 */
public final class DecompressionHandler extends ByteToMessageDecoder {

    private static final int MAX_DECOMPRESSED = 8388608;

    private final int threshold;
    private final Inflater inflater = new Inflater();

    public DecompressionHandler(int threshold) {
        this.threshold = threshold;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }
        int uncompressedLength = VarInt.read(in);
        if (uncompressedLength == 0) {
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }
        if (uncompressedLength < this.threshold || uncompressedLength > MAX_DECOMPRESSED) {
            throw new io.netty.handler.codec.DecoderException(
                    "Badly compressed packet - size of " + uncompressedLength
                            + " is outside [" + this.threshold + ", " + MAX_DECOMPRESSED + "]");
        }
        byte[] compressed = new byte[in.readableBytes()];
        in.readBytes(compressed);
        this.inflater.setInput(compressed);
        byte[] output = new byte[uncompressedLength];
        try {
            int written = this.inflater.inflate(output);
            if (written != uncompressedLength) {
                throw new io.netty.handler.codec.DecoderException("bad length: " + written + " != " + uncompressedLength);
            }
        } catch (DataFormatException e) {
            throw new io.netty.handler.codec.DecoderException("Badly compressed packet", e);
        } finally {
            this.inflater.reset();
        }
        out.add(Unpooled.wrappedBuffer(output));
    }
}

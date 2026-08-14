package cn.cubegarden.icbm.caching.virtual.net;

import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;

/** 出站游戏包（包 ID + payload；编码/压缩/成帧由连接层完成）。 */
public record OutboundPacket(int packetId, byte[] payload) {

    public static OutboundPacket of(int packetId, PacketBuf payload) {
        return new OutboundPacket(packetId, payload.toByteArray());
    }
}

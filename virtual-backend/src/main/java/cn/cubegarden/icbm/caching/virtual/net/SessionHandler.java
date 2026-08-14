package cn.cubegarden.icbm.caching.virtual.net;

import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;

/**
 * 会话处理器（Velocity MinecraftSessionHandler 对照）：每连接在特定 {@link ProtocolState}
 * 下的入站包分发与生命周期回调。
 */
public interface SessionHandler {

    /** 会话已激活（状态切换完成后调用）。 */
    default void onActivated() {
    }

    /** 处理入站包；返回 false 表示未知/未处理（由连接层记录并继续，不主动断连）。 */
    boolean handle(int packetId, PacketBuf body);

    /** 连接关闭/异常时清理（断线清理）。 */
    default void onDisconnect(String reason) {
    }
}

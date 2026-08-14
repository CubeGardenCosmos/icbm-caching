package cn.cubegarden.icbm.caching.virtual.net;

/** 26.3 连接状态机（net/minecraft/network/ConnectionProtocol.java 对照）。 */
public enum ProtocolState {
    HANDSHAKE,
    STATUS,
    LOGIN,
    CONFIGURATION,
    PLAY,
}

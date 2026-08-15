package cn.cubegarden.icbm.caching.control.routing;

/**
 * 源服 backend 健康状态（落点判定输入）：连接是否存活 + 最近事件/心跳时间戳。
 * 由 SourceConnector 接线（连接建立/断开/收到事件时更新）；健康 = 连接存活且
 * 最近活动未超时（源服无玩家时心跳保活，30s 心跳 + 阈值）。
 */
public final class BackendHealth {

    private volatile boolean connected;
    private volatile long lastActivityMillis;

    public void markConnected(long nowMillis) {
        this.connected = true;
        this.lastActivityMillis = nowMillis;
    }

    public void markActivity(long nowMillis) {
        this.lastActivityMillis = nowMillis;
    }

    public void markDisconnected() {
        this.connected = false;
    }

    public boolean healthy(long nowMillis, long staleThresholdMillis) {
        return this.connected && (nowMillis - this.lastActivityMillis) < staleThresholdMillis;
    }
}

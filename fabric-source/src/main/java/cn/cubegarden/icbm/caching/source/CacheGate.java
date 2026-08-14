package cn.cubegarden.icbm.caching.source;

/**
 * 缓存命中闸门：Mixin 目标自检失败或任何不可恢复错误时 fail closed。
 *
 * <p>对应 ARCHITECTURE.md「任何目标缺失都要在启动时禁用缓存命中，而不是静默降级」
 * 与 CACHE_PROTOCOL.md「校验失败时关闭缓存命中或操作通道」。
 */
public final class CacheGate {

    private volatile boolean enabled = true;
    private volatile String disabledReason = "";

    public boolean isEnabled() {
        return this.enabled;
    }

    /** 返回当前禁用原因（启用时为空字符串）。 */
    public String disabledReason() {
        return this.disabledReason;
    }

    /** 关闭缓存命中（fail closed）。重复调用保留第一次原因。 */
    public void disable(String reason) {
        if (this.enabled) {
            this.disabledReason = reason;
            this.enabled = false;
        }
    }
}

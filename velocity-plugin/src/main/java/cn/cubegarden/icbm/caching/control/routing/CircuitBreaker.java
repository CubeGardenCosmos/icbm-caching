package cn.cubegarden.icbm.caching.control.routing;

/**
 * 源服断路器（后端故障 fallback 验收）：连续失败达到阈值 → OPEN（权威会话请求
 * fallback 到缓存会话/拒绝）；OPEN 后经冷却 → HALF_OPEN（试探一次）；成功 → CLOSED，
 * 失败 → 重新 OPEN。
 *
 * <p>无全局可变状态：实例由 master 装配，供 RoutingPolicy 只读调用。
 */
public final class CircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final long openTimeoutMillis;
    private final Object lock = new Object();

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtMillis;

    public CircuitBreaker(int failureThreshold, long openTimeoutMillis) {
        this.failureThreshold = failureThreshold;
        this.openTimeoutMillis = openTimeoutMillis;
    }

    public State state(long nowMillis) {
        synchronized (this.lock) {
            if (this.state == State.OPEN && (nowMillis - this.openedAtMillis) >= this.openTimeoutMillis) {
                this.state = State.HALF_OPEN;
            }
            return this.state;
        }
    }

    /** 允许尝试：CLOSED / HALF_OPEN 可发起权威会话；OPEN 拒绝。 */
    public boolean allow(long nowMillis) {
        return state(nowMillis) != State.OPEN;
    }

    public void recordFailure(long nowMillis) {
        synchronized (this.lock) {
            if (this.state == State.HALF_OPEN) {
                this.state = State.OPEN;
                this.openedAtMillis = nowMillis;
                return;
            }
            this.consecutiveFailures++;
            if (this.consecutiveFailures >= this.failureThreshold) {
                this.state = State.OPEN;
                this.openedAtMillis = nowMillis;
                this.consecutiveFailures = 0;
            }
        }
    }

    public void recordSuccess() {
        synchronized (this.lock) {
            this.state = State.CLOSED;
            this.consecutiveFailures = 0;
        }
    }
}

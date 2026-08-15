package cn.cubegarden.icbm.caching.control.routing;

import java.util.List;

/**
 * 落点判定（master 唯一决策者；HIERARCHY §3.1）：给定玩家请求 → 会话模式
 * （CACHE / AUTHORITATIVE / REJECT）。
 *
 * <p>规则：
 * <ul>
 *   <li>serverName 不在白名单（配置非空时）→ REJECT（白名单）；</li>
 *   <li>请求权威会话（requestedMode=AUTHORITATIVE）但源服不健康或断路器 OPEN →
 *       fallback CACHE（后端故障 fallback 验收；缓存会话由 slave 对象库 + fail closed 兜底）；</li>
 *   <li>其余 → 按请求模式（默认 CACHE）。</li>
 * </ul>
 *
 * <p>模式切换（缓存 ↔ 权威）在会话内由 slave 侧发起 MC_PASSTHROUGH 流决定：master 以
 * {@link #allowAuthoritative} 授权（健康 + 断路器 + 白名单）。
 */
public final class RoutingPolicy {

    public enum SessionMode {
        CACHE, AUTHORITATIVE, REJECT
    }

    /** 落点决策：目标 serverName + 会话模式（REJECT 时 serverName 为请求原值）。 */
    public record RoutingDecision(String serverName, SessionMode mode) {
    }

    private final List<String> allowedServerNames;
    private final BackendHealth backendHealth;
    private final CircuitBreaker circuitBreaker;
    private final long staleThresholdMillis;

    public RoutingPolicy(List<String> allowedServerNames, BackendHealth backendHealth,
                         CircuitBreaker circuitBreaker, long staleThresholdMillis) {
        this.allowedServerNames = allowedServerNames;
        this.backendHealth = backendHealth;
        this.circuitBreaker = circuitBreaker;
        this.staleThresholdMillis = staleThresholdMillis;
    }

    /** 落点判定（登录验证时调用）。 */
    public RoutingDecision decide(String serverName, SessionMode requested, long nowMillis) {
        if (!this.allowedServerNames.isEmpty() && !this.allowedServerNames.contains(serverName)) {
            return new RoutingDecision(serverName, SessionMode.REJECT);
        }
        if (requested == SessionMode.AUTHORITATIVE && !allowAuthoritative(nowMillis)) {
            return new RoutingDecision(serverName, SessionMode.CACHE); // 后端故障 fallback
        }
        return new RoutingDecision(serverName, requested);
    }

    /** 权威会话授权（MC_PASSTHROUGH 流建立时调用）。 */
    public boolean allowAuthoritative(long nowMillis) {
        return this.backendHealth.healthy(nowMillis, this.staleThresholdMillis)
                && this.circuitBreaker.allow(nowMillis);
    }
}

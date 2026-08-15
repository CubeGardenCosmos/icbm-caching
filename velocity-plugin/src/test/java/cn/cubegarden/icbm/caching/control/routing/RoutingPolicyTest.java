package cn.cubegarden.icbm.caching.control.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 落点判定/健康/断路器：白名单、后端故障 fallback、断路器状态机（OPEN → HALF_OPEN →
 * CLOSED）、健康判定（连接 + 活动时间戳）。
 */
class RoutingPolicyTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void whitelistRejectsUnknownServer() {
        BackendHealth health = new BackendHealth();
        health.markConnected(NOW);
        RoutingPolicy policy = new RoutingPolicy(List.of("lobby-1"), health, new CircuitBreaker(3, 30_000), 90_000);
        assertEquals(RoutingPolicy.SessionMode.REJECT,
                policy.decide("other", RoutingPolicy.SessionMode.CACHE, NOW).mode());
        assertEquals(RoutingPolicy.SessionMode.CACHE,
                policy.decide("lobby-1", RoutingPolicy.SessionMode.CACHE, NOW).mode());
    }

    @Test
    void authoritativeFallsBackToCacheWhenBackendDown() {
        BackendHealth health = new BackendHealth(); // 未连接
        RoutingPolicy policy = new RoutingPolicy(List.of(), health, new CircuitBreaker(3, 30_000), 90_000);
        assertEquals(RoutingPolicy.SessionMode.CACHE,
                policy.decide("lobby-1", RoutingPolicy.SessionMode.AUTHORITATIVE, NOW).mode(),
                "后端故障 → 权威请求 fallback 缓存会话");
    }

    @Test
    void authoritativeAllowedWhenBackendHealthy() {
        BackendHealth health = new BackendHealth();
        health.markConnected(NOW);
        RoutingPolicy policy = new RoutingPolicy(List.of(), health, new CircuitBreaker(3, 30_000), 90_000);
        assertEquals(RoutingPolicy.SessionMode.AUTHORITATIVE,
                policy.decide("lobby-1", RoutingPolicy.SessionMode.AUTHORITATIVE, NOW).mode());
        assertTrue(policy.allowAuthoritative(NOW));
    }

    @Test
    void staleBackendIsUnhealthy() {
        BackendHealth health = new BackendHealth();
        health.markConnected(NOW - 200_000); // 超过 90s 无活动
        RoutingPolicy policy = new RoutingPolicy(List.of(), health, new CircuitBreaker(3, 30_000), 90_000);
        assertFalse(policy.allowAuthoritative(NOW), "无活动超过阈值 → 不健康");
    }

    @Test
    void circuitBreakerOpensThenHalfOpensThenRecovers() {
        BackendHealth health = new BackendHealth();
        health.markConnected(NOW);
        CircuitBreaker breaker = new CircuitBreaker(3, 30_000);
        RoutingPolicy policy = new RoutingPolicy(List.of(), health, breaker, 90_000);

        assertTrue(policy.allowAuthoritative(NOW), "初始 CLOSED");
        breaker.recordFailure(NOW);
        breaker.recordFailure(NOW);
        breaker.recordFailure(NOW);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(NOW), "连续失败达阈值 → OPEN");
        assertFalse(policy.allowAuthoritative(NOW), "OPEN 拒绝权威会话");

        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(NOW + 31_000), "冷却后半开");
        assertTrue(policy.allowAuthoritative(NOW + 31_000), "半开放行试探");

        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(NOW + 32_000), "成功 → 恢复 CLOSED");
        assertTrue(policy.allowAuthoritative(NOW + 32_000));
    }
}

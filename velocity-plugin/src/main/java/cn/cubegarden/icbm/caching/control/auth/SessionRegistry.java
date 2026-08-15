package cn.cubegarden.icbm.caching.control.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局活跃会话记账（HIERARCHY §2：同一玩家 UUID 同时只允许一个活跃会话）。
 *
 * <p>里程碑 4 最小实现：登记/注销 + 重复会话拒新（返回 null）。Mojang 正版验证、
 * 身份断言签名/单次兑换/吊销随里程碑 5 Auth Gateway 完整化；本类为记账的单一所有权点
 * （无全局可变状态：实例由 master 装配时创建并注入）。
 */
public final class SessionRegistry {

    private final Map<String, String> active = new ConcurrentHashMap<>(); // playerUuidHex → sessionId
    private final AtomicLong counter = new AtomicLong();

    /** 登记玩家；已有活跃会话 → 返回 null（重复会话，拒新策略）。 */
    public String register(String playerUuidHex) {
        String sessionId = "sess-" + Long.toHexString(this.counter.incrementAndGet());
        String previous = this.active.putIfAbsent(playerUuidHex, sessionId);
        return previous == null ? sessionId : null;
    }

    /** 注销记账（SESSION_CLOSED）；sessionId 不匹配则忽略（防误注销新会话）。 */
    public void unregister(String playerUuidHex, String sessionId) {
        this.active.remove(playerUuidHex, sessionId);
    }

    public int activeCount() {
        return this.active.size();
    }
}

package cn.cubegarden.icbm.caching.control.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局活跃会话记账（HIERARCHY §2：同一玩家 UUID 同时只允许一个活跃会话）。
 *
 * <p>登记/注销 + 重复会话拒新（默认策略）+ 管理踢旧（kickOld）+ slave 连接断开
 * 批量注销（防 SESSION_CLOSED 未送达时的会话表泄漏）。每会话记录归属 slave
 * （slaveId = 落点 serverName），断线清理按 slave 维度。
 *
 * <p>无全局可变状态：实例由 master 装配时创建并注入。
 */
public final class SessionRegistry {

    /** playerUuidHex → (sessionId, slaveId)。 */
    private final Map<String, Entry> active = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong();

    private record Entry(String sessionId, String slaveId) {
    }

    /** 登记玩家（归属 slave）；已有活跃会话 → 返回 null（重复会话，拒新策略）。 */
    public String register(String playerUuidHex, String slaveId) {
        String sessionId = "sess-" + Long.toHexString(this.counter.incrementAndGet());
        Entry entry = new Entry(sessionId, slaveId);
        Entry previous = this.active.putIfAbsent(playerUuidHex, entry);
        return previous == null ? sessionId : null;
    }

    /** 踢旧（管理操作）：移除旧会话并返回其 sessionId；无活跃会话 → null。 */
    public String kickOld(String playerUuidHex) {
        Entry removed = this.active.remove(playerUuidHex);
        return removed == null ? null : removed.sessionId();
    }

    /** 注销记账（SESSION_CLOSED）；sessionId 不匹配则忽略（防误注销新会话）。 */
    public void unregister(String playerUuidHex, String sessionId) {
        Entry entry = this.active.get(playerUuidHex);
        if (entry != null && entry.sessionId().equals(sessionId)) {
            this.active.remove(playerUuidHex, entry);
        }
    }

    /** slave 连接断开：注销该 slave 的全部活跃会话（防泄漏）。返回注销数。 */
    public int unregisterAllBySlave(String slaveId) {
        int count = 0;
        for (Map.Entry<String, Entry> e : this.active.entrySet()) {
            if (e.getValue().slaveId().equals(slaveId)) {
                if (this.active.remove(e.getKey(), e.getValue())) {
                    count++;
                }
            }
        }
        return count;
    }

    public int activeCount() {
        return this.active.size();
    }
}

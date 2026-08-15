package cn.cubegarden.icbm.caching.control.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份断言单次兑换（HIERARCHY §2.2：nonce/sessionId 在 master 中只能兑换一次；
 * 验收「票据不重放」）。
 *
 * <p>每个验证请求的 nonce 登记为 ISSUED；同一 nonce 再次提交（重放）→ 拒绝。
 * sessionId 签发时登记，SESSION_CLOSED 注销或显式吊销。兑换状态在内存（重启清空 →
 * 旧断言受 expiresAt 短期过期约束，可接受，文档已记录）。
 */
public final class TicketVault {

    /** nonceHex 已兑换标记（一次兑换；重复提交拒绝）。有界近似 LRU（A9 审计修复：
     *  防 nonce 表无限增长；超限移除最旧——nonce 已消费，移除仅影响极迟的重放判定）。 */
    private final Map<String, Boolean> issued = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 65536;
                }
            });
    /** sessionId → 状态（sessionId 由 SessionRegistry 分配，本类只维护兑换状态）。 */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    /** sessionId → 断言过期时间（A9 审计修复：文档声称的过期清理补实现）。 */
    private final Map<String, Long> sessionExpiry = new ConcurrentHashMap<>();

    /** 兑换 nonce：首次 → true；重复（重放）→ false。 */
    public boolean redeem(String nonceHex) {
        synchronized (this.issued) {
            if (this.issued.containsKey(nonceHex)) {
                return false;
            }
            this.issued.put(nonceHex, Boolean.TRUE);
            return true;
        }
    }

    /** 登记 sessionId（验证通过后由 AuthGatewayListener 调用；记录断言过期时间）。 */
    public void activate(String sessionId, long expiresAtMillis) {
        this.sessions.put(sessionId, "ACTIVE");
        this.sessionExpiry.put(sessionId, expiresAtMillis);
    }

    /** 吊销（SESSION_CLOSED / 管理踢旧 / 断言过期清理）。 */
    public void revoke(String sessionId) {
        this.sessions.remove(sessionId);
        this.sessionExpiry.remove(sessionId);
    }

    /** 清理已过期断言（AuthGatewayListener 周期调用；防会话表泄漏，A9 审计修复）。 */
    public int purgeExpired(long nowMillis) {
        int count = 0;
        for (Map.Entry<String, Long> entry : this.sessionExpiry.entrySet()) {
            if (entry.getValue() < nowMillis) {
                if (this.sessions.remove(entry.getKey()) != null) {
                    count++;
                }
                this.sessionExpiry.remove(entry.getKey());
            }
        }
        return count;
    }

    public boolean isActive(String sessionId) {
        return this.sessions.containsKey(sessionId);
    }

    public int activeCount() {
        return this.sessions.size();
    }
}

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

    /** nonceHex 已兑换标记（一次兑换；重复提交拒绝）。 */
    private final Map<String, Boolean> issued = new ConcurrentHashMap<>();
    /** sessionId → 状态（sessionId 由 SessionRegistry 分配，本类只维护兑换状态）。 */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    /** 兑换 nonce：首次 → true；重复（重放）→ false。 */
    public boolean redeem(String nonceHex) {
        return this.issued.putIfAbsent(nonceHex, Boolean.TRUE) == null;
    }

    /** 登记 sessionId（验证通过后由 AuthGatewayListener 调用）。 */
    public void activate(String sessionId) {
        this.sessions.put(sessionId, "ACTIVE");
    }

    /** 吊销（SESSION_CLOSED / 管理踢旧 / 断言过期清理）。 */
    public void revoke(String sessionId) {
        this.sessions.remove(sessionId);
    }

    public boolean isActive(String sessionId) {
        return this.sessions.containsKey(sessionId);
    }

    public int activeCount() {
        return this.sessions.size();
    }
}

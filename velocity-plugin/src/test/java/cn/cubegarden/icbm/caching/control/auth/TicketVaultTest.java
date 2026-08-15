package cn.cubegarden.icbm.caching.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 票据单次兑换：nonce 首次兑换成功、同一 nonce 重放拒绝；sessionId 激活/吊销/状态。
 */
class TicketVaultTest {

    @Test
    void redeemOnceThenReplayRejected() {
        TicketVault vault = new TicketVault();
        assertTrue(vault.redeem("aabbccdd"), "首次兑换必须成功");
        assertFalse(vault.redeem("aabbccdd"), "同一 nonce 重放必须拒绝");
        assertTrue(vault.redeem("eeff0011"), "不同 nonce 可正常兑换");
    }

    @Test
    void activateRevokeLifecycle() {
        TicketVault vault = new TicketVault();
        assertTrue(vault.redeem("aabbccdd"));
        String sessionId = "sess-1";
        vault.activate(sessionId);
        assertTrue(vault.isActive(sessionId));
        assertEquals(1, vault.activeCount());

        vault.revoke(sessionId);
        assertFalse(vault.isActive(sessionId));
        assertEquals(0, vault.activeCount());
    }

    @Test
    void revokeUnknownSessionIsNoop() {
        TicketVault vault = new TicketVault();
        vault.revoke("sess-nope");
        assertEquals(0, vault.activeCount());
    }
}

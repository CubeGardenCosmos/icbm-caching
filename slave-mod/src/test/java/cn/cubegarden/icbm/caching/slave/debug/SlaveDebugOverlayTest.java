package cn.cubegarden.icbm.caching.slave.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SlaveDebugOverlay：/bmtool debug toggle 状态行内容（性能 TPS/内存、slave↔master
 * 通讯状态、slave UUID）与开关语义。onServerTick 依赖 Minecraft 运行时（MinecraftServer/
 * ServerPlayer），不做单元测试（e2e/手工验证覆盖）。
 */
class SlaveDebugOverlayTest {

    @Test
    void statusLineShowsTpsMemConnectionAndUuid() {
        // avgTickNanos=50_000_000 → TPS 20.0（官方 getAverageTickTimeNanos 语义：1e9/nanos）
        String line = SlaveDebugOverlay.statusLine(50_000_000L, 300L * 1024 * 1024,
                true, "1112131415161718191a1b1c1d1e1f20");
        assertTrue(line.contains("TPS 20.0"), line);
        assertTrue(line.contains("MEM 300MB"), line);
        assertTrue(line.contains("master 已连接"), line);
        assertTrue(line.contains("1112131415161718191a1b1c1d1e1f20"), line);
    }

    @Test
    void statusLineReflectsDisconnectedFailClosed() {
        // 通讯断开 → 显示断开（与 ServeGate fail closed 同一事实来源）
        String line = SlaveDebugOverlay.statusLine(0L, 0L, false, "abc");
        assertTrue(line.contains("master 断开"), line);
        assertTrue(line.contains("fail closed"), line);
    }

    @Test
    void statusLineHandlesZeroNanosAsTwentyTps() {
        // avgNanos<=0（启动初期）→ 20 TPS 起步，不除零
        String line = SlaveDebugOverlay.statusLine(0L, 0L, true, "uuid");
        assertTrue(line.contains("TPS 20.0"), line);
    }

    @Test
    void toggleFlipsEnabledState() {
        // client 仅在 onServerTick 使用；null 只用于开关语义单元测试
        SlaveDebugOverlay overlay = new SlaveDebugOverlay(null, "uuid");
        assertFalse(overlay.enabled());
        assertTrue(overlay.toggle());
        assertTrue(overlay.enabled());
        assertFalse(overlay.toggle());
        assertFalse(overlay.enabled());
    }
}

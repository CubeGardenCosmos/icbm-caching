package cn.cubegarden.icbm.caching.slave.debug;

import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * slave 调试提示 overlay（{@code /bmtool debug toggle}）：开启后以 action bar 向所有
 * 在线玩家输出状态行——性能（TPS/内存）、slave↔master 通讯状态、slave UUID。
 *
 * <p>官方做法对照（26.3 mojmap，build/workspace/minecraft-assets）：
 * <ul>
 *   <li>action bar = {@code ServerPlayer.sendOverlayMessage(Component)} →
 *       {@code ClientboundSystemChatPacket(overlay=true)}（ServerPlayer.java:1857-1880）；</li>
 *   <li>TPS = {@code MinecraftServer.getAverageTickTimeNanos()}（MinecraftServer.java:1798），
 *       TPS = 1e9 / avgNanos（avgNanos=50_000_000 → 20.0）；</li>
 *   <li>在线玩家 = {@code server.getPlayerList().getPlayers()}（PlayerList.java:827）。</li>
 * </ul>
 *
 * <p>通讯状态取封装通道 {@link SlaveControlClient#connected()}（与 ServeGate fail closed
 * 同一事实来源：断开即不展示不确定基线）。UUID 为配置 {@code control.uuid}（AUTH 身份，
 * master roleBindings 绑定 SLAVE）。每 10 tick（2Hz）发送一次，避免刷屏。
 */
public final class SlaveDebugOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlaveDebugOverlay.class);
    private static final int SEND_INTERVAL_TICKS = 10;

    private final SlaveControlClient client;
    private final String uuidHex;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private int ticksSinceLastSend;

    public SlaveDebugOverlay(SlaveControlClient client, String uuidHex) {
        this.client = client;
        this.uuidHex = uuidHex;
    }

    /** 切换开关；返回切换后的状态（true = 已开启，action bar 提示显示中）。 */
    public boolean toggle() {
        boolean now = !this.enabled.get();
        this.enabled.set(now);
        LOGGER.info("[icbm-slave] bmtool debug {}", now ? "开启（action bar 状态提示）" : "关闭");
        return now;
    }

    public boolean enabled() {
        return this.enabled.get();
    }

    /** 每 tick 调用（END_SERVER_TICK）：开启时按节流向所有在线玩家发 action bar 状态行。 */
    public void onServerTick(MinecraftServer server) {
        if (!this.enabled.get()) {
            return;
        }
        if (++this.ticksSinceLastSend < SEND_INTERVAL_TICKS) {
            return;
        }
        this.ticksSinceLastSend = 0;
        long usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        String line = statusLine(server.getAverageTickTimeNanos(), usedBytes,
                this.client.connected(), this.uuidHex);
        Component text = Component.literal(line);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendOverlayMessage(text); // 26.3 ServerPlayer.java:1857 action bar
        }
    }

    /**
     * 状态行纯函数（测试友好）：TPS（1e9/avgNanos）/ 内存 MB / 通讯状态 / UUID。
     *
     * @param avgTickNanos 官方 getAverageTickTimeNanos 值（<=0 视为 20 TPS 起步）
     * @param usedBytes    已用堆字节
     */
    static String statusLine(long avgTickNanos, long usedBytes, boolean connected, String uuidHex) {
        double tps = avgTickNanos <= 0 ? 20.0 : 1_000_000_000.0 / avgTickNanos;
        long usedMb = usedBytes / (1024 * 1024);
        return String.format(Locale.ROOT, "ICBM slave | TPS %.1f | MEM %dMB | %s | UUID %s",
                tps, usedMb, connected ? "master 已连接" : "master 断开（fail closed）", uuidHex);
    }
}

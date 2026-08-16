package cn.cubegarden.icbm.caching.control.bridge;

import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.net.SourceConnector;
import cn.cubegarden.icbm.caching.control.routing.CircuitBreaker;
import cn.cubegarden.icbm.caching.control.routing.RoutingPolicy;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权威会话桥 v2（阶段 9.4；ARCHITECTURE §3.2 / CACHE_PROTOCOL §10）：多会话按 playerUuid 路由。
 * MC_PASSTHROUGH body v2 = [playerUuid(16)][流序号(8)][长度(4)][明文 MC 报文]；slave 上行
 * （uuid 首次见登记路由）→ 源服 backend；源服下行按 uuid 回对应 slave 会话。
 *
 * <p>桥建立时经 {@link RoutingPolicy#allowAuthoritative} 授权（源服健康 + 断路器未 OPEN，
 * 后端故障 fallback：拒绝 → slave 继续缓存会话）。源服回包失败 → {@link CircuitBreaker#recordFailure}；
 * 成功 → recordSuccess。数据包缓存（PacketLog）字节级审计（方向/长度/SHA-256）保持。
 *
 * <p>v1 的单一活跃权威会话约束（并发 slave 拒绝）由 v2 多会话取代——每个真实玩家（playerUuid）
 * 在源服有独立虚拟玩家会话（VirtualPlayerSession），master 不做 26.3 包解析（字节透传 + uuid 路由）。
 */
public final class McPassthroughBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(McPassthroughBridge.class);
    private static final String DIR_SLAVE_TO_BACKEND = "SLAVE_TO_BACKEND";
    private static final String DIR_BACKEND_TO_SLAVE = "BACKEND_TO_SLAVE";
    private static final int BODY_HEADER = 16 + 8 + 4;

    private final RoutingPolicy routing;
    private final CircuitBreaker circuitBreaker;
    private final PacketLog packetLog;

    /** playerUuid hex → slave 会话（slave 上行首报文登记；下行按此路由回传）。 */
    private final Map<String, EncapsulatedControlSession> routeByPlayerUuid = new ConcurrentHashMap<>();

    private volatile SourceConnector sourceConnector;

    public McPassthroughBridge(RoutingPolicy routing, CircuitBreaker circuitBreaker, PacketLog packetLog) {
        this.routing = routing;
        this.circuitBreaker = circuitBreaker;
        this.packetLog = packetLog;
    }

    /** 源服 backend 连接就绪（SourceConnector 装配注入；null = 未启用）。双向接线：
     *  SourceConnector 收到源服下行 MC_PASSTHROUGH → 本桥转发。 */
    public void attachSourceConnector(SourceConnector sourceConnector) {
        this.sourceConnector = sourceConnector;
        if (sourceConnector != null) {
            sourceConnector.setBridge(this);
        }
    }

    /** slave 报文 → 源服（桥建立：源服健康 + 断路器放行；拒绝 → fallback 缓存会话）。
     *  body v2 = [playerUuid(16)][流序号(8)][长度(4)][报文]；uuid 首次见登记路由。 */
    public void onSlavePacket(EncapsulatedControlSession slaveSession, byte[] body) {
        if (body.length < BODY_HEADER) {
            LOGGER.warn("[control] 权威桥：body 过短（{} 字节，fail closed 丢弃）", body.length);
            return;
        }
        String playerUuidHex = HexFormat.of().formatHex(body, 0, 16);
        this.routeByPlayerUuid.put(playerUuidHex, slaveSession);
        SourceConnector source = this.sourceConnector;
        if (source == null || !source.connected()) {
            LOGGER.warn("[control] 权威桥：源服未连接，拒绝转发 {}（slave 继续缓存会话，fail closed）",
                    playerUuidHex);
            return;
        }
        if (!this.routing.allowAuthoritative(System.currentTimeMillis())) {
            LOGGER.warn("[control] 权威桥：源服不健康/断路器 OPEN，拒绝 {}（后端故障 fallback 缓存会话）",
                    playerUuidHex);
            return;
        }
        this.packetLog.append(DIR_SLAVE_TO_BACKEND, body);
        source.sendMcPassthrough(body);
    }

    /** 源服下行报文 → 按 playerUuid 路由到对应 slave 会话 + 数据包缓存。 */
    public void onBackendPacket(byte[] body) {
        if (body.length < BODY_HEADER) {
            LOGGER.warn("[control] 权威桥：源服下行 body 过短（{} 字节，丢弃）", body.length);
            return;
        }
        this.packetLog.append(DIR_BACKEND_TO_SLAVE, body);
        String playerUuidHex = HexFormat.of().formatHex(body, 0, 16);
        EncapsulatedControlSession slaveSession = this.routeByPlayerUuid.get(playerUuidHex);
        if (slaveSession == null) {
            LOGGER.warn("[control] 权威桥：无 {} 的 slave 会话，丢弃源服下行包（{} 字节）",
                    playerUuidHex, body.length);
            return;
        }
        slaveSession.sendMcPassthrough(body);
        this.circuitBreaker.recordSuccess();
    }

    /** slave 断开：移除其全部路由（v2 多会话；会话销毁由源服 VirtualPlayerRegistry 兜底）。 */
    public void onSlaveDisconnected(EncapsulatedControlSession slaveSession) {
        this.routeByPlayerUuid.entrySet().removeIf(e -> e.getValue() == slaveSession);
    }

    /** 源服连接断开：路由表保留（slave 会话仍在；源服重连后 uuid 上行重登记）。 */
    public void onSourceDisconnected() {
        LOGGER.info("[control] 权威桥：源服断开，路由表保留（{} 会话待源服重连）",
                this.routeByPlayerUuid.size());
    }
}

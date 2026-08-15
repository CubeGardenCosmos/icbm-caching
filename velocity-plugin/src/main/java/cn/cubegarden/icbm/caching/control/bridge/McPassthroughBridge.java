package cn.cubegarden.icbm.caching.control.bridge;

import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.net.SourceConnector;
import cn.cubegarden.icbm.caching.control.routing.CircuitBreaker;
import cn.cubegarden.icbm.caching.control.routing.RoutingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权威会话桥（里程碑 5 v1；ARCHITECTURE §3.2 / HIERARCHY §3.1）：slave 明文 MC 报文
 * （封装通道 MC_PASSTHROUGH 流）↔ master ↔ 源服 backend（封装通道 MC_PASSTHROUGH 流）
 * 双向字节透传 + 数据包缓存（PacketLog）。
 *
 * <p>v1 范围：**单一活跃权威会话**（一个 slave ↔ 源服；多玩家并发权威会话随阶段 6/7
 * 虚拟玩家连接）。桥建立时经 {@link RoutingPolicy#allowAuthoritative} 授权（源服健康 +
 * 断路器未 OPEN，后端故障 fallback：拒绝 → slave 继续缓存会话）。源服回包失败 →
 * {@link CircuitBreaker#recordFailure}；成功 → recordSuccess。
 *
 * <p>MC_PASSTHROUGH body = [流序号(8)][长度(4)][明文 MC 报文]（CACHE_PROTOCOL §1.1），
 * master 只做字节透传（不解析 26.3 包结构，不复制常量）。
 */
public final class McPassthroughBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(McPassthroughBridge.class);
    private static final String DIR_SLAVE_TO_BACKEND = "SLAVE_TO_BACKEND";
    private static final String DIR_BACKEND_TO_SLAVE = "BACKEND_TO_SLAVE";

    private final RoutingPolicy routing;
    private final CircuitBreaker circuitBreaker;
    private final PacketLog packetLog;
    private final Object lock = new Object();

    private volatile EncapsulatedControlSession activeSlaveSession;
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

    /** slave 报文 → 源服（桥建立：源服健康 + 断路器放行；拒绝 → fallback 缓存会话）。 */
    public void onSlavePacket(EncapsulatedControlSession slaveSession, byte[] body) {
        if (this.sourceConnector == null || !this.sourceConnector.connected()) {
            LOGGER.warn("[control] 权威桥：源服未连接，拒绝转发（slave 继续缓存会话，fail closed）");
            return;
        }
        if (!this.routing.allowAuthoritative(System.currentTimeMillis())) {
            LOGGER.warn("[control] 权威桥：源服不健康/断路器 OPEN，拒绝（后端故障 fallback 缓存会话）");
            return;
        }
        synchronized (this.lock) {
            this.activeSlaveSession = slaveSession;
        }
        this.packetLog.append(DIR_SLAVE_TO_BACKEND, body);
        this.sourceConnector.sendMcPassthrough(body);
    }

    /** 源服下行报文 → 活跃 slave 会话 + 数据包缓存。 */
    public void onBackendPacket(byte[] body) {
        this.packetLog.append(DIR_BACKEND_TO_SLAVE, body);
        EncapsulatedControlSession slaveSession = this.activeSlaveSession;
        if (slaveSession == null) {
            LOGGER.warn("[control] 权威桥：无活跃 slave 会话，丢弃源服下行包（{} 字节）", body.length);
            return;
        }
        slaveSession.sendMcPassthrough(body);
        this.circuitBreaker.recordSuccess();
    }

    /** slave 断开：清活跃会话。 */
    public void onSlaveDisconnected(EncapsulatedControlSession slaveSession) {
        synchronized (this.lock) {
            if (this.activeSlaveSession == slaveSession) {
                this.activeSlaveSession = null;
            }
        }
    }
}

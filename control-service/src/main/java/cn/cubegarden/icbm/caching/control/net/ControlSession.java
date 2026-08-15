package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Role;

/**
 * 控制会话抽象（master 侧领域逻辑依赖的最小面；netty mTLS 会话与封装通道会话
 * 共用同一套 ControlService/Replicator/PeerRegistry 逻辑——阶段1 封装服务复用）。
 */
public interface ControlSession {

    /** sourceServerId（HELLO 包络中的对端标识）。 */
    String sourceServerId();

    /** 鉴权绑定标识：mTLS 会话 = 证书 CN；封装通道会话 = AUTH UUID。 */
    String bindingId();

    Role role();

    /** 最近入站包络的 worldEpoch（源侧 PUT_SNAPSHOT 的 epoch 来源）。 */
    long worldEpoch();

    boolean isActive();

    void requireRole(Role required);

    void requireSlaveRole();

    /** 发送（出站序号自增；推送类消息由调用方以事件序号覆盖）。 */
    void send(MessageType type, long requestId, byte[] body);

    /** 推送：包络序号 = 事件序号（跨连接单调，供 slave ACK 水位使用）。 */
    void sendWithSequence(MessageType type, long requestId, long worldEpoch, long sequence, byte[] body);

    /** HEARTBEAT：不受序号递增约束（seq=0）。 */
    void sendHeartbeat();

    /** 协议违规：发送 ERROR 后关闭。 */
    void fail(String reason);
}

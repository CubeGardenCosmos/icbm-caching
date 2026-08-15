package cn.cubegarden.icbm.caching.control.bridge;

import cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;

/**
 * 封装会话监听复合器：LOGIN_VERIFY 流 → Auth Gateway；MC_PASSTHROUGH 流 → 权威桥；
 * 连接关闭 → 会话记账清理 + 桥清理（SESSION_CLOSED 未送达时防泄漏）。
 */
public final class CompositeSessionListener implements EncapsulatedControlSession.Listener {

    private final AuthGatewayListener authGateway;
    private final McPassthroughBridge bridge;

    public CompositeSessionListener(AuthGatewayListener authGateway, McPassthroughBridge bridge) {
        this.authGateway = authGateway;
        this.bridge = bridge;
    }

    @Override
    public void onLoginVerifyRequest(EncapsulatedControlSession session, byte[] body) {
        this.authGateway.onLoginVerifyRequest(session, body);
    }

    @Override
    public void onMcPacket(EncapsulatedControlSession session, long streamSequence, byte[] packet) {
        this.bridge.onSlavePacket(session, packet);
    }

    @Override
    public void onSessionClosed(EncapsulatedControlSession session) {
        this.authGateway.onSlaveDisconnected(session.sourceServerId());
        this.bridge.onSlaveDisconnected(session);
    }
}

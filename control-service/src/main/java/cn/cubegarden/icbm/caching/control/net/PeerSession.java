package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Role;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制连接 netty 会话（mTLS 传输适配层，草稿 phase2-control-protocol §1/§2）：
 * pipeline = SSL(mTLS) → 帧切分 → 消息解码 → PeerSession。
 *
 * <p>HELLO 校验、序号递增校验、消息分发与权限规则全部委托共享的
 * {@link ControlSessionLogic}（封装通道会话复用同一逻辑）。
 */
public final class PeerSession extends ChannelInboundHandlerAdapter implements ControlSessionLogic.Transport {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerSession.class);

    private final ControlSessionLogic logic;
    private final Channel channel;
    private volatile String certCn; // 惰性：TLS 握手完成后才可读
    private ScheduledFuture<?> heartbeatTask;

    public PeerSession(Channel channel, ServiceConfig config, PeerRegistry registry,
                       ControlService service, Replicator replicator) {
        this.channel = channel;
        this.logic = new ControlSessionLogic(config, registry, service, replicator, this, this::certCn);
    }

    /** 证书 CN（TLS 握手完成后解析；无 TLS 时为空串）。 */
    public String certCn() {
        String cn = this.certCn;
        if (cn == null) {
            cn = extractCertCn(this.channel);
            this.certCn = cn;
        }
        return cn;
    }

    private static String extractCertCn(Channel channel) {
        try {
            SslHandler ssl = channel.pipeline().get(SslHandler.class);
            if (ssl != null && ssl.engine().getSession().getPeerPrincipal() != null) {
                String principal = ssl.engine().getSession().getPeerPrincipal().getName();
                // "CN=source-server, ..." → "source-server"
                String prefix = "CN=";
                int start = principal.indexOf(prefix);
                if (start >= 0) {
                    int end = principal.indexOf(',', start + prefix.length());
                    return end < 0 ? principal.substring(start + prefix.length())
                            : principal.substring(start + prefix.length(), end).trim();
                }
                return principal;
            }
        } catch (Exception ignored) {
            // 无 TLS：测试模式
        }
        return "";
    }

    public boolean isActive() {
        return this.logic.isActive();
    }

    public String sourceServerId() {
        return this.logic.sourceServerId();
    }

    public Role role() {
        return this.logic.role();
    }

    public long worldEpoch() {
        return this.logic.worldEpoch();
    }

    public void requireRole(Role required) {
        this.logic.requireRole(required);
    }

    public void requireSlaveRole() {
        this.logic.requireSlaveRole();
    }

    public void send(MessageType type, long requestId, byte[] body) {
        this.logic.send(type, requestId, body);
    }

    public void sendWithSequence(MessageType type, long requestId, long worldEpoch, long sequence, byte[] body) {
        this.logic.sendWithSequence(type, requestId, worldEpoch, sequence, body);
    }

    public void sendHeartbeat() {
        this.logic.sendHeartbeat();
    }

    // ---- ControlSessionLogic.Transport ----

    @Override
    public void write(Envelope envelope) {
        if (!this.channel.isActive()) {
            throw new IllegalStateException("channel closed");
        }
        this.channel.writeAndFlush(envelope);
    }

    @Override
    public void closeTransport() {
        if (this.heartbeatTask != null) {
            this.heartbeatTask.cancel(false);
            this.heartbeatTask = null;
        }
        if (this.channel.isActive()) {
            this.channel.close();
        }
    }

    @Override
    public void scheduleHeartbeat(Runnable action, long intervalMillis) {
        this.heartbeatTask = this.channel.eventLoop().scheduleAtFixedRate(
                action, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    // ---- netty handler ----

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Envelope envelope) {
            this.logic.onEnvelope(envelope);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.logic.onTransportClosed();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warn("[control] 连接异常 {} ({}): {}", this.certCn(), this.sourceServerId(),
                cause.toString());
        this.logic.onTransportClosed();
        ctx.close();
    }
}

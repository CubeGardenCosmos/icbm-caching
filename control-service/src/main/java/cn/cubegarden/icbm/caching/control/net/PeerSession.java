package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.Role;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制连接会话（显式状态机，草稿 phase2-control-protocol §3/§4）：
 * {@code AWAIT_HELLO → ACTIVE → CLOSED}。
 *
 * <p>校验（按序，任一失败 → ERROR + 断连，fail closed）：
 * <ol>
 *   <li>HELLO：证书 CN 绑定角色一致、版本/runtimeProtocol/fingerprint 通过（{@link PeerRegistry}）；</li>
 *   <li>入站 sequence 严格递增（源→master 方向防重放；gap/重放 → ERROR）；</li>
 *   <li>角色权限：SOURCE 仅 WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/ADMIN_INVALIDATE；
 *       slave 仅 SUBSCRIBE/ACK/RESYNC/RESOLVE。</li>
 * </ol>
 *
 * <p>master→slave 推送使用事件序号作为包络 sequence；HEARTBEAT 不受序号递增约束
 * （保活探测，不承载有序状态）。
 */
public final class PeerSession extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerSession.class);

    private enum State {
        AWAIT_HELLO, ACTIVE, CLOSED,
    }

    /** 协议违规（权限/校验失败）：由异常处理器发 ERROR 后断连。 */
    public static final class ProtocolViolation extends RuntimeException {
        public ProtocolViolation(String message) {
            super(message);
        }
    }

    private final Channel channel;
    private final ServiceConfig config;
    private final PeerRegistry registry;
    private final ControlService service;
    private final Replicator replicator;

    private volatile State state = State.AWAIT_HELLO;
    private volatile String sourceServerId;
    private volatile Role role;
    private volatile String certCn; // 惰性：TLS 握手完成后才可读
    private long lastSequence;             // 入站严格递增校验（源→master 方向）
    private long outboundSequence;         // master→对端包络序号（推送用事件序号覆盖）
    private volatile long lastEnvelopeWorldEpoch;
    private ScheduledFuture<?> heartbeatTask;

    public PeerSession(Channel channel, ServiceConfig config, PeerRegistry registry,
                       ControlService service, Replicator replicator) {
        this.channel = channel;
        this.config = config;
        this.registry = registry;
        this.service = service;
        this.replicator = replicator;
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
        return this.state == State.ACTIVE;
    }

    public String sourceServerId() {
        return this.sourceServerId;
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

    public Role role() {
        return this.role;
    }

    /** 最近入站包络的 worldEpoch（源侧 PUT_SNAPSHOT 的 epoch 来源）。 */
    public long worldEpoch() {
        return this.lastEnvelopeWorldEpoch;
    }

    public void requireRole(Role required) {
        if (this.role != required) {
            throw new ProtocolViolation("角色 " + this.role + " 无权发送该消息（需要 " + required + "）");
        }
    }

    public void requireSlaveRole() {
        if (this.role == null || !this.role.isSlave()) {
            throw new ProtocolViolation("角色 " + this.role + " 无权发送该消息（需要 slave 角色）");
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 等待 HELLO（AWAIT_HELLO 超时由帧层 ReadTimeoutHandler 兜底）
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Envelope envelope)) {
            return;
        }
        try {
            this.handleEnvelope(ctx, envelope);
        } catch (ProtocolViolation e) {
            LOGGER.warn("[control] 协议违规来自 {} ({}): {}", this.certCn, this.sourceServerId, e.getMessage());
            this.fail(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("[control] 处理消息失败来自 {}: {}", this.sourceServerId, e.toString());
            this.fail("internal error: " + e.getClass().getSimpleName());
        }
    }

    private void handleEnvelope(ChannelHandlerContext ctx, Envelope envelope) {
        if (this.state == State.CLOSED) {
            return;
        }
        this.lastEnvelopeWorldEpoch = envelope.worldEpoch();
        switch (this.state) {
            case AWAIT_HELLO -> this.handleHello(ctx, envelope);
            case ACTIVE -> {
                this.requireIncreasingSequence(envelope);
                this.dispatch(envelope);
            }
            case CLOSED -> {
            }
        }
    }

    private void handleHello(ChannelHandlerContext ctx, Envelope envelope) {
        if (envelope.messageType() != MessageType.HELLO) {
            throw new ProtocolViolation("首个消息必须是 HELLO，收到 " + envelope.messageType());
        }
        this.requireIncreasingSequence(envelope);
        Messages.Hello hello = decodeBody(MessageType.HELLO, envelope.body());
        if (hello == null) {
            throw new ProtocolViolation("HELLO 体无法解析");
        }
        this.sourceServerId = envelope.sourceServerId();
        String rejectReason = null;
        if (!"26.3".equals(hello.minecraftVersion())) {
            rejectReason = "minecraftVersion 不支持: " + hello.minecraftVersion();
        } else if (hello.runtimeProtocol() != this.config.runtimeProtocol()) {
            rejectReason = "runtimeProtocol 不匹配: " + hello.runtimeProtocol()
                    + "（期望 " + this.config.runtimeProtocol() + "）";
        } else {
            rejectReason = this.registry.register(this, hello.role(),
                    hello.modpackFingerprint(), hello.registryAndDatapackFingerprint());
        }
        if (rejectReason != null) {
            this.send(MessageType.HELLO_ACK, envelope.requestId(),
                    encode(MessageType.HELLO_ACK, new Messages.HelloAck(false, rejectReason, this.config.serverId())));
            throw new ProtocolViolation(rejectReason);
        }
        this.role = hello.role();
        this.state = State.ACTIVE;
        this.send(MessageType.HELLO_ACK, envelope.requestId(),
                encode(MessageType.HELLO_ACK, new Messages.HelloAck(true, "", this.config.serverId())));
        if (this.role.isSlave()) {
            this.startHeartbeat();
        }
    }

    private void dispatch(Envelope envelope) {
        MessageType type = envelope.messageType();
        if (type == MessageType.HEARTBEAT) {
            // slave 保活应答：更新 master 侧 lastAckTime（由 Replicator 经 ACK 推进）
            Messages.Heartbeat heartbeat = decodeBody(type, envelope.body());
            if (heartbeat != null) {
                this.replicator.onAck(this, new Messages.Ack(heartbeat.lastSequence()));
            }
            return;
        }
        switch (type) {
            case WORLD_EPOCH -> this.service.onWorldEpoch(this, decodeBody(type, envelope.body()));
            case INVALIDATE -> this.service.onInvalidate(this, decodeBody(type, envelope.body()));
            case PUT_SNAPSHOT -> {
                Messages.PutSnapshot snapshot = decodeBody(type, envelope.body());
                var result = this.service.onPutSnapshot(this, snapshot);
                this.send(MessageType.PUT_SNAPSHOT_ACK, envelope.requestId(), encode(
                        MessageType.PUT_SNAPSHOT_ACK,
                        new Messages.PutSnapshotAck(snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ(),
                                snapshot.revision(), result)));
            }
            case RESOLVE_CHUNK_REQ -> {
                Messages.ResolveChunkReq req = decodeBody(type, envelope.body());
                var res = this.service.onResolveChunk(this, req);
                this.send(MessageType.RESOLVE_CHUNK_RES, envelope.requestId(),
                        encode(MessageType.RESOLVE_CHUNK_RES, res));
            }
            case SUBSCRIBE -> this.service.onSubscribe(this, decodeBody(type, envelope.body()));
            case ACK -> this.service.onAck(this, decodeBody(type, envelope.body()));
            case RESYNC_REQ -> {
                Messages.ResyncReq req = decodeBody(type, envelope.body());
                this.service.onResync(this, req);
            }
            case ADMIN_INVALIDATE -> this.service.onAdminInvalidate(this, decodeBody(type, envelope.body()));
            case RESYNC_RES, PUT_SNAPSHOT_ACK, HELLO, HELLO_ACK, SNAPSHOT_PUSH, ERROR -> {
                throw new ProtocolViolation("对端发送了不应由客户端发送的类型: " + type);
            }
        }
    }

    private void requireIncreasingSequence(Envelope envelope) {
        if (envelope.sequence() <= this.lastSequence) {
            throw new ProtocolViolation("序号不递增（重放/乱序）: " + envelope.sequence()
                    + "（上次 " + this.lastSequence + "）");
        }
        this.lastSequence = envelope.sequence();
    }

    /** 发送（出站序号自增；推送类消息由调用方以事件序号覆盖）。 */
    public void send(MessageType type, long requestId, byte[] body) {
        long seq = ++this.outboundSequence;
        this.write(Envelope.of(type, requestId, this.config.serverId(), 0, seq, System.currentTimeMillis(), body));
    }

    /** 推送：包络序号 = 事件序号（跨连接单调，供 slave ACK 水位使用）；worldEpoch = 事件所属维度 epoch。 */
    public void sendWithSequence(MessageType type, long requestId, long worldEpoch, long sequence, byte[] body) {
        this.write(Envelope.of(type, requestId, this.config.serverId(), worldEpoch, sequence,
                System.currentTimeMillis(), body));
        this.outboundSequence = Math.max(this.outboundSequence, sequence);
    }

    /** HEARTBEAT：不受序号递增约束（seq=0）。 */
    public void sendHeartbeat() {
        try {
            this.write(Envelope.of(MessageType.HEARTBEAT, 0, this.config.serverId(), 0, 0,
                    System.currentTimeMillis(),
                    encode(MessageType.HEARTBEAT, new Messages.Heartbeat(System.currentTimeMillis(), 0))));
        } catch (IllegalStateException ignored) {
            // 连接关闭中
        }
    }

    private void write(Envelope envelope) {
        if (!this.channel.isActive()) {
            throw new IllegalStateException("channel closed");
        }
        this.channel.writeAndFlush(envelope);
    }

    private void fail(String reason) {
        try {
            this.send(MessageType.ERROR, 0, encode(MessageType.ERROR, new Messages.ErrorMessage(reason)));
        } catch (Exception ignored) {
            // 通道可能已不可写
        }
        this.close();
    }

    private void close() {
        this.state = State.CLOSED;
        if (this.heartbeatTask != null) {
            this.heartbeatTask.cancel(false);
            this.heartbeatTask = null;
        }
        if (this.channel.isActive()) {
            this.channel.close();
        }
    }

    private void startHeartbeat() {
        this.heartbeatTask = this.channel.eventLoop().scheduleAtFixedRate(
                this::sendHeartbeat, this.config.heartbeatIntervalMillis(),
                this.config.heartbeatIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.state = State.CLOSED;
        if (this.heartbeatTask != null) {
            this.heartbeatTask.cancel(false);
            this.heartbeatTask = null;
        }
        this.registry.unregister(this.sourceServerId);
        this.replicator.onDisconnected(this);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warn("[control] 连接异常 {} ({}): {}", this.certCn, this.sourceServerId,
                cause.toString());
        this.state = State.CLOSED;
        ctx.close();
    }

    @SuppressWarnings("unchecked")
    private static <T> T decodeBody(MessageType type, byte[] body) {
        try {
            return (T) MessageBodyCodec.decode(type, new DataInputStream(new java.io.ByteArrayInputStream(body)));
        } catch (IOException e) {
            throw new ProtocolViolation(type + " 体解析失败: " + e.getMessage());
        }
    }

    private static byte[] encode(MessageType type, Object message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            MessageBodyCodec.encode(type, out, message);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode failed", e);
        }
    }
}

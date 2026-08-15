package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.Role;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制会话共享逻辑（显式状态机：{@code AWAIT_HELLO → ACTIVE → CLOSED}），
 * 与传输解耦：封装通道会话（{@link EncapsulatedControlSession}）及其它传输实现
 * 复用同一套 HELLO 校验、序号递增校验、消息分发与权限规则
 * （草稿 encapsulated-channel §3/§4）。
 *
 * <p>校验（按序，任一失败 → ERROR + 断连，fail closed）：
 * <ol>
 *   <li>HELLO：鉴权绑定角色一致、版本/runtimeProtocol/fingerprint 通过（{@link PeerRegistry}）；</li>
 *   <li>入站 sequence 严格递增（防重放；gap/重放 → ERROR）；</li>
 *   <li>角色权限：SOURCE 仅 WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/ADMIN_INVALIDATE；
 *       slave 仅 SUBSCRIBE/ACK/RESYNC/RESOLVE。</li>
 * </ol>
 *
 * <p>master→slave 推送使用事件序号作为包络 sequence；HEARTBEAT 不受序号递增约束。
 */
public final class ControlSessionLogic implements ControlSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlSessionLogic.class);

    /** 协议违规（权限/校验失败）：由调用方发 ERROR 后断连。 */
    public static final class ProtocolViolation extends RuntimeException {
        public ProtocolViolation(String message) {
            super(message);
        }
    }

    private enum State {
        AWAIT_HELLO, ACTIVE, CLOSED,
    }

    /** 传输侧回调（写帧、关连接、心跳调度由具体传输实现）。 */
    public interface Transport {
        void write(Envelope envelope);

        void closeTransport();

        void scheduleHeartbeat(Runnable action, long intervalMillis);
    }

    private final ServiceConfig config;
    private final PeerRegistry registry;
    private final ControlService service;
    private final Replicator replicator;
    private final Transport transport;
    private final Supplier<String> bindingIdProvider;

    private volatile State state = State.AWAIT_HELLO;
    private volatile String sourceServerId;
    private volatile Role role;
    private long lastSequence;             // 入站严格递增校验
    private long outboundSequence;         // 出站包络序号（推送用事件序号覆盖）
    private volatile long lastEnvelopeWorldEpoch;

    public ControlSessionLogic(ServiceConfig config, PeerRegistry registry,
                               ControlService service, Replicator replicator,
                               Transport transport, Supplier<String> bindingIdProvider) {
        this.config = config;
        this.registry = registry;
        this.service = service;
        this.replicator = replicator;
        this.transport = transport;
        this.bindingIdProvider = bindingIdProvider;
    }

    @Override
    public String sourceServerId() {
        return this.sourceServerId;
    }

    @Override
    public String bindingId() {
        return this.bindingIdProvider.get();
    }

    @Override
    public Role role() {
        return this.role;
    }

    @Override
    public long worldEpoch() {
        return this.lastEnvelopeWorldEpoch;
    }

    @Override
    public boolean isActive() {
        return this.state == State.ACTIVE;
    }

    @Override
    public void requireRole(Role required) {
        if (this.role != required) {
            throw new ProtocolViolation("角色 " + this.role + " 无权发送该消息（需要 " + required + "）");
        }
    }

    @Override
    public void requireSlaveRole() {
        if (this.role == null || !this.role.isSlave()) {
            throw new ProtocolViolation("角色 " + this.role + " 无权发送该消息（需要 slave 角色）");
        }
    }

    /** 入站包络入口（传输层解码后调用）。 */
    public void onEnvelope(Envelope envelope) {
        if (this.state == State.CLOSED) {
            return;
        }
        this.lastEnvelopeWorldEpoch = envelope.worldEpoch();
        try {
            switch (this.state) {
                case AWAIT_HELLO -> this.handleHello(envelope);
                case ACTIVE -> {
                    this.requireIncreasingSequence(envelope);
                    this.dispatch(envelope);
                }
                case CLOSED -> {
                }
            }
        } catch (ProtocolViolation e) {
            LOGGER.warn("[control] 协议违规来自 {} ({}): {}", this.bindingId(), this.sourceServerId, e.getMessage());
            this.fail(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("[control] 处理消息失败来自 {}: {}", this.sourceServerId, e.toString());
            this.fail("internal error: " + e.getClass().getSimpleName());
        }
    }

    private void handleHello(Envelope envelope) {
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
        if (!hello.minecraftVersion().startsWith("26.3")) {
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
            this.transport.scheduleHeartbeat(this::sendHeartbeat, this.config.heartbeatIntervalMillis());
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
            case SESSION_CLOSED -> this.service.onSessionClosed(this, decodeBody(type, envelope.body()));
            case SUBMIT_OPERATION -> this.service.onSubmitOperation(this, decodeBody(type, envelope.body()));
            case GET_OPERATION_STATUS -> this.service.onGetOperationStatus(this, decodeBody(type, envelope.body()));
            case RESYNC_RES, PUT_SNAPSHOT_ACK, HELLO, HELLO_ACK, SNAPSHOT_PUSH, ERROR,
                    OPERATION_COMMITTED, OPERATION_REJECTED, OPERATION_STATUS_RES -> {
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

    @Override
    public void send(MessageType type, long requestId, byte[] body) {
        long seq = ++this.outboundSequence;
        this.transport.write(Envelope.of(type, requestId, this.config.serverId(), 0, seq,
                System.currentTimeMillis(), body));
    }

    @Override
    public void sendWithSequence(MessageType type, long requestId, long worldEpoch, long sequence, byte[] body) {
        this.transport.write(Envelope.of(type, requestId, this.config.serverId(), worldEpoch, sequence,
                System.currentTimeMillis(), body));
        this.outboundSequence = Math.max(this.outboundSequence, sequence);
    }

    @Override
    public void sendHeartbeat() {
        try {
            this.transport.write(Envelope.of(MessageType.HEARTBEAT, 0, this.config.serverId(), 0, 0,
                    System.currentTimeMillis(),
                    encode(MessageType.HEARTBEAT, new Messages.Heartbeat(System.currentTimeMillis(), 0))));
        } catch (IllegalStateException ignored) {
            // 连接关闭中
        }
    }

    @Override
    public void fail(String reason) {
        try {
            this.send(MessageType.ERROR, 0, encode(MessageType.ERROR, new Messages.ErrorMessage(reason)));
        } catch (Exception ignored) {
            // 通道可能已不可写
        }
        this.close();
    }

    /** 传输关闭（读侧 EOF/异常/主动 close）时由传输层调用。 */
    public void onTransportClosed() {
        this.state = State.CLOSED;
        this.registry.unregister(this.sourceServerId);
        this.replicator.onDisconnected(this);
    }

    private void close() {
        this.state = State.CLOSED;
        this.transport.closeTransport();
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

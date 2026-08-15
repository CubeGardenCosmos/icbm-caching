package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 源服 backend 连接器（里程碑 3：velocity 授信模式，master 主动连接源服）。
 *
 * <p>流程（草稿 backend-velocity-trust §3；与 slave 通道方向相反）：
 * <ol>
 *   <li>AUTH（公共密码证明，**PSK = velocity forwarding secret**）+ UUID（master 身份）；</li>
 *   <li>等源服发 HELLO（role=SOURCE + sourceServerId + serverName + 指纹 + runtimeProtocol）→
 *       验证并回 HELLO_ACK（accepted/reason）；</li>
 *   <li>ACTIVE：收 WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT → 接入 {@link ControlService}（对象库/
 *       事件日志/订阅推送），PUT 回 PUT_SNAPSHOT_ACK；</li>
 *   <li>断线退避重连（源服重发 HELLO，握手重来）。</li>
 * </ol>
 *
 * <p>无玩家在线也可用：连接由 master 主动建立，不依赖玩家会话。源服 HELLO 校验失败或
 * AUTH 失败 → fail closed（不猜测兼容格式）。
 */
public final class SourceConnector implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceConnector.class);

    private final ServiceConfig config;
    private final ServiceConfig.BackendServer backend;
    private final PeerRegistry registry;
    private final ControlService service;
    private final Replicator replicator;
    private final EncapsulatedChannelClient client;
    private final BackendSessionAdapter adapter;

    public SourceConnector(ServiceConfig config, ControlService service,
                           Replicator replicator, PeerRegistry registry) {
        this.config = config;
        this.backend = config.backend();
        if (this.backend == null) {
            throw new IllegalArgumentException("未配置 control.backend*，无法启动 SourceConnector");
        }
        this.service = service;
        this.replicator = replicator;
        this.registry = registry;
        this.adapter = new BackendSessionAdapter();
        this.client = new EncapsulatedChannelClient(
                this.backend.host(), this.backend.port(),
                this.config.serverId(), masterUuidHex(), this.backend.forwardingSecret(),
                null, EncapsulatedChannelClient.HandshakeMode.SERVER_HELLO,
                60_000, 3_000, true, new ClientListener());
    }

    /** master 的 AUTH 身份（由 serverId 派生，稳定）。 */
    private String masterUuidHex() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(this.config.serverId().getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void start() {
        this.client.start();
        LOGGER.info("[control] SourceConnector 启动: {}:{}（velocity 授信）",
                this.backend.host(), this.backend.port());
    }

    public boolean connected() {
        return this.client.isConnected();
    }

    @Override
    public void close() {
        this.client.close();
        this.registry.unregister(this.adapter.sourceServerId());
    }

    // ---- 客户端事件 ----

    private final class ClientListener implements EncapsulatedChannelClient.Listener {
        @Override
        public boolean onServerHello(Envelope envelope, byte[] body) {
            try {
                Messages.Hello hello = EncapsulatedChannelClient.decode(MessageType.HELLO, body);
                String reason = validate(hello, envelope);
                if (reason != null) {
                    SourceConnector.this.client.send(MessageType.HELLO_ACK, envelope.requestId(), 0,
                            EncapsulatedChannelClient.encode(MessageType.HELLO_ACK,
                                    new Messages.HelloAck(false, reason, SourceConnector.this.config.serverId())));
                    LOGGER.error("[control] 源服 HELLO 校验失败（fail closed）: {}", reason);
                    return false;
                }
                String registerReason = null;
                SourceConnector.this.adapter.onHello(envelope.sourceServerId());
                registerReason = SourceConnector.this.registry.registerSource(
                        SourceConnector.this.adapter, hello.modpackFingerprint(),
                        hello.registryAndDatapackFingerprint());
                if (registerReason != null) {
                    SourceConnector.this.client.send(MessageType.HELLO_ACK, envelope.requestId(), 0,
                            EncapsulatedChannelClient.encode(MessageType.HELLO_ACK,
                                    new Messages.HelloAck(false, registerReason, SourceConnector.this.config.serverId())));
                    return false;
                }
                SourceConnector.this.client.send(MessageType.HELLO_ACK, envelope.requestId(), 0,
                        EncapsulatedChannelClient.encode(MessageType.HELLO_ACK,
                                new Messages.HelloAck(true, "", SourceConnector.this.config.serverId())));
                return true;
            } catch (Exception e) {
                LOGGER.error("[control] 源服 HELLO 解析失败: {}", e.toString());
                return false;
            }
        }

        private String validate(Messages.Hello hello, Envelope envelope) {
            if (hello.role() != Role.SOURCE) {
                return "backend HELLO 角色必须是 SOURCE，收到 " + hello.role();
            }
            if (envelope.sourceServerId() == null || envelope.sourceServerId().isEmpty()) {
                return "backend HELLO 缺少 sourceServerId";
            }
            if (!hello.minecraftVersion().startsWith("26.3")) {
                return "minecraftVersion 不支持: " + hello.minecraftVersion();
            }
            if (hello.runtimeProtocol() != SourceConnector.this.config.runtimeProtocol()) {
                return "runtimeProtocol 不匹配: " + hello.runtimeProtocol()
                        + "（期望 " + SourceConnector.this.config.runtimeProtocol() + "）";
            }
            if (hello.serverName().isEmpty()) {
                return "serverName 不能为空（缓存隔离键）";
            }
            return null;
        }

        @Override
        public void onConnected(String serverId) {
            LOGGER.info("[control] 源服 backend 已连接: {}", serverId);
        }

        @Override
        public void onRejected(String reason) {
            SourceConnector.this.registry.unregister(SourceConnector.this.adapter.sourceServerId());
            LOGGER.error("[control] 源服 backend 连接被拒（fail closed）: {}", reason);
        }

        @Override
        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
            SourceConnector.this.adapter.onEnvelope(envelope);
            try {
                switch (type) {
                    case WORLD_EPOCH -> SourceConnector.this.service.onWorldEpoch(
                            SourceConnector.this.adapter,
                            EncapsulatedChannelClient.decode(MessageType.WORLD_EPOCH, body));
                    case INVALIDATE -> SourceConnector.this.service.onInvalidate(
                            SourceConnector.this.adapter,
                            EncapsulatedChannelClient.decode(MessageType.INVALIDATE, body));
                    case PUT_SNAPSHOT -> {
                        Messages.PutSnapshot snapshot =
                                EncapsulatedChannelClient.decode(MessageType.PUT_SNAPSHOT, body);
                        PutResult result = SourceConnector.this.service.onPutSnapshot(
                                SourceConnector.this.adapter, snapshot);
                        SourceConnector.this.client.send(MessageType.PUT_SNAPSHOT_ACK, envelope.requestId(), 0,
                                EncapsulatedChannelClient.encode(MessageType.PUT_SNAPSHOT_ACK,
                                        new Messages.PutSnapshotAck(snapshot.dimension(), snapshot.chunkX(),
                                                snapshot.chunkZ(), snapshot.revision(), result)));
                    }
                    case HEARTBEAT -> {
                        // 源服保活：仅维持连接（无水位推进）
                    }
                    case ERROR -> LOGGER.error("[control] 源服错误: {}",
                            EncapsulatedChannelClient.<Messages.ErrorMessage>decode(MessageType.ERROR, body).reason());
                    default -> LOGGER.warn("[control] 忽略来自源服的类型: {}", type);
                }
            } catch (Exception e) {
                LOGGER.error("[control] 处理源服消息失败（fail closed，保持现有状态）: {}", e.toString());
            }
        }

        @Override
        public void onDisconnected(Throwable cause) {
            SourceConnector.this.registry.unregister(SourceConnector.this.adapter.sourceServerId());
            LOGGER.warn("[control] 源服 backend 断开（重连）: {}", cause == null ? "?" : cause.getMessage());
        }
    }

    /** ControlSession 适配（源服=SOURCE 生产者；master 只回 ACK，无推送水位）。 */
    private final class BackendSessionAdapter implements ControlSession {
        private volatile String sourceServerId;
        private volatile long lastEnvelopeWorldEpoch;

        void onHello(String sourceServerId) {
            this.sourceServerId = sourceServerId;
        }

        void onEnvelope(Envelope envelope) {
            this.lastEnvelopeWorldEpoch = envelope.worldEpoch();
        }

        @Override
        public String sourceServerId() {
            return this.sourceServerId;
        }

        @Override
        public String bindingId() {
            return "backend:" + SourceConnector.this.backend.host() + ":" + SourceConnector.this.backend.port();
        }

        @Override
        public Role role() {
            return Role.SOURCE;
        }

        @Override
        public long worldEpoch() {
            return this.lastEnvelopeWorldEpoch;
        }

        @Override
        public boolean isActive() {
            return SourceConnector.this.client.isConnected();
        }

        @Override
        public void requireRole(Role required) {
            if (this.role() != required) {
                throw new ControlSessionLogic.ProtocolViolation(
                        "角色 " + this.role() + " 无权发送该消息（需要 " + required + "）");
            }
        }

        @Override
        public void requireSlaveRole() {
            throw new ControlSessionLogic.ProtocolViolation("backend 通道不接受 slave 消息");
        }

        @Override
        public void send(MessageType type, long requestId, byte[] body) {
            SourceConnector.this.client.send(type, requestId, 0, body);
        }

        @Override
        public void sendWithSequence(MessageType type, long requestId, long worldEpoch, long sequence, byte[] body) {
            // backend 通道 master→源服只有 ACK，无事件推送水位
            SourceConnector.this.client.send(type, requestId, 0, body);
        }

        @Override
        public void sendHeartbeat() {
            // 源服侧发心跳；master 不主动发
        }

        @Override
        public void fail(String reason) {
            try {
                SourceConnector.this.client.send(MessageType.ERROR, 0, 0,
                        EncapsulatedChannelClient.encode(MessageType.ERROR, new Messages.ErrorMessage(reason)));
            } catch (Exception ignored) {
            }
            SourceConnector.this.client.close();
        }
    }
}

package cn.cubegarden.icbm.caching.control.service;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.net.ControlSession;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.ResolveStatus;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制服务领域门面（master 缓存目录）：INVALIDATE / PUT_SNAPSHOT / WORLD_EPOCH /
 * RESOLVE_CHUNK / ADMIN_INVALIDATE / SUBSCRIBE / ACK / RESYNC。
 *
 * <p>提交顺序（CACHE_PROTOCOL §4.3）：INVALIDATE 先提高 required → PUT_SNAPSHOT 校验
 * 通过后持久化并原子写 committed → 事件日志追加 → 向订阅 slave 推送。
 */
public final class ControlService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlService.class);

    private final SnapshotObjectStore store;
    private final EventLog eventLog;
    private final Replicator replicator;
    private final PeerRegistry registry;
    private final int runtimeProtocol;
    private volatile cn.cubegarden.icbm.caching.control.auth.SessionRegistry sessionRegistry;
    private volatile cn.cubegarden.icbm.caching.control.auth.TicketVault ticketVault;
    private volatile cn.cubegarden.icbm.caching.control.net.SourceConnector sourceConnector;
    /** 乐观操作路由：operationId(hex) → 发起 slave 会话（源服终态回发按此路由；有界近似 LRU）。 */
    private final java.util.Map<String, ControlSession> operationRoutes =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, ControlSession> eldest) {
                    return size() > 4096;
                }
            });
    private volatile boolean shuttingDown;

    public ControlService(SnapshotObjectStore store, EventLog eventLog,
                          Replicator replicator, PeerRegistry registry, ServiceConfig config) {
        this.store = store;
        this.eventLog = eventLog;
        this.replicator = replicator;
        this.registry = registry;
        this.runtimeProtocol = config.runtimeProtocol();
    }

    public void onWorldEpoch(ControlSession session, Messages.WorldEpoch msg) {
        session.requireRole(Role.SOURCE);
        this.store.announceEpoch(msg.dimension(), msg.worldEpoch());
        long seq = this.eventLog.append(EventLog.EventType.WORLD_EPOCH, msg.dimension(), null,
                msg.worldEpoch(), "", msg.registryAndDatapackFingerprint(), msg.spawn());
        this.replicator.onWorldEpoch(msg, seq);
        LOGGER.debug("[control] WORLD_EPOCH: dim={} epoch={} reason={}", msg.dimension(), msg.worldEpoch(), msg.reason());
    }

    public void onInvalidate(ControlSession session, Messages.Invalidate msg) {
        session.requireRole(Role.SOURCE);
        ChunkRef key = ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ());
        this.store.raiseRequired(key, msg.revision());
        long seq = this.eventLog.append(EventLog.EventType.INVALIDATE, msg.dimension(), key,
                msg.revision(), "", "", Messages.SpawnPoint.DEFAULT);
        this.replicator.onInvalidate(msg, seq);
    }

    /** PUT_SNAPSHOT：校验 → 持久化 → 原子 committed → 日志 → 推送；失败保持 required（fail closed）。 */
    public PutResult onPutSnapshot(ControlSession session, Messages.PutSnapshot msg) {
        session.requireRole(Role.SOURCE);
        ChunkRef key = ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ());
        PutResult result = this.store.put(key, msg.revision(), session.worldEpoch(),
                msg.snapshotFormat(), msg.compression(), msg.uncompressedLength(),
                msg.payloadHash(), msg.payload());
        if (result == PutResult.OK) {
            try {
                this.store.commit(key, msg.revision());
            } catch (IllegalStateException e) {
                LOGGER.error("[control] committed 失败（fail closed，保持 required）: {} rev={}: {}",
                        key, msg.revision(), e.getMessage());
                return PutResult.REJECTED_UNAVAILABLE;
            }
            long seq = this.eventLog.append(EventLog.EventType.SNAPSHOT_COMMITTED, msg.dimension(), key,
                    msg.revision(), msg.payloadHash(), "", Messages.SpawnPoint.DEFAULT);
            this.replicator.onCommitted(key, msg.revision(), msg.payloadHash(), seq);
        }
        return result;
    }

    /**
     * RESOLVE_CHUNK：protocol/fingerprint 不匹配 → INCOMPATIBLE；源未注册或服务关闭 → UNAVAILABLE；
     * 其余委托对象库（HIT/MISS/STALE/INCOMPATIBLE）。
     */
    public Messages.ResolveChunkRes onResolveChunk(ControlSession session, Messages.ResolveChunkReq msg) {
        if (msg.expectedProtocol() != this.runtimeProtocol) {
            return new Messages.ResolveChunkRes(msg.dimension(), msg.chunkX(), msg.chunkZ(),
                    ResolveStatus.INCOMPATIBLE, 0, "", "");
        }
        if (this.shuttingDown || !this.registry.sourceRegistered()) {
            return new Messages.ResolveChunkRes(msg.dimension(), msg.chunkX(), msg.chunkZ(),
                    ResolveStatus.UNAVAILABLE, 0, "", "");
        }
        if (!msg.registryAndDatapackFingerprint().equals(this.registry.sourceRegistryFingerprint())) {
            return new Messages.ResolveChunkRes(msg.dimension(), msg.chunkX(), msg.chunkZ(),
                    ResolveStatus.INCOMPATIBLE, 0, "", "");
        }
        SnapshotObjectStore.ResolveResult resolved = this.store.resolve(
                ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ()), msg.minRevision());
        return new Messages.ResolveChunkRes(msg.dimension(), msg.chunkX(), msg.chunkZ(),
                switch (resolved.status()) {
                    case HIT -> ResolveStatus.HIT;
                    case MISS -> ResolveStatus.MISS;
                    case STALE -> ResolveStatus.STALE;
                    case INCOMPATIBLE -> ResolveStatus.INCOMPATIBLE;
                    case UNAVAILABLE -> ResolveStatus.UNAVAILABLE;
                },
                resolved.committedRevision(), resolved.objectId(), resolved.objectHash());
    }

    /** ADMIN_INVALIDATE：chunk/region/dimension/world 范围失效（审计原因记录到日志）。 */
    /** SESSION_CLOSED：slave 上报会话关闭 → 注销全局记账（防会话表泄漏）。 */
    public void onSessionClosed(ControlSession session, Messages.SessionClosed msg) {
        session.requireRole(Role.SLAVE);
        cn.cubegarden.icbm.caching.control.auth.SessionRegistry registry = this.sessionRegistry;
        if (registry == null) {
            LOGGER.debug("[control] SESSION_CLOSED 忽略（无会话注册表）: {}", msg.sessionId());
            return;
        }
        registry.unregister(java.util.HexFormat.of().formatHex(msg.playerUuid()), msg.sessionId());
        cn.cubegarden.icbm.caching.control.auth.TicketVault vault = this.ticketVault;
        if (vault != null) {
            vault.revoke(msg.sessionId());
        }
        LOGGER.info("[control] 会话关闭注销: session={} reason={}", msg.sessionId(), msg.reason());
    }

    /** 装配时注入会话注册表（与 AuthGateway 共享同一实例；无则 SESSION_CLOSED 不注销）。 */
    public void setSessionRegistry(cn.cubegarden.icbm.caching.control.auth.SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /** 装配时注入票据库（SESSION_CLOSED 吊销票据；无则只注销记账）。 */
    public void setTicketVault(cn.cubegarden.icbm.caching.control.auth.TicketVault ticketVault) {
        this.ticketVault = ticketVault;
    }

    /** 装配时注入源服连接（乐观操作 slave→source 转发；null = 无源服时操作拒绝）。 */
    public void setSourceConnector(cn.cubegarden.icbm.caching.control.net.SourceConnector sourceConnector) {
        this.sourceConnector = sourceConnector;
    }

    // ---- 乐观操作（里程碑 7 阶段 5；裁决在源服主线程，master 纯转发） ----

    /** 按需物化（8.2）：slave 请求生成目标 chunk → 转发源服；源未连接/发送失败 →
     *  即时应答 REJECTED（fail closed，slave 保持撞墙；生成结果经既有快照管道回流）。 */
    public void onChunkMaterializeReq(ControlSession session, Messages.ChunkMaterializeReq msg) {
        session.requireSlaveRole();
        cn.cubegarden.icbm.caching.control.net.SourceConnector source = this.sourceConnector;
        boolean accepted = source != null && source.connected();
        if (!accepted) {
            LOGGER.warn("[control] 物化请求拒绝（源服未连接，fail closed）: req={} {} {},{}",
                    msg.requestId(), msg.dimension(), msg.chunkX(), msg.chunkZ());
        } else {
            accepted = source.send(MessageType.CHUNK_MATERIALIZE_REQ, 0,
                    encodeBody(MessageType.CHUNK_MATERIALIZE_REQ, msg));
        }
        session.send(MessageType.CHUNK_MATERIALIZE_RES, 0,
                encodeBody(MessageType.CHUNK_MATERIALIZE_RES,
                        new Messages.ChunkMaterializeRes(msg.requestId(), accepted,
                                accepted ? "" : "source-unavailable")));
    }

    /** SUBMIT_OPERATION：slave 提交 → 转发源服裁决（源服唯一权威）；记录路由供终态回发。 */
    public void onSubmitOperation(ControlSession session, Messages.SubmitOperation msg) {
        session.requireSlaveRole();
        this.operationRoutes.put(hex(msg.operationId()), session);
        cn.cubegarden.icbm.caching.control.net.SourceConnector source = this.sourceConnector;
        if (source == null || !source.connected()) {
            LOGGER.warn("[control] 操作拒绝（源服未连接，fail closed）: op={} type={}",
                    hex(msg.operationId()), msg.operationType());
            this.routeRejected(session, msg.operationId(), "source-unavailable",
                    msg.dimension(), posToChunk(msg.posX(), msg.posZ()));
            return;
        }
        if (!source.send(MessageType.SUBMIT_OPERATION, 0, encodeBody(MessageType.SUBMIT_OPERATION, msg))) {
            LOGGER.error("[control] 操作转发源服发送失败（fail closed）: op={} type={}",
                    hex(msg.operationId()), msg.operationType());
            this.routeRejected(session, msg.operationId(), "source-send-failed",
                    msg.dimension(), posToChunk(msg.posX(), msg.posZ()));
        }
    }

    /** GET_OPERATION_STATUS：slave 超时查询 → 转发源服终态历史。 */
    public void onGetOperationStatus(ControlSession session, Messages.GetOperationStatus msg) {
        session.requireSlaveRole();
        cn.cubegarden.icbm.caching.control.net.SourceConnector source = this.sourceConnector;
        if (source == null || !source.connected()) {
            LOGGER.warn("[control] 状态查询拒绝（源服未连接）: op={}", hex(msg.operationId()));
            session.send(MessageType.OPERATION_STATUS_RES, 0,
                    encodeBody(MessageType.OPERATION_STATUS_RES,
                            new Messages.OperationStatusRes(msg.operationId(),
                                    Messages.OperationStatusRes.OperationStatus.UNKNOWN,
                                    "", 0, "source-unavailable",
                                    cn.cubegarden.icbm.caching.protocol.DimensionRef.of("minecraft", "overworld"),
                                    new int[0], new int[0])));
            return;
        }
        source.send(MessageType.GET_OPERATION_STATUS, 0, encodeBody(MessageType.GET_OPERATION_STATUS, msg));
    }

    /** OPERATION_COMMITTED：源服终态 → 路由回发起 slave。 */
    public void onOperationCommitted(ControlSession session, Messages.OperationCommitted msg) {
        session.requireRole(Role.SOURCE);
        this.routeToOrigin(msg.operationId(), MessageType.OPERATION_COMMITTED, msg);
    }

    /** OPERATION_REJECTED：源服拒绝 → 路由回发起 slave（携带 resync 范围）。 */
    public void onOperationRejected(ControlSession session, Messages.OperationRejected msg) {
        session.requireRole(Role.SOURCE);
        this.routeToOrigin(msg.operationId(), MessageType.OPERATION_REJECTED, msg);
    }

    /** OPERATION_STATUS_RES：源服查询结果 → 路由回发起 slave。 */
    public void onOperationStatusRes(ControlSession session, Messages.OperationStatusRes msg) {
        session.requireRole(Role.SOURCE);
        this.routeToOrigin(msg.operationId(), MessageType.OPERATION_STATUS_RES, msg);
    }

    // ---- 聊天直传（阶段 9.3；聊天瞬时消息不进事件日志，断线不重放） ----

    /** CHAT_RELAY：slave 玩家聊天上行 → 转发源服权威广播（源服唯一权威）。 */
    public void onChatRelay(ControlSession session, Messages.ChatRelay msg) {
        session.requireSlaveRole();
        cn.cubegarden.icbm.caching.control.net.SourceConnector source = this.sourceConnector;
        if (source == null || !source.connected()) {
            LOGGER.warn("[control] 聊天拒绝（源服未连接，fail closed）: {}: {}",
                    msg.senderName(), msg.message());
            return;
        }
        source.send(MessageType.CHAT_RELAY, 0, encodeBody(MessageType.CHAT_RELAY, msg));
    }

    /** CHAT_BROADCAST：源服权威广播结果 → 分发所有订阅 slave 本地送达（不区分维度）。
     *  由 SourceConnector.onMessage 调用（该通道仅源服消息，无需角色再校验）。 */
    public void onChatBroadcast(Messages.ChatBroadcast msg) {
        byte[] body = encodeBody(MessageType.CHAT_BROADCAST, msg);
        int delivered = 0;
        for (cn.cubegarden.icbm.caching.control.replication.Replicator.SlaveSubscription sub
                : this.replicator.subscriptions().values()) {
            cn.cubegarden.icbm.caching.control.net.ControlSession slave = sub.session();
            if (!slave.isActive()) {
                continue;
            }
            slave.send(MessageType.CHAT_BROADCAST, 0, body);
            delivered++;
        }
        LOGGER.info("[control] 权威聊天广播: {}: {}（slave 送达 {}）", msg.senderName(), msg.message(), delivered);
    }

    private void routeToOrigin(byte[] operationId, MessageType type, Object msg) {
        ControlSession origin = this.operationRoutes.get(hex(operationId));
        if (origin == null) {
            LOGGER.warn("[control] 操作终态无法路由（发起 slave 未知/已断开）: op={} type={}",
                    hex(operationId), type);
            return;
        }
        if (!origin.isActive()) {
            LOGGER.warn("[control] 操作终态丢弃（发起 slave 已断开）: op={}", hex(operationId));
            return;
        }
        origin.send(type, 0, encodeBody(type, msg));
    }

    private void routeRejected(ControlSession origin, byte[] operationId, String reason,
                               DimensionRef dimension, int[][] chunks) {
        int[] xs = new int[chunks.length];
        int[] zs = new int[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            xs[i] = chunks[i][0];
            zs[i] = chunks[i][1];
        }
        origin.send(MessageType.OPERATION_REJECTED, 0,
                encodeBody(MessageType.OPERATION_REJECTED,
                        new Messages.OperationRejected(operationId, reason, dimension, xs, zs,
                                System.currentTimeMillis())));
    }

    private static int[][] posToChunk(int posX, int posZ) {
        return new int[][] {{Math.floorDiv(posX, 16), Math.floorDiv(posZ, 16)}};
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static byte[] encodeBody(MessageType type, Object msg) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.encode(
                    type, new java.io.DataOutputStream(bytes), msg);
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("encode failed: " + type, e);
        }
    }

    public void onAdminInvalidate(ControlSession session, Messages.AdminInvalidate msg) {
        session.requireRole(Role.SOURCE);
        switch (msg.scope()) {
            case "CHUNK" -> {
                ChunkRef key = ChunkRef.of(msg.dimension(), msg.chunkX(), msg.chunkZ());
                this.raiseToNext(key, msg.reason());
            }
            case "REGION" -> {
                for (ChunkRef key : this.store.chunksInRegion(msg.dimension(), msg.regionX(), msg.regionZ())) {
                    this.raiseToNext(key, msg.reason());
                }
            }
            case "DIMENSION" -> {
                for (ChunkRef key : this.store.chunksInDimension(msg.dimension())) {
                    this.raiseToNext(key, msg.reason());
                }
            }
            case "WORLD" -> {
                for (DimensionRef dimension : this.store.knownDimensions()) {
                    for (ChunkRef key : this.store.chunksInDimension(dimension)) {
                        this.raiseToNext(key, msg.reason());
                    }
                }
            }
            default -> throw new IllegalArgumentException("未知失效范围: " + msg.scope());
        }
        LOGGER.warn("[control] ADMIN_INVALIDATE: scope={} dim={} reason={}", msg.scope(), msg.dimension(), msg.reason());
    }

    public void onSubscribe(ControlSession session, Messages.Subscribe msg) {
        session.requireSlaveRole();
        this.replicator.subscribe(session, msg);
    }

    public void onAck(ControlSession session, Messages.Ack msg) {
        session.requireSlaveRole();
        this.replicator.onAck(session, msg);
    }

    /** RESYNC：以 fromRevision 为下限重拉维度内已提交对象（日志裁剪后的兜底路径）。 */
    public Messages.ResyncRes onResync(ControlSession session, Messages.ResyncReq msg) {
        session.requireSlaveRole();
        List<ChunkRef> chunks = this.store.chunksInDimension(msg.dimension());
        int replayed = 0;
        long lastCommitted = 0;
        for (ChunkRef key : chunks) {
            SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
            if (index == null || index.committed() < msg.fromRevision() || index.committed() < index.required()) {
                continue;
            }
            byte[] payload = this.store.loadCommittedPayload(key);
            if (payload == null) {
                continue;
            }
            Messages.SnapshotPush push = new Messages.SnapshotPush(new Messages.PutSnapshot(
                    msg.dimension(), key.x(), key.z(), index.committed(),
                    cn.cubegarden.icbm.caching.protocol.ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                    cn.cubegarden.icbm.caching.protocol.ProtocolConstants.COMPRESSION_GZIP,
                    this.store.committedUncompressedLength(key),
                    index.committedHash(), payload));
            session.sendWithSequence(cn.cubegarden.icbm.caching.protocol.MessageType.SNAPSHOT_PUSH,
                    0, this.store.dimensionEpoch(msg.dimension()),
                    this.eventLog.lastSequence() + 1L + replayed, encode(push));
            lastCommitted = index.committed();
            replayed++;
        }
        return new Messages.ResyncRes(msg.dimension(), msg.fromRevision(), lastCommitted, replayed);
    }

    public void markShuttingDown() {
        this.shuttingDown = true;
    }

    @Override
    public void close() {
        this.markShuttingDown();
        this.store.close();
    }

    private void raiseToNext(ChunkRef key, String reason) {
        SnapshotObjectStore.ChunkIndex current = this.store.indexOf(key);
        long next = current == null ? 1 : current.required() + 1;
        this.store.raiseRequired(key, next);
        long seq = this.eventLog.append(EventLog.EventType.INVALIDATE, key.dimension(), key,
                next, "", "", Messages.SpawnPoint.DEFAULT);
        this.replicator.onInvalidate(new Messages.Invalidate(key.dimension(), key.x(), key.z(),
                next, cn.cubegarden.icbm.caching.protocol.InvalidateReason.ADMIN), seq);
    }

    private static byte[] encode(Messages.SnapshotPush push) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.encode(
                    cn.cubegarden.icbm.caching.protocol.MessageType.SNAPSHOT_PUSH,
                    new java.io.DataOutputStream(bytes), push);
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("resync encode failed", e);
        }
    }
}

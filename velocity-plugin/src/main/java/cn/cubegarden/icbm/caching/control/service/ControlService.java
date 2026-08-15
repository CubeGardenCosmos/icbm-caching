package cn.cubegarden.icbm.caching.control.service;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.net.ControlSession;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
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
        LOGGER.info("[control] WORLD_EPOCH: dim={} epoch={} reason={}", msg.dimension(), msg.worldEpoch(), msg.reason());
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
        LOGGER.info("[control] 会话关闭注销: session={} reason={}", msg.sessionId(), msg.reason());
    }

    /** 装配时注入会话注册表（与 AuthGateway 共享同一实例；无则 SESSION_CLOSED 不注销）。 */
    public void setSessionRegistry(cn.cubegarden.icbm.caching.control.auth.SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
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

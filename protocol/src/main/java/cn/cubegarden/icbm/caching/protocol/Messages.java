package cn.cubegarden.icbm.caching.protocol;

/**
 * 消息体（CACHE_PROTOCOL.md §1 包络 body）。每种消息一个记录，编解码见
 * {@link MessageBodyCodec}（golden 测试锁定线上布局）。
 */
public final class Messages {

    private Messages() {
    }

    /** HELLO（角色、serverName、版本、指纹与能力；serverName 为缓存隔离键，见 CACHE_PROTOCOL §1）。 */
    public record Hello(
            Role role,
            String serverName,
            String minecraftVersion,
            int runtimeProtocol,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String modpackFingerprint,
            String registryAndDatapackFingerprint,
            byte capabilities
    ) {
    }

    /** HELLO_ACK（accepted=false 时 reason 非空，随后服务端断连）。 */
    public record HelloAck(boolean accepted, String reason, String serverId) {
    }

    /** WORLD_EPOCH：新 epoch 立即使对应维度旧对象不可读；spawn 为维度出生点（slave 放置玩家用，
     * 26.3 LevelData.RespawnData(GlobalPos, yaw, pitch) 快照）。 */
    public record WorldEpoch(
            DimensionRef dimension,
            long worldEpoch,
            EpochReason reason,
            String registryAndDatapackFingerprint,
            SpawnPoint spawn
    ) {
    }

    /** 维度出生点（slave join 时放置玩家的基准；失败时默认 0/64/0）。 */
    public record SpawnPoint(double x, double y, double z, float yaw, float pitch) {
        public static final SpawnPoint DEFAULT = new SpawnPoint(0.0, 64.0, 0.0, 0.0F, 0.0F);
    }

    /** INVALIDATE：先提高该区块 requiredRevision。 */
    public record Invalidate(
            DimensionRef dimension,
            int chunkX,
            int chunkZ,
            long revision,
            InvalidateReason reason
    ) {
    }

    /** PUT_SNAPSHOT：验证并持久化对象后原子写 committedRevision。 */
    public record PutSnapshot(
            DimensionRef dimension,
            int chunkX,
            int chunkZ,
            long revision,
            String snapshotFormat,
            String compression,
            int uncompressedLength,
            String payloadHash,
            byte[] payload
    ) {
    }

    /** PUT_SNAPSHOT_ACK：响应 PUT_SNAPSHOT（echo requestId）。 */
    public record PutSnapshotAck(
            DimensionRef dimension,
            int chunkX,
            int chunkZ,
            long revision,
            PutResult result
    ) {
    }

    /** RESOLVE_CHUNK 请求。 */
    public record ResolveChunkReq(
            DimensionRef dimension,
            int chunkX,
            int chunkZ,
            int expectedProtocol,
            String registryAndDatapackFingerprint,
            long minRevision
    ) {
    }

    /** RESOLVE_CHUNK 响应：只有 HIT 可装载为 committed snapshot 基线。 */
    public record ResolveChunkRes(
            DimensionRef dimension,
            int chunkX,
            int chunkZ,
            ResolveStatus status,
            long committedRevision,
            String objectId,
            String objectHash
    ) {
    }

    /** SUBSCRIBE：slave 订阅维度推送；ackedSequence 用于断线重连后的日志重放。 */
    public record Subscribe(DimensionRef[] dimensions, boolean pushEnabled, long ackedSequence) {
    }

    /** SNAPSHOT_PUSH：master→slave 已提交快照（body 与 PUT_SNAPSHOT 相同）。 */
    public record SnapshotPush(PutSnapshot snapshot) {
    }

    /** ACK：slave 确认收到 master 的 sequence（流控窗口推进）。 */
    public record Ack(long ackedSequence) {
    }

    /** RESYNC_REQ：slave 重拉（断线/重放缺口）。 */
    public record ResyncReq(DimensionRef dimension, long fromRevision) {
    }

    /** RESYNC_RES：重拉结果（replayedCount 为 0 且 lastCommittedRevision 非 0 表示已同步）。 */
    public record ResyncRes(
            DimensionRef dimension,
            long fromRevision,
            long lastCommittedRevision,
            int replayedCount
    ) {
    }

    /** HEARTBEAT：保活与滞后检测。 */
    public record Heartbeat(long sentAt, long lastSequence) {
    }

    /** ADMIN_INVALIDATE：chunk/region/dimension/world 范围失效。 */
    public record AdminInvalidate(
            DimensionRef dimension,
            String scope,          // CHUNK | REGION | DIMENSION | WORLD
            int chunkX,
            int chunkZ,
            int regionX,
            int regionZ,
            String reason
    ) {
    }

    /** ERROR：协议违规时由接收方发出并关闭通道。 */
    public record ErrorMessage(String reason) {
    }

    /** Mojang 正版 profile 属性（LOGIN_VERIFY_REQ.profileProperties 一项）。 */
    public record ProfileProperty(String name, String value, String signature) {
    }

    /** LOGIN_VERIFY_REQ（阶段A，slave→master）：登录开始，请求加密握手挑战。
     *  RSA 密钥对在 master（velocity master 终止登录，slave 只透传握手，CACHE_PROTOCOL §9）；
     *  profileProperties 不再随请求（由 Mojang hasJoinedServer 结果填充）。 */
    public record LoginVerifyReq(
            String playerName,
            byte[] playerUuid,
            String slaveId,
            String connectionInfo,
            byte[] nonce,
            long sentAt
    ) {
    }

    /** LOGIN_VERIFY_RES（阶段A响应，master→slave）：ok=true 附挑战与 master 公钥（slave 用它
     *  构造 ClientboundHelloPacket 发客户端，26.3 ServerLoginPacketListenerImpl.java:127）；
     *  ok=false 附拒绝原因。 */
    public record LoginVerifyRes(boolean ok, String reason, byte[] challenge, byte[] publicKeyDer) {
        public static LoginVerifyRes reject(String reason) {
            return new LoginVerifyRes(false, reason, new byte[0], new byte[0]);
        }
    }

    /** LOGIN_KEY_REQ（阶段B，slave→master）：递交客户端加密响应（ServerboundKeyPacket 原样字段：
     *  keybytes=加密会话密钥，encryptedChallenge=加密挑战；26.3 ServerboundKeyPacket.java 对照）。 */
    public record LoginKeyReq(
            byte[] playerUuid,
            byte[] nonce,
            byte[] keybytes,
            byte[] encryptedChallenge
    ) {
    }

    /** LOGIN_KEY_RES（阶段B响应，master→slave）：verified=true 附会话密钥（slave 以 AES/CFB8
     *  建 Cipher 解密客户端报文，Crypt.getCipher 对照）与身份断言（GameProfile 皮肤）；
     *  verified=false 附拒绝原因。 */
    public record LoginKeyRes(boolean verified, String reason, byte[] sharedSecret,
                              MasterVerifiedIdentity identity) {
        public static LoginKeyRes reject(String reason) {
            return new LoginKeyRes(false, reason, new byte[0], null);
        }
    }

    /** master 签发的身份断言（LOGIN_VERIFY_RES.VERIFIED 载荷；HIERARCHY §2）。 */
    public record MasterVerifiedIdentity(
            String issuer,
            String keyId,
            String audience,
            byte[] playerUuid,
            String sessionId,
            byte[] nonce,
            long expiresAt,
            byte[] signature,
            ProfileProperty[] profileProperties
    ) {
    }

    /** SESSION_CLOSED：slave 上报会话关闭，master 注销记账（防会话表泄漏）。 */
    public record SessionClosed(byte[] playerUuid, String sessionId, String reason) {
    }

    /** 乐观操作类型（阶段 5 白名单：仅 BREAK/PLACE；其余类型 slave 直接拒绝，不发提交）。 */
    public enum OperationType {
        BREAK(0),
        PLACE(1);

        private final int id;

        OperationType(int id) {
            this.id = id;
        }

        public int id() {
            return this.id;
        }

        /** 未知类型返回 null（调用方必须拒绝，不猜测）。 */
        public static OperationType fromId(int id) {
            for (OperationType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return null;
        }
    }

    /** SUBMIT_OPERATION：slave 提交单格乐观操作（源服主线程幂等裁决；预测永不信任）。 */
    public record SubmitOperation(
            byte[] operationId,          // UUID（16 字节）；源服以它去重/幂等
            byte[] playerId,             // UUID（16 字节）；模拟玩家或真实玩家标识
            String virtualSessionId,     // 会话标识（master 路由回发起 slave）
            long clientInteractionSequence,
            OperationType operationType, // BREAK | PLACE
            DimensionRef dimension,
            int posX,
            int posY,
            int posZ,
            long baseRevision,           // slave 看到的目标 chunk committed revision（快速拒绝，非唯一冲突键）
            String preconditions,        // 目标方块状态哈希（sha256(state)）；源服与权威状态比对
            String placeState,           // PLACE 的目标方块状态 id（如 "minecraft:diamond_block"；BREAK 为空）
            long sentAt
    ) {
    }

    /** OPERATION_COMMITTED：源服权威执行成功（canonical delta = 执行后权威状态 + revision）。 */
    public record OperationCommitted(
            byte[] operationId,
            String canonicalState,       // 执行后 getBlockState 的权威状态 id
            long chunkRevision,          // 该 chunk 新 revision（脏标记递增后）
            long epoch,
            long committedAt
    ) {
    }

    /** OPERATION_REJECTED：源服裁决失败（前置条件不满足/类型禁用/执行失败）。 */
    public record OperationRejected(
            byte[] operationId,
            String reason,
            DimensionRef dimension,
            int[] resyncChunkX,          // 完整纠正范围（slave RESYNC 该区块，不做单方块包）
            int[] resyncChunkZ,
            long rejectedAt
    ) {
    }

    /** GET_OPERATION_STATUS：超时后查询操作终态（操作超时不等于失败）。 */
    public record GetOperationStatus(byte[] operationId) {
    }

    /** OPERATION_STATUS_RES：终态查询结果。 */
    public record OperationStatusRes(
            byte[] operationId,
            OperationStatus status,      // UNKNOWN | PENDING | COMMITTED | REJECTED
            String canonicalState,       // COMMITTED 时有效
            long chunkRevision,
            String rejectReason,
            DimensionRef dimension,
            int[] resyncChunkX,
            int[] resyncChunkZ
    ) {
        public enum OperationStatus {
            UNKNOWN(0),
            PENDING(1),
            COMMITTED(2),
            REJECTED(3);

            private final int id;

            OperationStatus(int id) {
                this.id = id;
            }

            public int id() {
                return this.id;
            }

            public static OperationStatus fromId(int id) {
                for (OperationStatus status : values()) {
                    if (status.id == id) {
                        return status;
                    }
                }
                return null;
            }
        }
    }
}

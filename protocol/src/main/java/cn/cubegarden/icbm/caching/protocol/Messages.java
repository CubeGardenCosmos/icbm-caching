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

    /** LOGIN_VERIFY_REQ：slave 终止登录握手后把验证材料经封装通道递交给 master（CACHE_PROTOCOL §9）。 */
    public record LoginVerifyReq(
            String playerName,
            byte[] playerUuid,
            ProfileProperty[] profileProperties,
            String serverHash,
            byte[] sharedSecret,
            String slaveId,
            String connectionInfo,
            byte[] nonce,
            long sentAt
    ) {
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
            byte[] signature
    ) {
    }

    /** LOGIN_VERIFY_RES：验证结果（VERIFIED 含断言；REJECTED 含 reason）。 */
    public record LoginVerifyRes(boolean verified, String reason, MasterVerifiedIdentity identity) {
    }

    /** SESSION_CLOSED：slave 上报会话关闭，master 注销记账（防会话表泄漏）。 */
    public record SessionClosed(byte[] playerUuid, String sessionId, String reason) {
    }
}

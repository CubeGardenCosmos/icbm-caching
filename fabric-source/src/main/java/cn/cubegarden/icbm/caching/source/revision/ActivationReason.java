package cn.cubegarden.icbm.caching.source.revision;

/**
 * worldEpoch 激活原因（对应 CACHE_PROTOCOL.md WORLD_EPOCH.activationReason）。
 */
public enum ActivationReason {
    /** 新世界/首次启动。 */
    START,
    /** 世界回档或状态无法证明单调（缺失/损坏）。 */
    ROLLBACK,
    /** 模组或数据包变动（fingerprint 变化）。 */
    MODPACK_CHANGE,
    /** 管理员范围失效。 */
    ADMIN,
}

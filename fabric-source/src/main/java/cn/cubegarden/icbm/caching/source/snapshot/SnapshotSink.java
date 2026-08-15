package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;

/**
 * 快照落点接口，镜像 CACHE_PROTOCOL.md 的快照消息顺序：
 * INVALIDATE（raiseRequiredRevision）→ PUT_SNAPSHOT（putSnapshot）→ committedRevision。
 *
 * <p>阶段1 默认实现 {@link LocalSnapshotSink}（本地对象库，委托协议模块
 * {@link cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore}）；阶段2 默认
 * {@link RemoteSnapshotSink}（控制服务远端）。put/commit 为异步回调接口：本地 sink
 * 内联完成回调，远端 sink 在控制通道线程完成——提交流程状态机由
 * {@link SnapshotPublisher} 显式驱动（AGENTS.md「显式状态机」）。
 */
public interface SnapshotSink extends AutoCloseable {

    enum PutResult {
        OK,
        REJECTED_OUT_OF_ORDER,
        REJECTED_EPOCH_MISMATCH,
        REJECTED_HASH_MISMATCH,
        REJECTED_SIZE_MISMATCH,
        REJECTED_UNSUPPORTED,
        UNAVAILABLE,
    }

    interface PutCallback {
        void onResult(ChunkKey key, long revision, PutResult result);
    }

    interface CommitCallback {
        void onCommitted(ChunkKey key, long revision, boolean ok);
    }

    /**
     * 落点当前是否可用。false 时发布器不得 begin（fail closed：
     * 控制服务不可用时不得产生不确定基线）。
     */
    boolean available();

    /** 先提高 requiredRevision（对应 INVALIDATE）：异步入队，不阻塞调用线程。 */
    void raiseRequiredRevision(ChunkKey key, long revision);

    /** 持久化并校验快照（对应 PUT_SNAPSHOT）；完成回调在任意线程触发。 */
    void putSnapshot(ChunkSnapshot snapshot, PutCallback done);

    /** 原子设置 committedRevision（对应控制服务验证后提交）。 */
    void commitRevision(ChunkKey key, long revision, CommitCallback done);

    /** 维度 epoch 切换（对应 WORLD_EPOCH）：新 epoch 使旧对象不可读；spawn 为维度出生点。 */
    void announceEpoch(DimensionRef dimension, long epoch, String reason, String registryFingerprint,
                       Messages.SpawnPoint spawn);

    /** 乐观操作结果回发（OPERATION_COMMITTED/REJECTED/STATUS_RES；里程碑 7 阶段 5）。
     *  本地 sink（开发模式）为 no-op——操作队列仍记录终态（测试可直接查询）。 */
    default boolean sendOperationResult(cn.cubegarden.icbm.caching.protocol.MessageType type, byte[] body) {
        return false;
    }

    @Override
    void close();
}

package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.source.revision.ChunkKey;

/**
 * 快照落点接口，镜像 CACHE_PROTOCOL.md 的快照消息顺序：
 * INVALIDATE（raiseRequiredRevision）→ PUT_SNAPSHOT（putSnapshot）→ committedRevision。
 *
 * <p>阶段1 默认实现 {@link LocalSnapshotSink}（本地对象库，含 required/committed 标记与
 * 哈希/大小/epoch/顺序校验）；阶段2 以控制服务远端 sink 替换，消息语义不变。
 */
public interface SnapshotSink extends AutoCloseable {

    enum PutResult {
        OK,
        REJECTED_OUT_OF_ORDER,
        REJECTED_EPOCH_MISMATCH,
        REJECTED_HASH_MISMATCH,
        REJECTED_SIZE_MISMATCH,
    }

    /**
     * 先提高 requiredRevision（对应 INVALIDATE）：使旧 revision 快照立即不可作为基线，
     * 之后新快照才可读。
     */
    void raiseRequiredRevision(ChunkKey key, long revision);

    /** 持久化并校验快照（对应 PUT_SNAPSHOT）。失败时保持 required，不得提交。 */
    PutResult putSnapshot(ChunkSnapshot snapshot);

    /** 原子设置 committedRevision（对应控制服务验证后提交）。 */
    void commitRevision(ChunkKey key, long revision);

    @Override
    void close();
}

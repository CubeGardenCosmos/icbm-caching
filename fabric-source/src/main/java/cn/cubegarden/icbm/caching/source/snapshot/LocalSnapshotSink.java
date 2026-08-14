package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 本地对象库 sink（无控制服务的开发模式）：委托协议模块
 * {@link SnapshotObjectStore}（唯一磁盘格式实现，两侧复用，AGENTS.md「避免重复实现」）。
 *
 * <p>校验失败（哈希/大小/epoch/顺序）返回对应拒绝原因且不落盘新快照；控制服务不可用时
 * 由上层 fail closed（保持 required，虚拟后端不得以旧基线服务）。
 * 本地 sink 的 put/commit 回调内联完成（同步语义）。
 */
public final class LocalSnapshotSink implements SnapshotSink {

    private final SnapshotObjectStore store;

    public LocalSnapshotSink(Path root) {
        this.store = new SnapshotObjectStore(root);
        try {
            this.store.recover();
        } catch (IOException e) {
            throw new IllegalStateException("本地对象库恢复失败: " + root, e);
        }
    }

    @Override
    public boolean available() {
        return this.store.available();
    }

    @Override
    public void raiseRequiredRevision(ChunkKey key, long revision) {
        this.store.raiseRequired(toRef(key), revision);
    }

    @Override
    public void putSnapshot(ChunkSnapshot snapshot, PutCallback done) {
        ChunkRef ref = toRef(snapshot.key());
        cn.cubegarden.icbm.caching.protocol.PutResult result = this.store.put(
                ref, snapshot.revision(), snapshot.epoch(),
                snapshot.snapshotFormat(), snapshot.compression(),
                snapshot.uncompressedLength(), snapshot.payloadHash(), snapshot.payload());
        done.onResult(snapshot.key(), snapshot.revision(), map(result));
    }

    @Override
    public void commitRevision(ChunkKey key, long revision, CommitCallback done) {
        try {
            this.store.commit(toRef(key), revision);
            done.onCommitted(key, revision, true);
        } catch (IllegalStateException e) {
            done.onCommitted(key, revision, false);
        }
    }

    @Override
    public void announceEpoch(DimensionRef dimension, long epoch, String reason, String registryFingerprint) {
        this.store.announceEpoch(dimension, epoch);
    }

    @Override
    public void close() {
        this.store.close();
    }

    private static ChunkRef toRef(ChunkKey key) {
        return ChunkRef.of(new DimensionRef(key.dimension().getNamespace(), key.dimension().getPath()),
                key.x(), key.z());
    }

    private static SnapshotSink.PutResult map(cn.cubegarden.icbm.caching.protocol.PutResult result) {
        return switch (result) {
            case OK -> SnapshotSink.PutResult.OK;
            case REJECTED_OUT_OF_ORDER -> SnapshotSink.PutResult.REJECTED_OUT_OF_ORDER;
            case REJECTED_EPOCH_MISMATCH -> SnapshotSink.PutResult.REJECTED_EPOCH_MISMATCH;
            case REJECTED_HASH_MISMATCH -> SnapshotSink.PutResult.REJECTED_HASH_MISMATCH;
            case REJECTED_SIZE_MISMATCH -> SnapshotSink.PutResult.REJECTED_SIZE_MISMATCH;
            case REJECTED_UNSUPPORTED -> SnapshotSink.PutResult.REJECTED_UNSUPPORTED;
            case REJECTED_UNAVAILABLE -> SnapshotSink.PutResult.UNAVAILABLE;
        };
    }
}

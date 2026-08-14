package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.source.CacheGate;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提交流程状态机（草稿 phase1-source-revision §3 与 phase2-control-protocol §4.1，
 * 显式状态机，AGENTS.md）：
 *
 * <pre>
 * IDLE --begin(key,rev)--> REQUIRED --put(snapshot)--> AWAITING_PUT
 * AWAITING_PUT --sink cb(OK)--> AWAITING_COMMIT --sink cb(ok)--> COMMITTED -->(终态自清理)--> IDLE
 * AWAITING_PUT --sink cb(拒)--> FAILED -->(自清理)--> IDLE
 * </pre>
 *
 * <p>任意校验失败都不得 commit（CACHE_PROTOCOL：「校验失败时关闭缓存命中或操作通道」）；
 * {@code begin} 在闸门关闭或 sink 不可用（fail closed）时返回 false，由 ticker 保持
 * dirty 重试。终态自动清理（远端 sink 回调异步到达后无 ticker 轮询也能继续下一轮）。
 */
public final class SnapshotPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotPublisher.class);

    private enum Phase {
        IDLE, REQUIRED, AWAITING_PUT, AWAITING_COMMIT, FAILED, COMMITTED,
    }

    private final SnapshotSink sink;
    private final CacheGate gate;
    private final Map<ChunkKey, Phase> phases = new ConcurrentHashMap<>();

    public SnapshotPublisher(SnapshotSink sink, CacheGate gate) {
        this.sink = sink;
        this.gate = gate;
    }

    /** 开始一轮提交：先提高 requiredRevision。返回 false 表示闸门关闭、sink 不可用或上一轮未结束。 */
    public boolean begin(ChunkKey key, long revision) {
        if (!this.gate.isEnabled()) {
            return false;
        }
        if (!this.sink.available()) {
            LOGGER.warn("[icbm-source] 快照落点不可用（fail closed，保持 dirty 重试）: {}", key);
            return false;
        }
        Phase previous = this.phases.putIfAbsent(key, Phase.REQUIRED);
        if (previous != null) {
            return false;
        }
        this.sink.raiseRequiredRevision(key, revision);
        return true;
    }

    /**
     * 提交快照（异步）：sink 回调驱动状态迁移；终态（COMMITTED/FAILED）自动清理。
     * 本地 sink 回调内联（同步语义）；远端 sink 回调在控制通道线程。
     */
    public void put(ChunkSnapshot snapshot) {
        Phase phase = this.phases.get(snapshot.key());
        if (phase != Phase.REQUIRED) {
            return;
        }
        this.phases.put(snapshot.key(), Phase.AWAITING_PUT);
        ChunkKey key = snapshot.key();
        this.sink.putSnapshot(snapshot, (k, revision, result) -> {
            if (result == SnapshotSink.PutResult.OK) {
                if (this.phases.get(k) != Phase.AWAITING_PUT) {
                    return; // 已被取消/替换
                }
                this.phases.put(k, Phase.AWAITING_COMMIT);
                this.sink.commitRevision(k, revision, (k2, rev2, ok) -> {
                    if (this.phases.get(k2) == Phase.AWAITING_COMMIT) {
                        this.phases.put(k2, ok ? Phase.COMMITTED : Phase.FAILED);
                        if (!ok) {
                            LOGGER.error("[icbm-source] committed 确认失败（fail closed）: {} rev={}", k2, rev2);
                        }
                        this.phases.remove(k2); // 终态自清理（endTick 幂等兜底）
                    }
                });
            } else {
                if (this.phases.get(k) == Phase.AWAITING_PUT) {
                    LOGGER.error("[icbm-source] 快照被拒（fail closed，保持 required）: {} rev={} result={}",
                            k, revision, result);
                    this.phases.remove(k);
                }
            }
        });
    }

    /** 兼容调用（本地同步路径回调已内联完成时 no-op）；终态迁移由 sink 回调驱动。 */
    public void commit(ChunkKey key, long revision) {
        // 无额外动作：put 成功回调链内已触发 sink.commitRevision
    }

    /** tick 末尾清理（终态已被回调自清理时 no-op；保留以兼容同步路径测试）。 */
    public void endTick(ChunkKey key) {
        this.phases.remove(key);
    }

    /**
     * 区块已修改但来不及发布快照（如卸载）：先失效后提交原则（ARCHITECTURE §4.3）——
     * 保持 required（旧快照不可读）且不 commit，等待下次加载+修改后补快照。
     */
    public void failWithoutSnapshot(ChunkKey key) {
        if (this.phases.get(key) == Phase.REQUIRED) {
            this.phases.remove(key);
        }
    }

    public SnapshotSink sink() {
        return this.sink;
    }

    public boolean isEnabled() {
        return this.gate.isEnabled();
    }

    public String disabledReason() {
        return this.gate.disabledReason();
    }
}

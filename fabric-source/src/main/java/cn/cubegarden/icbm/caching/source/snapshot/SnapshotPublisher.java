package cn.cubegarden.icbm.caching.source.snapshot;

import cn.cubegarden.icbm.caching.source.CacheGate;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提交流程状态机（草稿 phase1-source-revision §3，显式状态机，AGENTS.md）：
 *
 * <pre>
 * IDLE --begin(revision)--> REQUIRED --put(snapshot)--> PUBLISHED --commit--> COMMITTED --endTick--> IDLE
 *                               |                             |
 *                               +--校验失败--> FAILED --endTick--> IDLE（fail closed：保持 required）
 * </pre>
 *
 * <p>任意校验失败都不得 commit（CACHE_PROTOCOL：「校验失败时关闭缓存命中或操作通道」）。
 * 每 chunk 同一时刻至多一个 pending（本地同步 sink 当 tick 完成；远端 sink 在终态前
 * 拒绝新 begin，见 ARCHITECTURE.md「每名玩家每个区块只允许一个 pending operation」的区块维度对应）。
 */
public final class SnapshotPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotPublisher.class);

    private enum Phase {
        IDLE, REQUIRED, PUBLISHED, FAILED, COMMITTED,
    }

    private final SnapshotSink sink;
    private final CacheGate gate;
    private final Map<ChunkKey, Phase> phases = new ConcurrentHashMap<>();

    public SnapshotPublisher(SnapshotSink sink, CacheGate gate) {
        this.sink = sink;
        this.gate = gate;
    }

    /** 开始一轮提交：先提高 requiredRevision。返回 false 表示闸门关闭或上一轮未结束。 */
    public boolean begin(ChunkKey key, long revision) {
        if (!this.gate.isEnabled()) {
            return false;
        }
        Phase previous = this.phases.putIfAbsent(key, Phase.REQUIRED);
        if (previous != null) {
            return false;
        }
        this.sink.raiseRequiredRevision(key, revision);
        return true;
    }

    /** 持久化并校验快照；失败时 fail closed（保持 required，不 commit）。 */
    public void put(ChunkSnapshot snapshot) {
        if (this.phases.get(snapshot.key()) != Phase.REQUIRED) {
            return;
        }
        SnapshotSink.PutResult result = this.sink.putSnapshot(snapshot);
        if (result == SnapshotSink.PutResult.OK) {
            this.phases.put(snapshot.key(), Phase.PUBLISHED);
        } else {
            this.phases.put(snapshot.key(), Phase.FAILED);
            LOGGER.error("[icbm-source] 快照被拒（fail closed，保持 required）: key={} revision={} result={}",
                    snapshot.key(), snapshot.revision(), result);
        }
    }

    /** 提交 committedRevision（仅 PUBLISHED 后有效）。 */
    public void commit(ChunkKey key, long revision) {
        if (this.phases.get(key) != Phase.PUBLISHED) {
            return;
        }
        this.sink.commitRevision(key, revision);
        this.phases.put(key, Phase.COMMITTED);
    }

    /** tick 末尾清理已终态（COMMITTED/FAILED）；REQUIRED/PUBLISHED 留给远端 sink 的下一 tick。 */
    public void endTick(ChunkKey key) {
        Phase phase = this.phases.get(key);
        if (phase == Phase.COMMITTED || phase == Phase.FAILED) {
            this.phases.remove(key);
        }
    }

    /**
     * 区块已修改但来不及发布快照（如卸载）：先失效后提交原则（ARCHITECTURE §4.3）——
     * 保持 required（旧快照不可读）且不 commit，等待下次加载+修改后补快照。
     */
    public void failWithoutSnapshot(ChunkKey key) {
        if (this.phases.get(key) == Phase.REQUIRED) {
            this.phases.put(key, Phase.FAILED);
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

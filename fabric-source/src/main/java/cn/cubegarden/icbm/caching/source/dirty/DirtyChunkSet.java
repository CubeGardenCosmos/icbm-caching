package cn.cubegarden.icbm.caching.source.dirty;

import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 tick 待发布区块集合（合并语义）：同一区块多次标记只保留一条记录，
 * 由 tick 末尾 {@link #drain()} 一次性消费（草稿 phase1-source-revision §3）。
 */
public final class DirtyChunkSet {

    private final Map<ChunkKey, Boolean> dirty = new ConcurrentHashMap<>();

    /** 标记区块为脏（幂等，可重入；Mixin 权威路径与诊断事件都会调用）。 */
    public void mark(ChunkKey key) {
        this.dirty.put(key, Boolean.TRUE);
    }

    /** 取出并清空当前全部脏区块（tick 末尾调用）。 */
    public Set<ChunkKey> drain() {
        Set<ChunkKey> out = new HashSet<>(this.dirty.keySet());
        this.dirty.clear();
        return out;
    }

    /** 若键在集合中则移除并返回 true（区块卸载时失效使用）。 */
    public boolean remove(ChunkKey key) {
        return this.dirty.remove(key) != null;
    }

    public boolean contains(ChunkKey key) {
        return this.dirty.containsKey(key);
    }

    public int size() {
        return this.dirty.size();
    }

    public boolean isEmpty() {
        return this.dirty.isEmpty();
    }
}

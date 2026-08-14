package cn.cubegarden.icbm.caching.velocity;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;

/**
 * slave 服务闸门（fail closed，验收 4：落后的 slave 不会服务低于 master requiredRevision
 * 的区块；控制服务不可用时拒绝不确定基线）。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>与 master 的连接断开 → {@link Status#UNAVAILABLE}（无法证明 required 水位最新）；</li>
 *   <li>无 committed 基线或损坏 → {@link Status#UNAVAILABLE}；</li>
 *   <li>committed &lt; required（master 已失效但快照未到）→ {@link Status#STALE}（落后）；</li>
 *   <li>对象 epoch 与维度当前 epoch 不一致 → {@link Status#UNAVAILABLE}。</li>
 * </ol>
 * 只有 {@link Status#SERVABLE} 可被虚拟后端装载为 committed snapshot 基线。
 */
public final class ServeGate {

    public enum Status {
        SERVABLE, STALE, UNAVAILABLE,
    }

    private final SnapshotObjectStore store;
    private volatile boolean masterConnected;

    public ServeGate(SnapshotObjectStore store) {
        this.store = store;
    }

    public void setMasterConnected(boolean connected) {
        this.masterConnected = connected;
    }

    public boolean masterConnected() {
        return this.masterConnected;
    }

    public Status status(ChunkRef key) {
        if (!this.masterConnected) {
            return Status.UNAVAILABLE; // 控制服务不可用：拒绝不确定基线
        }
        SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
        if (index == null || index.committed() <= 0 || index.corrupt()) {
            return Status.UNAVAILABLE;
        }
        if (index.committed() < index.required()) {
            return Status.STALE; // 落后：低于 master requiredRevision
        }
        long dimensionEpoch = this.store.dimensionEpoch(key.dimension());
        if (dimensionEpoch > 0 && index.committedEpoch() != dimensionEpoch) {
            return Status.UNAVAILABLE; // epoch 不匹配：不得装载旧 epoch 对象
        }
        return Status.SERVABLE;
    }
}

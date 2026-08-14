package cn.cubegarden.icbm.caching.source.revision;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * 源状态门面：worldEpoch 与 chunk revision 的全部领域逻辑（持久化于 {@link SourceState}）。
 *
 * <p>epoch 规则（草稿 phase1-source-revision §3）：
 * <ul>
 *   <li>首次启动（无状态文件）→ 各维度首次访问时以 START 建 epoch。</li>
 *   <li>状态缺失/损坏且存在 level.dat（可能回档）→ 以 ROLLBACK 建 epoch。</li>
 *   <li>modpackFingerprint 与已存不同 → 所有维度 MODPACK_CHANGE 新 epoch。</li>
 *   <li>管理员命令 → ADMIN 新 epoch（{@link #newEpoch}）。</li>
 * </ul>
 * 新 epoch 使该维度旧 revision 全部失效（CACHE_PROTOCOL WORLD_EPOCH 语义），因此
 * revision 重置是安全的；状态正常恢复时 revision 保持单调（验收：重启后不倒退）。
 */
public final class SourceStateService {

    private final SourceState state;
    private final String currentFingerprint;
    private final ActivationReason initialReason;

    private SourceStateService(SourceState state, String currentFingerprint, ActivationReason initialReason) {
        this.state = state;
        this.currentFingerprint = currentFingerprint;
        this.initialReason = initialReason;
    }

    /**
     * 从 {@link SavedDataStorage} 装载/创建状态（26.3 SavedDataStorage.java 对照）。
     *
     * @param existingWorld level.dat 是否存在（用于区分 ROLLBACK/START）
     */
    public static SourceStateService load(
            SavedDataStorage storage,
            String currentFingerprint,
            boolean existingWorld
    ) {
        SourceState loaded = storage.get(SourceState.TYPE);
        SourceState state;
        if (loaded == null) {
            // 文件缺失或解析失败：无法证明 revision 单调 → 全新状态（epoch 按需创建）
            state = new SourceState();
            storage.set(SourceState.TYPE, state);
        } else {
            state = loaded;
        }
        SourceStateService service = new SourceStateService(
                state, currentFingerprint,
                existingWorld ? ActivationReason.ROLLBACK : ActivationReason.START
        );
        service.checkModpackFingerprint();
        return service;
    }

    public SourceState state() {
        return this.state;
    }

    public String currentFingerprint() {
        return this.currentFingerprint;
    }

    /** 当前维度的 epoch；不存在则按初始原因创建（START/ROLLBACK）。 */
    public long epochFor(Identifier dimension) {
        Long existing = this.state.epochs().get(dimension);
        if (existing != null) {
            return existing;
        }
        return this.newEpoch(dimension, this.initialReason);
    }

    /** 手动创建新 epoch（ADMIN 或指纹变化时使用）。 */
    public long newEpoch(Identifier dimension, ActivationReason reason) {
        long id = this.state.nextEpochId() + 1;
        this.state.setNextEpochId(id);
        this.state.epochs().put(dimension, id);
        this.state.recordEpochTransition(
                dimension, new SourceState.EpochTransition(id, reason, System.currentTimeMillis())
        );
        return id;
    }

    /** 读取区块 revision（从未修改为 0）。 */
    public long revisionOf(ChunkKey key) {
        Map<String, Long> dimensionRevisions = this.state.revisions().get(key.dimension());
        return dimensionRevisions == null ? 0L : dimensionRevisions.getOrDefault(key.keyString(), 0L);
    }

    /** 每 tick 每区块合并后递增 revision（草稿 §3：每个 committed 快照对应一次递增）。 */
    public long nextRevision(ChunkKey key) {
        long next = this.revisionOf(key) + 1;
        this.state.revisions()
                .computeIfAbsent(key.dimension(), ignored -> new HashMap<>())
                .put(key.keyString(), next);
        this.state.setDirty();
        return next;
    }

    /** 首次记录指纹；指纹变化 → 所有已知维度 MODPACK_CHANGE 新 epoch 并更新指纹。 */
    private void checkModpackFingerprint() {
        String stored = this.state.modpackFingerprint();
        if (stored.isEmpty()) {
            this.state.setModpackFingerprint(this.currentFingerprint);
            return;
        }
        if (!stored.equals(this.currentFingerprint)) {
            for (Identifier dimension : this.state.epochs().keySet()) {
                this.newEpoch(dimension, ActivationReason.MODPACK_CHANGE);
            }
            this.state.setModpackFingerprint(this.currentFingerprint);
        }
    }
}

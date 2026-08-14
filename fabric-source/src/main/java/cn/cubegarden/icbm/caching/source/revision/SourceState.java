package cn.cubegarden.icbm.caching.source.revision;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 源模组持久化状态（26.3 SavedDataStorage codec 模型，SavedDataStorage.java 为对照）：
 *
 * <ul>
 *   <li>epochs：每个维度一个 worldEpoch（对应 CACHE_PROTOCOL.md WORLD_EPOCH 的 dimensionId + worldEpoch）。</li>
 *   <li>revisions：每维度每区块（"x,z"）的 revision。</li>
 *   <li>nextEpochId：epoch 单调计数器。</li>
 *   <li>modpackFingerprint：模组/数据包指纹，变化时所有维度进入 MODPACK_CHANGE epoch。</li>
 * </ul>
 *
 * <p>重启后从磁盘恢复即保持 revision 单调；状态缺失/损坏时由
 * {@link SourceStateService} 产生新 epoch（旧快照按 epoch 失效，重置安全）。
 */
public final class SourceState extends SavedData {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("icbm_caching", "source_state");

    private static final Codec<Map<String, Long>> CHUNK_REVISIONS =
            Codec.unboundedMap(Codec.STRING, Codec.LONG);

    public static final Codec<SourceState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.LONG)
                    .fieldOf("epochs").forGetter(SourceState::epochs),
            Codec.unboundedMap(Identifier.CODEC, CHUNK_REVISIONS)
                    .fieldOf("revisions").forGetter(SourceState::revisions),
            Codec.unboundedMap(Identifier.CODEC, EpochTransition.CODEC)
                    .fieldOf("epoch_transitions").forGetter(SourceState::epochTransitions),
            Codec.LONG.fieldOf("next_epoch_id").forGetter(SourceState::nextEpochId),
            Codec.STRING.fieldOf("modpack_fingerprint").forGetter(SourceState::modpackFingerprint)
    ).apply(instance, SourceState::new));

    public static final SavedDataType<SourceState> TYPE =
            new SavedDataType<>(ID, SourceState::new, CODEC, DataFixTypes.SAVED_DATA_SCOREBOARD);

    /** epoch 转换审计（原因 → 时间戳）。 */
    public record EpochTransition(long epoch, ActivationReason reason, long timestampMillis) {

        private static final Codec<ActivationReason> REASON_CODEC =
                Codec.STRING.xmap(ActivationReason::valueOf, Enum::name);

        public static final Codec<EpochTransition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("epoch").forGetter(EpochTransition::epoch),
                REASON_CODEC.fieldOf("reason").forGetter(EpochTransition::reason),
                Codec.LONG.fieldOf("timestamp").forGetter(EpochTransition::timestampMillis)
        ).apply(instance, EpochTransition::new));
    }

    private final Map<Identifier, Long> epochs = new HashMap<>();
    private final Map<Identifier, Map<String, Long>> revisions = new HashMap<>();
    private final Map<Identifier, EpochTransition> epochTransitions = new HashMap<>();
    private long nextEpochId;
    private String modpackFingerprint = "";

    /** codec 构造（保存路径）。 */
    public SourceState() {
    }

    /** codec 反序列化构造。 */
    private SourceState(
            Map<Identifier, Long> epochs,
            Map<Identifier, Map<String, Long>> revisions,
            Map<Identifier, EpochTransition> epochTransitions,
            long nextEpochId,
            String modpackFingerprint
    ) {
        this.epochs.putAll(epochs);
        // codec 解码产物为不可变 map：深拷贝内层，保证后续可变（unboundedMap 行为）
        revisions.forEach((dimension, values) -> this.revisions.put(dimension, new HashMap<>(values)));
        this.epochTransitions.putAll(epochTransitions);
        this.nextEpochId = nextEpochId;
        this.modpackFingerprint = modpackFingerprint;
    }

    public Map<Identifier, Long> epochs() {
        return this.epochs;
    }

    public Map<Identifier, Map<String, Long>> revisions() {
        return this.revisions;
    }

    public long nextEpochId() {
        return this.nextEpochId;
    }

    public void setNextEpochId(long nextEpochId) {
        this.nextEpochId = nextEpochId;
        this.setDirty();
    }

    public String modpackFingerprint() {
        return this.modpackFingerprint;
    }

    public void setModpackFingerprint(String fingerprint) {
        this.modpackFingerprint = fingerprint;
        this.setDirty();
    }

    public Map<Identifier, EpochTransition> epochTransitions() {
        return this.epochTransitions;
    }

    public EpochTransition epochTransition(Identifier dimension) {
        return this.epochTransitions.get(dimension);
    }

    public void recordEpochTransition(Identifier dimension, EpochTransition transition) {
        this.epochTransitions.put(dimension, transition);
        this.setDirty();
    }
}

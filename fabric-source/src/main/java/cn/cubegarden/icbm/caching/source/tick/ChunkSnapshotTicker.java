package cn.cubegarden.icbm.caching.source.tick;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.source.CacheGate;
import cn.cubegarden.icbm.caching.source.dirty.DirtyTracker;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import cn.cubegarden.icbm.caching.source.revision.SourceState;
import cn.cubegarden.icbm.caching.source.revision.SourceStateService;
import cn.cubegarden.icbm.caching.source.snapshot.ChunkSnapshot;
import cn.cubegarden.icbm.caching.source.snapshot.SnapshotPublisher;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每 tick 末合并发布器（ServerTickEvents.END_SERVER_TICK 驱动）：
 * drain 脏集合 → 每 chunk 一次 revision 递增 + 一个稳定快照 → 提交流程。
 *
 * <p>序列化/压缩在后台 executor（与 ChunkMap.save 中
 * {@code supplyAsync(data::write, Util.backgroundExecutor())} 一致，ChunkMap.java 对照），
 * 不阻塞源服主线程 tick 热路径；revision/epoch 判定与发布回主线程。
 *
 * <p>epoch 变化（新 epoch 或首次启动）→ 先向 sink 声明 WORLD_EPOCH（新 epoch 使旧对象
 * 不可读）；sink 不可用时 begin 失败 → 保持 dirty 重试（fail closed）。
 */
public final class ChunkSnapshotTicker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkSnapshotTicker.class);

    private final SourceStateService service;
    private final SnapshotPublisher publisher;
    private final DirtyTracker tracker;
    private final CacheGate gate;
    private final String registryAndDatapackFingerprint;
    private final Map<Identifier, Long> announcedEpochs = new HashMap<>();

    public ChunkSnapshotTicker(
            SourceStateService service,
            SnapshotPublisher publisher,
            DirtyTracker tracker,
            CacheGate gate,
            String registryAndDatapackFingerprint
    ) {
        this.service = service;
        this.publisher = publisher;
        this.tracker = tracker;
        this.gate = gate;
        this.registryAndDatapackFingerprint = registryAndDatapackFingerprint;
    }

    public void onEndServerTick(MinecraftServer server) {
        if (!this.gate.isEnabled()) {
            return;
        }
        Set<ChunkKey> keys = this.tracker.dirtySet().drain();
        for (ChunkKey key : keys) {
            this.publish(server, key);
        }
    }

    /** 首次启动时声明各已知维度的当前 epoch（sink 恢复维度 epoch 基线）。 */
    public void announceInitialEpochs() {
        for (Map.Entry<Identifier, Long> entry : this.service.state().epochs().entrySet()) {
            this.announceEpoch(entry.getKey(), entry.getValue());
        }
    }

    private void publish(MinecraftServer server, ChunkKey key) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, key.dimension()));
        if (level == null) {
            return;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk levelChunk)) {
            // 未加载/未 FULL：跳过（卸载保存路径处理；下次修改会重新标记）
            return;
        }

        long epoch = this.service.epochFor(key.dimension());
        this.announceEpoch(key.dimension(), epoch);
        long revision = this.service.nextRevision(key);
        if (!this.publisher.begin(key, revision)) {
            // 闸门关闭或 sink 不可用：保持 dirty，重连/恢复后补发（fail closed）
            this.tracker.dirtySet().mark(key);
            return;
        }

        // 主线程拷贝（ChunkMap.save 同款：copyOf 读主线程状态）
        SerializableChunkData data = SerializableChunkData.copyOf(level, levelChunk);
        CompletableFuture.supplyAsync(() -> serialize(key, revision, epoch, data), Util.backgroundExecutor())
                .thenAccept(snapshot -> {
                    if (!server.isRunning()) {
                        return; // 服务器已停止：丢弃未发布结果（终态由下一次启动的 epoch 保证）
                    }
                    server.execute(() -> {
                        this.publisher.put(snapshot);
                        this.publisher.commit(snapshot.key(), snapshot.revision());
                        this.publisher.endTick(snapshot.key());
                        LOGGER.debug("[icbm-source] 已提交快照: {} rev={} epoch={}",
                                snapshot.key(), snapshot.revision(), snapshot.epoch());
                    });
                });
    }

    /** epoch 变化/首次时向 sink 声明 WORLD_EPOCH（幂等：同 epoch 只声明一次）。 */
    private synchronized void announceEpoch(Identifier dimension, long epoch) {
        Long announced = this.announcedEpochs.get(dimension);
        if (announced != null && announced == epoch) {
            return;
        }
        SourceState.EpochTransition transition = this.service.state().epochTransition(dimension);
        String reason = transition == null ? "START" : transition.reason().name();
        this.publisher.sink().announceEpoch(
                new DimensionRef(dimension.getNamespace(), dimension.getPath()),
                epoch, reason, this.registryAndDatapackFingerprint);
        this.announcedEpochs.put(dimension, epoch);
    }

    private static ChunkSnapshot serialize(ChunkKey key, long revision, long epoch, SerializableChunkData data) {
        try {
            CompoundTag tag = data.write();
            // 压缩 payload（NbtIo.writeCompressed 对照）
            ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, compressedOut);
            byte[] payload = compressedOut.toByteArray();
            // 未压缩长度（PUT_SNAPSHOT.uncompressedLength，供接收方校验）
            ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(rawOut));
            return ChunkSnapshot.of(key, revision, epoch, payload, rawOut.size());
        } catch (IOException e) {
            throw new UncheckedIOException("区块快照序列化失败: " + key, e);
        }
    }
}

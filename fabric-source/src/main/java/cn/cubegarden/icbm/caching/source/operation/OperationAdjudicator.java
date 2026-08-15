package cn.cubegarden.icbm.caching.source.operation;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单格乐观操作裁决器（里程碑 7 阶段 5；源服主线程执行——调用方必须是 Server thread）。
 *
 * <p>语义对照（26.3 minecraft-assets，AGENTS.md「官方做法最高权重」）：
 * <ul>
 *   <li>前置条件复刻 {@code ServerPlayerGameMode.destroyBlock}（ServerPlayerGameMode.java:266）
 *       与 {@code BlockItem.place} 的检查面：GameMasterBlock 拒绝（模拟玩家 canUseGameMasterBlocks
 *       =false）、目标非空气/可替换、preconditions 状态哈希 == 权威状态哈希（源服重算，不信任
 *       slave 的“方块应该变成什么”）、baseRevision 只作快速拒绝不作唯一冲突键；</li>
 *   <li>权威执行走 {@code ServerLevel.setBlock}（Level.java:216，官方世界修改底层，
 *       SetBlockCommand/FillCommand 同一路径）；canonical delta = 执行后 {@code getBlockState}
 *       的 {@code toString()}（StateHolder.java:42，id+properties 形式）；</li>
 *   <li>真实玩家会话接线（阶段 8）时替换模拟上下文为真实 ServerPlayer，裁决核心不变。</li>
 * </ul>
 */
public final class OperationAdjudicator implements cn.cubegarden.icbm.caching.source.operation.OperationQueue.Adjudicator {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationAdjudicator.class);

    /** 模拟玩家权限级别（>=2 可放置/破坏；GameMasterBlock 需 >=2 且 canUseGameMasterBlocks）。 */
    private final int simulatedPermissionLevel;
    private final cn.cubegarden.icbm.caching.source.dirty.DirtyTracker tracker;
    private final cn.cubegarden.icbm.caching.source.revision.SourceStateService service;

    public OperationAdjudicator() {
        this(2, null, null);
    }

    public OperationAdjudicator(int simulatedPermissionLevel) {
        this(simulatedPermissionLevel, null, null);
    }

    /** 装配：带脏标记/版本服务（成功裁决后递增 revision，快照管道随后发布新状态）。 */
    public OperationAdjudicator(cn.cubegarden.icbm.caching.source.dirty.DirtyTracker tracker,
                                cn.cubegarden.icbm.caching.source.revision.SourceStateService service) {
        this(2, tracker, service);
    }

    public OperationAdjudicator(int simulatedPermissionLevel,
                                cn.cubegarden.icbm.caching.source.dirty.DirtyTracker tracker,
                                cn.cubegarden.icbm.caching.source.revision.SourceStateService service) {
        this.simulatedPermissionLevel = simulatedPermissionLevel;
        this.tracker = tracker;
        this.service = service;
    }

    /** 裁决结果：成功（canonical state + 新 revision）或失败原因。 */
    public sealed interface Result permits Committed, Rejected {
    }

    public record Committed(String canonicalState, long chunkRevision, long epoch) implements Result {
    }

    public record Rejected(String reason) implements Result {
    }

    /** 执行裁决（Server thread）。preconditions 为 slave 提交的目标状态哈希；未命中 → Rejected。 */
    @Override
    public Result adjudicate(MinecraftServer server, Messages.SubmitOperation op) {
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, resolve(op.dimension())));
        if (level == null) {
            return new Rejected("dimension-not-loaded");
        }
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(op.posX(), op.posY(), op.posZ());
        if (!level.isLoaded(pos)) {
            return new Rejected("chunk-not-loaded");
        }
        BlockState authority = level.getBlockState(pos);
        if (op.preconditions() != null && !op.preconditions().isEmpty()
                && !hash(authority.toString()).equals(op.preconditions())) {
            return new Rejected("precondition-mismatch");
        }
        if (authority.getBlock() instanceof GameMasterBlock && this.simulatedPermissionLevel < 2) {
            return new Rejected("game-master-block");
        }
        BlockState result;
        switch (op.operationType()) {
            case BREAK -> {
                if (authority.isAir()) {
                    return new Rejected("already-air");
                }
                if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)) {
                    return new Rejected("set-block-failed");
                }
                result = level.getBlockState(pos);
            }
            case PLACE -> {
                BlockState placed = parsePlaceState(op.placeState());
                if (placed == null) {
                    return new Rejected("unknown-place-state: " + op.placeState());
                }
                if (!authority.isAir() && !authority.canBeReplaced()) {
                    return new Rejected("target-not-replaceable");
                }
                if (!level.setBlock(pos, placed, Block.UPDATE_CLIENTS)) {
                    return new Rejected("set-block-failed");
                }
                result = level.getBlockState(pos);
            }
            default -> {
                return new Rejected("operation-type-disabled");
            }
        }
        // 成功裁决：脏标记 + revision 递增（快照管道随后发布新状态）；epoch 取当前维度 epoch
        return new Committed(result.toString(), this.markDirtyAndRevision(server, op),
                this.service == null ? 0 : this.service.epochFor(Level.OVERWORLD.identifier()));
    }

    /** 脏标记 + revision 递增（成功裁决后；快照管道随后发布新状态）。 */
    private long markDirtyAndRevision(MinecraftServer server, Messages.SubmitOperation op) {
        net.minecraft.resources.Identifier dimension =
                net.minecraft.resources.Identifier.parse(op.dimension().id());
        cn.cubegarden.icbm.caching.source.revision.ChunkKey key =
                cn.cubegarden.icbm.caching.source.revision.ChunkKey.of(
                        dimension, Math.floorDiv(op.posX(), 16), Math.floorDiv(op.posZ(), 16));
        if (this.tracker != null) {
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimension));
            if (level != null) {
                this.tracker.onChunkUnsaved(level, new net.minecraft.world.level.ChunkPos(key.x(), key.z()));
            }
        }
        return this.service == null ? 0 : this.service.nextRevision(key);
    }

    private static Identifier resolve(DimensionRef dimension) {
        return Identifier.parse(dimension.id());
    }

    private static BlockState parsePlaceState(String id) {
        try {
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
            return block == null || block == net.minecraft.world.level.block.Blocks.AIR
                    ? null : block.defaultBlockState();
        } catch (Exception e) {
            return null;
        }
    }

    public static String hash(String state) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

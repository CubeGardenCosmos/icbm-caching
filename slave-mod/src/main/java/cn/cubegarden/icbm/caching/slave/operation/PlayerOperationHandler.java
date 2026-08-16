package cn.cubegarden.icbm.caching.slave.operation;

import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.slave.control.SlaveControlClient;
import cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader;
import cn.cubegarden.icbm.caching.slave.world.SnapshotReloader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实玩家操作接线（阶段 8.1，草稿 real-player-operations §3）：把真实玩家互动包
 * （BREAK/PLACE）接入乐观操作管道——预检验（{@link OperationGate}，fail closed）→ overlay
 * 预测登记 + 应用到已装载 LevelChunk（UPDATE_CLIENTS，玩家视角即时可见）→
 * SUBMIT_OPERATION → 源服主线程裁决 → 终态回调（COMMITTED 清预测等快照覆盖；REJECTED
 * RESYNC 落回 committed snapshot + 重应用其余 pending 预测）。
 *
 * <p>语义对照（26.3 minecraft-assets）：
 * <ul>
 *   <li>破坏：ServerGamePacketListenerImpl.handlePlayerAction → ServerPlayerGameMode.
 *       handleBlockBreakAction（ServerPlayerGameMode.java:154）→ instabuild 创造即时破坏；
 *       v1 只接创造模式（instabuild），生存挖掘动画延后；</li>
 *   <li>放置：handleUseItemOn（ServerGamePacketListenerImpl.java:1337）→ BlockItem → 目标 =
 *       hitResult.getBlockPos().relative(direction)；复杂放置（块实体/双格植物/门/床）拒绝
 *       ——源服同判定（防权威世界畸形），slave 提前拒绝避免提交注定失败的 op；</li>
 *   <li>预条件哈希：sha256(BlockState.toString)，与源服 OperationAdjudicator.hash 同算法；
 *       slave 视图 = committed snapshot + overlay 已应用预测（相交操作被单 pending 挡住）；</li>
 *   <li>ack：ackBlockChangesUpTo（ServerGamePacketListenerImpl.java:1470）确认客户端序列；
 *       拒绝路径回显 ClientboundBlockUpdatePacket（vanilla "too far"/"may not interact"
 *       同款，客户端撤销本地预测）。</li>
 * </ul>
 */
public final class PlayerOperationHandler implements SlaveControlClient.OperationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerOperationHandler.class);

    private final SlaveControlClient client;
    private final OperationOverlay overlay;
    private final SnapshotChunkLoader loader;
    private final SnapshotReloader reloader;

    public PlayerOperationHandler(SlaveControlClient client, OperationOverlay overlay,
                                  SnapshotChunkLoader loader, SnapshotReloader reloader) {
        this.client = client;
        this.overlay = overlay;
        this.loader = loader;
        this.reloader = reloader;
    }

    // ===================== 玩家互动入口（Server thread；SpectateGateMixin 路由后调用） =====================

    /** 挖掘意图（START_DESTROY_BLOCK；创造模式即时破坏）。 */
    public void handleBreak(ServerPlayer player, ServerGamePacketListenerImpl connection,
                            BlockPos pos, int sequence) {
        ServerLevel level = player.level();
        BlockState view = level.getBlockState(pos);
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        String reason = OperationGate.breakIntent(
                this.loader.isChunkServiceable(chunkX, chunkZ),
                player.getAbilities().instabuild,
                player.isWithinBlockInteractionRange(pos, 1.0),
                view.isAir(),
                view.getBlock().defaultDestroyTime() < 0,
                view.getBlock() instanceof GameMasterBlock,
                this.overlay.hasPendingAt(pos.getX(), pos.getY(), pos.getZ()),
                this.overlay.hasPendingInChunk(hex(uuidBytes(player.getUUID())), chunkX, chunkZ));
        if (reason != null) {
            this.rejectBreak(level, connection, pos, sequence, reason);
            return;
        }
        byte[] operationId = uuidBytes(UUID.randomUUID());
        Messages.SubmitOperation op = new Messages.SubmitOperation(
                operationId, uuidBytes(player.getUUID()), "uuid:" + player.getUUID(),
                sequence, Messages.OperationType.BREAK, this.loader.dimension(),
                pos.getX(), pos.getY(), pos.getZ(),
                this.loader.committedRevision(chunkX, chunkZ),
                hash(view.toString()), "",
                System.currentTimeMillis());
        this.submitAndPredict(player, connection, op, level, pos, Blocks.AIR.defaultBlockState(),
                "minecraft:air", sequence);
    }

    /** 放置意图（handleUseItemOn 手持 BlockItem）。 */
    public void handlePlace(ServerPlayer player, ServerGamePacketListenerImpl connection,
                            ServerboundUseItemOnPacket packet) {
        ServerLevel level = player.level();
        connection.ackBlockChangesUpTo(packet.getSequence()); // vanilla 先 ack（handleUseItemOn:1340）
        BlockHitResult hit = packet.getHitResult();
        BlockPos clicked = hit.getBlockPos();
        BlockPos target = clicked.relative(hit.getDirection());
        ItemStack stack = player.getItemInHand(packet.getHand());
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            this.rejectPlace(level, connection, clicked, target, "not-block-item");
            return;
        }
        Block placed = blockItem.getBlock();
        BlockState targetView = level.getBlockState(target);
        int chunkX = target.getX() >> 4;
        int chunkZ = target.getZ() >> 4;
        String reason = OperationGate.placeIntent(
                this.loader.isChunkServiceable(chunkX, chunkZ),
                player.getAbilities().instabuild,
                player.isWithinBlockInteractionRange(clicked, 1.0),
                targetView.isAir() || targetView.canBeReplaced(),
                placed.defaultBlockState().hasBlockEntity()
                        || placed instanceof DoublePlantBlock
                        || placed instanceof DoorBlock
                        || placed instanceof BedBlock,
                this.overlay.hasPendingAt(target.getX(), target.getY(), target.getZ()),
                this.overlay.hasPendingInChunk(hex(uuidBytes(player.getUUID())), chunkX, chunkZ));
        if (reason != null) {
            this.rejectPlace(level, connection, clicked, target, reason);
            return;
        }
        byte[] operationId = uuidBytes(UUID.randomUUID());
        String placeState = BuiltInRegistries.BLOCK.getKey(placed).toString();
        Messages.SubmitOperation op = new Messages.SubmitOperation(
                operationId, uuidBytes(player.getUUID()), "uuid:" + player.getUUID(),
                packet.getSequence(), Messages.OperationType.PLACE, this.loader.dimension(),
                target.getX(), target.getY(), target.getZ(),
                this.loader.committedRevision(chunkX, chunkZ),
                hash(targetView.toString()), placeState,
                System.currentTimeMillis());
        this.submitAndPredict(player, connection, op, level, target,
                placed.defaultBlockState(), placeState, packet.getSequence());
        // vanilla 无条件回显 clicked + 邻接位置（handleUseItemOn 结尾；客户端预测校正）
        connection.send(new ClientboundBlockUpdatePacket(level, clicked));
        connection.send(new ClientboundBlockUpdatePacket(level, target));
    }

    /** 提交 + 预测登记 + 应用到已装载 LevelChunk（UPDATE_CLIENTS 广播给玩家视角）。 */
    private void submitAndPredict(ServerPlayer player, ServerGamePacketListenerImpl connection,
                                  Messages.SubmitOperation op, ServerLevel level, BlockPos pos,
                                  BlockState predicted, String predictedId, int sequence) {
        String operationIdHex = hex(op.operationId());
        String playerIdHex = hex(uuidBytes(player.getUUID()));
        this.overlay.submit(operationIdHex, playerIdHex, op, predictedId);
        if (!this.client.submitOperation(op)) {
            this.overlay.discardAll();
            this.rejectBreak(player.level(), connection, pos, sequence, "submit-failed");
            return;
        }
        level.setBlock(pos, predicted, Block.UPDATE_CLIENTS);
        connection.ackBlockChangesUpTo(sequence);
        LOGGER.info("[icbm-slave] 玩家操作已提交: player={} op={} type={} pos={},{},{}",
                player.getGameProfile().name(), operationIdHex, op.operationType(),
                pos.getX(), pos.getY(), pos.getZ());
    }

    // ===================== 拒绝路径（回显撤销，fail closed） =====================

    private void rejectBreak(ServerLevel level, ServerGamePacketListenerImpl connection,
                             BlockPos pos, int sequence, String reason) {
        connection.send(new ClientboundBlockUpdatePacket(level, pos));
        connection.ackBlockChangesUpTo(sequence);
        LOGGER.info("[icbm-slave] 破坏拒绝（客户端撤销预测）: pos={},{},{} reason={}",
                pos.getX(), pos.getY(), pos.getZ(), reason);
    }

    private void rejectPlace(ServerLevel level, ServerGamePacketListenerImpl connection,
                             BlockPos clicked, BlockPos target, String reason) {
        connection.send(new ClientboundBlockUpdatePacket(level, clicked));
        connection.send(new ClientboundBlockUpdatePacket(level, target));
        LOGGER.info("[icbm-slave] 放置拒绝（客户端撤销预测）: clicked={},{},{} target={},{},{} reason={}",
                clicked.getX(), clicked.getY(), clicked.getZ(),
                target.getX(), target.getY(), target.getZ(), reason);
    }

    // ===================== 终态回调（SlaveControlClient 通道线程；COMMITTED 就地应用，REJECTED 登记 resync） =====================

    /**
     * 阶段 9.2 F1 快路径（草稿 fast-operation-sync §3.1）：COMMITTED 到达后立即把权威
     * canonicalState 就地应用到已装载 LevelChunk（Server thread，UPDATE_CLIENTS）——视角
     * 不再等 SNAPSHOT 往返；随后到达的 SNAPSHOT_PUSH 经 refreshLoaded 差异为空（天然幂等，
     * 作为确认）。canonicalState 解析失败/区块未装载 → 跳过（快照管道兜底，fail closed
     * 不猜测）。
     */
    @Override
    public void onCommitted(Messages.OperationCommitted committed) {
        OperationOverlay.Pending pending = this.overlay.onCommitted(committed.operationId());
        LOGGER.info("[icbm-slave] 操作已提交: op={} state={} rev={}",
                hex(committed.operationId()), committed.canonicalState(), committed.chunkRevision());
        if (pending != null) {
            this.applyCanonical(pending, committed.canonicalState());
        }
    }

    @Override
    public void onRejected(Messages.OperationRejected rejected) {
        this.resyncAfterReject(rejected.operationId(), rejected.reason());
    }

    @Override
    public void onStatusRes(Messages.OperationStatusRes res) {
        String id = hex(res.operationId());
        switch (res.status()) {
            case COMMITTED -> {
                // 超时查询路径：同样就地应用权威状态（F1 同语义）
                OperationOverlay.Pending pending = this.overlay.onCommitted(res.operationId());
                if (pending != null) {
                    this.applyCanonical(pending, res.canonicalState());
                }
            }
            case REJECTED -> this.resyncAfterReject(res.operationId(), res.rejectReason());
            case UNKNOWN, PENDING -> {
                if (res.status() == Messages.OperationStatusRes.OperationStatus.UNKNOWN) {
                    // 源服丢失终态（重启/历史裁剪）：保守丢弃全部预测，fail closed
                    int dropped = this.overlay.discardAll();
                    LOGGER.error("[icbm-slave] 操作终态未知（源服历史丢失）: op={} 丢弃全部预测 {} 项", id, dropped);
                }
            }
        }
    }

    /** REJECTED：清预测 + 对该 chunk 登记 RESYNC（Server tick 就地恢复 committed + 重应用预测）。 */
    private void resyncAfterReject(byte[] operationId, String reason) {
        List<int[]> resync = this.overlay.onRejected(operationId);
        for (int[] chunk : resync) {
            this.reloader.requestResync(chunk[0], chunk[1]);
        }
        LOGGER.warn("[icbm-slave] 操作被拒（RESYNC {} 个区块）: op={} reason={}",
                resync.size(), hex(operationId), reason);
    }

    /** F1 就地应用（通道线程调用；切 Server thread 执行）。canonicalState 解析失败或区块
     *  未装载 → 跳过（SNAPSHOT 到达时 refreshLoaded 兜底，天然幂等）。 */
    private void applyCanonical(OperationOverlay.Pending pending, String canonicalState) {
        BlockState state = parseCanonical(canonicalState);
        if (state == null) {
            LOGGER.error("[icbm-slave] canonicalState 解析失败（不应用，等快照纠正，fail closed）: "
                    + "state={}", canonicalState);
            return;
        }
        MinecraftServer server = this.client.serverRef();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.Identifier.parse(pending.dimension.id())));
            if (level == null) {
                return;
            }
            int cx = Math.floorDiv(pending.posX, 16);
            int cz = Math.floorDiv(pending.posZ, 16);
            if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                return; // 未装载：进入时 scheduleChunkLoad 命中最新快照，无需就地应用
            }
            level.setBlock(new BlockPos(pending.posX, pending.posY, pending.posZ),
                    state, Block.UPDATE_CLIENTS);
            LOGGER.info("[icbm-slave] COMMITTED 就地应用 canonicalState: pos={},{},{} state={}",
                    pending.posX, pending.posY, pending.posZ, canonicalState);
        });
    }

    // ===================== 工具（与源服 OperationAdjudicator 同语义） =====================

    /** 状态 id（如 "minecraft:air"/"minecraft:diamond_block"）→ 默认 BlockState；未知 → null。 */
    public static BlockState parseState(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        return block == null ? null : block.defaultBlockState();
    }

    /**
     * canonicalState 解析（阶段 9.2 F1）：源服 canonicalState = 执行后 {@code getBlockState()
     * .toString()}。26.3 格式为 {@code Block{id}[prop=v,...]}（StateHolder.java:42：owner 即
     * Block.toString()，Block.java:576 {@code Block{<registeredName>}}；与 1.21 的
     * {@code minecraft:air} 格式不同）——先 {@link #normalizeCanonical} 归一为
     * {@code id[prop=v,...]}，再经 26.3 {@code BlockStateParser.parseForBlock}
     * （commands/arguments/blocks/BlockStateParser.java:88，1.21 的 BlockArgumentParser 已改名）
     * 解析。未知/畸形 → null（调用方 fail closed 等快照纠正）。
     */
    public static BlockState parseCanonical(String state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        String normalized = normalizeCanonical(state);
        if (normalized == null) {
            return null;
        }
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                    BuiltInRegistries.BLOCK, normalized, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** 26.3 BlockState.toString() 归一：{@code Block{id}[prop=v,...]} → {@code id[prop=v,...]}。
     *  非 {@code Block{...}} 前缀原样返回（兼容纯 id/旧格式）；畸形 → null。 */
    static String normalizeCanonical(String state) {
        if (state == null || !state.startsWith("Block{")) {
            return state;
        }
        int close = state.indexOf('}');
        if (close < 0) {
            return null;
        }
        return state.substring("Block{".length(), close) + state.substring(close + 1);
    }

    /** 预条件哈希：sha256hex(BlockState.toString)（与源服 OperationAdjudicator.hash 同算法）。 */
    public static String hash(String state) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static byte[] uuidBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return bytes;
    }
}

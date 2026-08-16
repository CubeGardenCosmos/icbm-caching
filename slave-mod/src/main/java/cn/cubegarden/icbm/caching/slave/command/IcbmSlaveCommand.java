package cn.cubegarden.icbm.caching.slave.command;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import cn.cubegarden.icbm.caching.slave.operation.OperationOverlay;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * slave 调试命令：{@code /icbmslave operate <x> <y> <z> <break|place> [blockState]}。
 *
 * <p>无头操作入口（里程碑 6 RCON 代理的延续；真实玩家网络层随阶段 8 接线）：触发
 * 乐观操作提交——登记 overlay 预测 + SUBMIT_OPERATION → master → 源服主线程裁决 →
 * 终态回发（COMMITTED 清预测 / REJECTED 落回 committed snapshot）。RCON 可执行，
 * 供 e2e 验证「玩家经 slave 的操作 → 源服权威 → 快照 → slave 展示」全链路。
 *
 * <p>v1 说明：preconditions 为空（跳过状态哈希前置校验——无头入口无真实方块状态哈希；
 * 真实玩家路径由玩家网络层计算）。操作类型白名单：BREAK/PLACE（阶段 6 扩大类型延后）。
 */
public final class IcbmSlaveCommand {

    private IcbmSlaveCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("icbmslave")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("operate")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .executes(IcbmSlaveCommand::operate))))))
                .then(Commands.literal("chat")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(IcbmSlaveCommand::chat)))
                .then(Commands.literal("passthrough")
                        .then(Commands.literal("useitem")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("dir", StringArgumentType.word())
                                                                .executes(IcbmSlaveCommand::passthroughUseItem))))))));
    }

    private static int operate(CommandContext<CommandSourceStack> context) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            context.getSource().sendFailure(Component.literal("icbm-slave 运行时未初始化"));
            return 0;
        }
        if (!runtime.client().connected()) {
            context.getSource().sendFailure(Component.literal("master 未连接（fail closed，拒绝操作）"));
            return 0;
        }
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        String typeName = StringArgumentType.getString(context, "type").toLowerCase(Locale.ROOT);
        Messages.OperationType type = switch (typeName) {
            case "break" -> Messages.OperationType.BREAK;
            case "place" -> Messages.OperationType.PLACE;
            default -> {
                context.getSource().sendFailure(Component.literal("未知操作类型: " + typeName + "（break|place）"));
                yield null;
            }
        };
        if (type == null) {
            return 0;
        }
        String placeState = type == Messages.OperationType.PLACE ? "minecraft:diamond_block" : "";
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        // 单 pending 约束（架构 §6）：相交/同 chunk 操作排队
        OperationOverlay overlay = runtime.overlay();
        String playerId = "00000000-0000-0000-0000-0000000000ff"; // RCON 模拟玩家（无头）
        if (overlay.hasPendingInChunk(playerId, chunkX, chunkZ)) {
            context.getSource().sendFailure(Component.literal("该 chunk 已有未决操作（单 pending，排队等待终态）"));
            return 0;
        }
        byte[] operationId = uuidBytes(UUID.randomUUID());
        Messages.SubmitOperation op = new Messages.SubmitOperation(
                operationId, uuidBytes(UUID.nameUUIDFromBytes("rcon-player".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "rcon", 1, type, DimensionRef.of("minecraft", "overworld"),
                x, y, z, 0, "", placeState, System.currentTimeMillis());
        overlay.submit(java.util.HexFormat.of().formatHex(operationId), playerId, op,
                type == Messages.OperationType.BREAK ? "minecraft:air" : placeState);
        if (!runtime.client().submitOperation(op)) {
            overlay.discardAll();
            context.getSource().sendFailure(Component.literal("操作提交失败（通道不可用）"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("已提交操作 " + java.util.HexFormat.of().formatHex(operationId)
                + " " + typeName + " " + x + "," + y + "," + z + "（等待源服终态）"), true);
        return 1;
    }

    /** 聊天模拟（阶段 9.3 e2e 闭环）：以模拟玩家身份发 CHAT_RELAY——真实玩家聊天由
     *  ALLOW_CHAT_MESSAGE 拦截同路径；本命令供无头验证「slave → master → 源服权威广播 →
     *  下行分发 → slave 送达」全链路（源服控制台可见 + slave 送达日志）。 */
    private static int chat(CommandContext<CommandSourceStack> context) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            context.getSource().sendFailure(Component.literal("icbm-slave 运行时未初始化"));
            return 0;
        }
        if (!runtime.client().connected()) {
            context.getSource().sendFailure(Component.literal("master 未连接（聊天上行不可用）"));
            return 0;
        }
        String text = StringArgumentType.getString(context, "text");
        java.util.UUID sender = java.util.UUID.nameUUIDFromBytes("rcon-chatter".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean sent = runtime.client().sendChatRelay(new Messages.ChatRelay(
                uuidBytes(sender), "rcon-chatter", text));
        if (!sent) {
            context.getSource().sendFailure(Component.literal("聊天上行发送失败（fail closed）"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("已上行聊天: " + text), true);
        return 1;
    }

    private static byte[] uuidBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return bytes;
    }

    /** 权威会话 v2 调试：{@code /icbmslave passthrough useitem <x> <y> <z> <dir>} —— 构造
     *  ServerboundUseItemOnPacket（主手，点击 (x,y,z) + 方向）走与真实玩家拦截相同的上行路径
     *  （MC_PASSTHROUGH → master → 源服虚拟玩家 vanilla 处理）。e2e 无头验证「使用物品
     *  结果来自源服」：源服 give 虚拟玩家方块 → 此命令放置 → 快照 → slave 视角。
     *
     *  <p>dir = up|down|north|south|east|west（放置方向）；序列=0（无头）。
     */
    private static int passthroughUseItem(CommandContext<CommandSourceStack> context) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null) {
            context.getSource().sendFailure(Component.literal("icbm-slave 运行时未初始化"));
            return 0;
        }
        if (!runtime.client().connected()) {
            context.getSource().sendFailure(Component.literal("master 未连接（fail closed，拒绝直传）"));
            return 0;
        }
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        String dirName = StringArgumentType.getString(context, "dir").toLowerCase(Locale.ROOT);
        net.minecraft.core.Direction dir = switch (dirName) {
            case "up" -> net.minecraft.core.Direction.UP;
            case "down" -> net.minecraft.core.Direction.DOWN;
            case "north" -> net.minecraft.core.Direction.NORTH;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "east" -> net.minecraft.core.Direction.EAST;
            case "west" -> net.minecraft.core.Direction.WEST;
            default -> {
                context.getSource().sendFailure(Component.literal("未知方向: " + dirName + "（up|down|north|south|east|west）"));
                yield null;
            }
        };
        if (dir == null) {
            return 0;
        }
        net.minecraft.core.BlockPos clicked = new net.minecraft.core.BlockPos(x, y, z);
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(clicked), dir, clicked, false);
        net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet =
                new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                        net.minecraft.world.InteractionHand.MAIN_HAND, hit, 0);
        UUID playerUuid = UUID.nameUUIDFromBytes("rcon-player".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean sent = runtime.passthrough().sendServerbound(playerUuid, packet);
        if (!sent) {
            context.getSource().sendFailure(Component.literal("直传上行失败（fail closed）"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.literal("已直传 use-item: 点击 " + x + "," + y + "," + z + " 方向 " + dirName),
                true);
        return 1;
    }

}

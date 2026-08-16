package cn.cubegarden.icbm.caching.slave.command;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import cn.cubegarden.icbm.caching.slave.translate.TranslationService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * slave 调试/工具命令 {@code /bmtool}：
 * <ul>
 *   <li>{@code /bmtool debug toggle}（op，LEVEL_GAMEMASTERS）——切换 action bar 状态提示
 *       （slave 性能 / slave↔master 通讯状态 / slave UUID，见
 *       {@link cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay}）；</li>
 *   <li>{@code /bmtool translate toggle}（无权限，LEVEL_ALL）——按玩家手动开关 LLM 翻译
 *       （默认开启；开关状态存内存，slave 禁持久化约束，会话级）；</li>
 *   <li>{@code /bmtool translate sign|book [x y z]}（无权限）——按命令发起玩家 locale 翻译
 *       告示牌/讲台书本，绿色 Action Bar 显示译文（坐标缺省 = 玩家所在方块）。</li>
 * </ul>
 *
 * <p>权限与反馈模式沿用 {@link IcbmSlaveCommand}；运行时未初始化 → sendFailure
 * （fail closed，不猜测状态）。
 */
public final class BmToolCommand {

    private BmToolCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bmtool")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .then(Commands.literal("debug")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("toggle")
                                .executes(BmToolCommand::toggleDebug)))
                .then(Commands.literal("translate")
                        .then(Commands.literal("toggle")
                                .executes(BmToolCommand::toggleTranslate))
                        .then(Commands.literal("sign")
                                .executes(BmToolCommand::translateSignAtSelf)
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(BmToolCommand::translateSignAtPos)))))
                        .then(Commands.literal("book")
                                .executes(BmToolCommand::translateBookAtSelf)
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer(-64, 320))
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(BmToolCommand::translateBookAtPos)))))));
    }

    // ===================== debug（op） =====================

    private static int toggleDebug(CommandContext<CommandSourceStack> context) {
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null || runtime.debugOverlay() == null) {
            context.getSource().sendFailure(Component.literal("icbm-slave 运行时未初始化"));
            return 0;
        }
        boolean on = runtime.debugOverlay().toggle();
        context.getSource().sendSuccess(
                () -> Component.literal(on ? "bmtool debug 已开启（action bar 状态提示）"
                        : "bmtool debug 已关闭"), true);
        return 1;
    }

    // ===================== translate toggle（无权限） =====================

    private static int toggleTranslate(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("仅玩家可执行"));
            return 0;
        }
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null || runtime.translationService() == null) {
            context.getSource().sendFailure(Component.literal("icbm-slave 运行时未初始化"));
            return 0;
        }
        boolean on = runtime.translationService().toggle(player.getUUID());
        context.getSource().sendSuccess(
                () -> Component.literal(on ? "LLM 翻译已开启（收到的信息将按你的语言翻译）"
                        : "LLM 翻译已关闭（不再显示译文）"), true);
        return 1;
    }

    // ===================== translate sign|book（无权限；坐标缺省 = 玩家位置） =====================

    private static int translateSignAtSelf(CommandContext<CommandSourceStack> context) {
        return translateAt(context, BlockPos.containing(context.getSource().getPosition()), true);
    }

    private static int translateSignAtPos(CommandContext<CommandSourceStack> context) {
        return translateAt(context, pos(context), true);
    }

    private static int translateBookAtSelf(CommandContext<CommandSourceStack> context) {
        return translateAt(context, BlockPos.containing(context.getSource().getPosition()), false);
    }

    private static int translateBookAtPos(CommandContext<CommandSourceStack> context) {
        return translateAt(context, pos(context), false);
    }

    private static BlockPos pos(CommandContext<CommandSourceStack> context) {
        return new BlockPos(IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    private static int translateAt(CommandContext<CommandSourceStack> context, BlockPos pos, boolean sign) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("仅玩家可执行"));
            return 0;
        }
        SlaveRuntime runtime = SlaveRuntime.current();
        if (runtime == null || runtime.translationService() == null) {
            context.getSource().sendFailure(Component.literal("icbm-slave 运行时未初始化"));
            return 0;
        }
        TranslationService service = runtime.translationService();
        if (sign) {
            service.translateSign(player, pos);
        } else {
            service.translateBook(player, pos);
        }
        return 1;
    }
}

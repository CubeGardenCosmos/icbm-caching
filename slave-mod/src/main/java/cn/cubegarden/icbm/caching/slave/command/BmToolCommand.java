package cn.cubegarden.icbm.caching.slave.command;

import cn.cubegarden.icbm.caching.slave.SlaveRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * slave 调试命令：{@code /bmtool debug toggle}（op）——切换 action bar 状态提示
 * （slave 性能 / slave↔master 通讯状态 / slave UUID，见 {@link
 * cn.cubegarden.icbm.caching.slave.debug.SlaveDebugOverlay}）。
 *
 * <p>权限与反馈模式沿用 {@link IcbmSlaveCommand}（Commands.LEVEL_GAMEMASTERS = op）；
 * 运行时未初始化 → sendFailure（fail closed，不猜测状态）。
 */
public final class BmToolCommand {

    private BmToolCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bmtool")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("debug")
                        .then(Commands.literal("toggle")
                                .executes(BmToolCommand::toggleDebug))));
    }

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
}

package cn.cubegarden.icbm.caching.source.command;

import cn.cubegarden.icbm.caching.source.SourceRuntime;
import cn.cubegarden.icbm.caching.source.revision.ActivationReason;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * 管理命令：{@code /icbm epoch new <reason>}（ADMIN 失效）与 {@code /icbm status}。
 * 需要 op 权限（2 级）。
 */
public final class IcbmCommand {

    private IcbmCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("icbm")
                .then(Commands.literal("epoch")
                        .then(Commands.literal("new")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("reason", StringArgumentType.word())
                                        .executes(IcbmCommand::newEpoch)))
                        .then(Commands.literal("status")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(IcbmCommand::status))));
    }

    private static int newEpoch(CommandContext<CommandSourceStack> context) {
        SourceRuntime runtime = SourceRuntime.current();
        if (runtime == null) {
            context.getSource().sendFailure(net.minecraft.network.chat.Component.literal("icbm-source 运行时未初始化"));
            return 0;
        }
        String reasonName = StringArgumentType.getString(context, "reason").toUpperCase(Locale.ROOT);
        ActivationReason reason;
        try {
            reason = ActivationReason.valueOf(reasonName);
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                    "未知原因: " + reasonName + "（可选: START/ROLLBACK/MODPACK_CHANGE/ADMIN）"));
            return 0;
        }
        if (!(context.getSource().getLevel() instanceof ServerLevel level)) {
            context.getSource().sendFailure(net.minecraft.network.chat.Component.literal("需要在服务器世界内执行"));
            return 0;
        }
        Identifier dimension = level.dimension().identifier();
        long epoch = runtime.service().newEpoch(dimension, reason);
        context.getSource().sendSuccess(
                () -> net.minecraft.network.chat.Component.literal(
                        "已创建新 epoch: " + dimension + " -> " + epoch + "（原因 " + reason + "）"),
                true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        SourceRuntime runtime = SourceRuntime.current();
        if (runtime == null) {
            context.getSource().sendFailure(net.minecraft.network.chat.Component.literal("icbm-source 运行时未初始化"));
            return 0;
        }
        String gate = runtime.gate().isEnabled() ? "enabled" : "disabled（" + runtime.gate().disabledReason() + "）";
        context.getSource().sendSuccess(
                () -> net.minecraft.network.chat.Component.literal("icbm-source 缓存命中: " + gate
                        + "；脏区块待处理: " + runtime.tracker().dirtySet().size()),
                false);
        return 1;
    }
}

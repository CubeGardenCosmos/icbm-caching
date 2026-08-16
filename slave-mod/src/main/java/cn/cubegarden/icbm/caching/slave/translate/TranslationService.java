package cn.cubegarden.icbm.caching.slave.translate;

import cn.cubegarden.icbm.caching.slave.config.SlaveConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * slave LLM 翻译服务（参考 llm-translator，草稿 llm-translation §3）：按玩家客户端 locale
 * 把收到的信息翻译为对应语言——聊天文本自动（广播后异步送达）、告示牌/讲台书本按命令
 * （{@code /bmtool translate sign|book}）按发起玩家 locale 翻译。
 *
 * <p>约束：
 * <ul>
 *   <li>翻译结果绿色醒目标记 {@code [已翻译]}（按接收玩家 locale 本地化的 marker，见
 *       {@link TranslationLanguages#marker()}）：聊天 = 绿色前缀 + 白色译文的系统消息；
 *       告示牌/书本 = 绿色 Action Bar 消息（{@code sendOverlayMessage}）；</li>
 *   <li>LLM 请求在 LlmClient 单线程 Executor（不阻塞 Server tick 热路径）；结果经
 *       {@code server.execute} 回 Server thread 送达，玩家已下线 → 丢弃（fail closed）；</li>
 *   <li>同目标语言的多个接收方合并为一次 LLM 调用（控成本）；每玩家冷却（5s）+ 全局
 *       pending 上限（2）+ 输入截断（llm.maxTextLength）防滥用；</li>
 *   <li>LLM 未启用（apiKey 空）/ 失败（超时/网络/非 200）→ 聊天原文已按原版路径广播、
 *       命令给出明确提示，不猜测不降级（fail closed）；</li>
 *   <li>玩家 toggle 状态按 UUID 存内存（slave 禁持久化约束，会话级；重连需重开）。</li>
 * </ul>
 */
public final class TranslationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationService.class);
    private static final long COOLDOWN_MILLIS = 5_000;
    private static final int MAX_PENDING = 2;

    private final SlaveConfig config;
    private final LlmClient client;
    private final Set<String> supportedCodes;
    private final Map<UUID, Boolean> enabledByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTranslationAt = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();

    public TranslationService(SlaveConfig config, LlmClient client) {
        this.config = config;
        this.client = client;
        this.supportedCodes = Set.copyOf(config.llmLanguages());
    }

    // ===================== 玩家开关（/bmtool translate toggle，无权限） =====================

    /** 切换翻译开关；返回切换后状态（true = 开启）。默认开启。 */
    public boolean toggle(UUID player) {
        boolean now = !isEnabled(player);
        this.enabledByPlayer.put(player, now);
        LOGGER.info("[icbm-slave] 玩家 {} LLM 翻译 {}", player, now ? "开启" : "关闭");
        return now;
    }

    public boolean isEnabled(UUID player) {
        return this.enabledByPlayer.getOrDefault(player, true);
    }

    // ===================== 聊天自动翻译（下行权威广播送达钩子） =====================

    /**
     * 聊天广播送达钩子（阶段 9.3：翻译迁移到接收路径——slave 本地聊天不再广播，改走
     * CHAT_RELAY 上行 → master → 源服权威广播 → CHAT_BROADCAST 下行 → 本钩子；不再挂
     * fabric CHAT_MESSAGE 事件，避免本地拦截后的事件时序错乱）。Server thread 调用：对
     * 每个非发送者玩家按接收方 locale 判定是否需要翻译，同目标语言合并为一次 LLM 调用；
     * 译文完成后回 Server thread 以绿色 {@code [已翻译]} 前缀系统消息送达。原文广播走
     * 下行送达路径不变（本钩子不取消）。
     */
    public void onAuthoritativeBroadcast(PlayerChatMessage message, ServerPlayer sender) {
        if (!this.config.translationEnabled()) {
            return;
        }
        String text = message.signedContent();
        String senderLocale = sender.clientInformation().language();
        MinecraftServer server = sender.level().getServer();
        if (server == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<TranslationLanguages, List<ServerPlayer>> byTarget = new LinkedHashMap<>();
        for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
            if (receiver == sender) {
                continue; // 发送者看到自己的原文，不需要译文
            }
            Optional<TranslationLanguages> target = LanguageJudge.targetLanguage(
                    senderLocale, receiver.clientInformation().language(), text, this.supportedCodes);
            if (target.isEmpty()) {
                continue;
            }
            if (withinCooldown(receiver.getUUID(), now)) {
                continue; // 冷却内不重复（防滥用/控成本）
            }
            if (this.pending.get() >= MAX_PENDING) {
                LOGGER.debug("[icbm-slave] 聊天翻译跳过：pending 达上限 {}", MAX_PENDING);
                continue;
            }
            this.lastTranslationAt.put(receiver.getUUID(), now);
            byTarget.computeIfAbsent(target.get(), k -> new ArrayList<>()).add(receiver);
        }
        if (byTarget.isEmpty()) {
            return;
        }
        String sourceName = TranslationLanguages.fromClientLocale(senderLocale)
                .map(TranslationLanguages::promptName).orElse("未知语言");
        String requestText = truncate(text, this.config.llmMaxTextLength());
        for (Map.Entry<TranslationLanguages, List<ServerPlayer>> entry : byTarget.entrySet()) {
            TranslationLanguages target = entry.getKey();
            List<ServerPlayer> receivers = entry.getValue();
            this.pending.incrementAndGet();
            this.client.translateAsync(requestText, sourceName, target.promptName())
                    .whenComplete((translated, error) -> {
                        this.pending.decrementAndGet();
                        if (error != null) {
                            LOGGER.warn("[icbm-slave] 聊天翻译失败（fail closed，原文已广播）: {}",
                                    error.getMessage());
                            return;
                        }
                        this.deliverChat(server, receivers, target, translated);
                    });
        }
    }

    // ===================== 告示牌/书本按需翻译（/bmtool translate sign|book） =====================

    /** 告示牌翻译：读取已装载区块的 SignBlockEntity，前后各 4 行合并为一次 LLM 调用。 */
    public void translateSign(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity sign)) {
            this.overlay(player, "该位置不是告示牌（或区块未装载）");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (SignTextSlot slot : List.of(SignTextSlot.FRONT, SignTextSlot.BACK)) {
            for (Component line : sign.getText(slot).getMessages(false)) {
                String text = line.getString().trim();
                if (!text.isEmpty()) {
                    lines.add(text);
                }
            }
        }
        if (lines.isEmpty()) {
            this.overlay(player, "告示牌无文字");
            return;
        }
        this.submitBlockTranslation(player, String.join("\n", lines), "告示牌");
    }

    /** 讲台书本翻译：读取 LecternBlockEntity 的成书内容，全部页合并为一次 LLM 调用。 */
    public void translateBook(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof LecternBlockEntity lectern)) {
            this.overlay(player, "该位置不是讲台（或区块未装载）");
            return;
        }
        ItemStack book = lectern.getBook();
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            this.overlay(player, "讲台上没有成书");
            return;
        }
        List<String> pages = new ArrayList<>();
        for (Filterable<Component> page : content.pages()) {
            String text = page.raw().getString().trim();
            if (!text.isEmpty()) {
                pages.add(text);
            }
        }
        if (pages.isEmpty()) {
            this.overlay(player, "书本无内容");
            return;
        }
        this.submitBlockTranslation(player, String.join("\n", pages), "书本");
    }

    /** 告示牌/书本通用：冷却/pending 检查 → 异步翻译 → 绿色 Action Bar 送达。 */
    private void submitBlockTranslation(ServerPlayer player, String content, String kind) {
        if (!this.config.translationEnabled()) {
            this.overlay(player, "LLM 翻译未启用（llm.apiKey 未配置）");
            return;
        }
        Optional<TranslationLanguages> target =
                TranslationLanguages.fromClientLocale(player.clientInformation().language());
        if (target.isEmpty() || !this.supportedCodes.contains(target.get().code())) {
            this.overlay(player, "你的客户端语言不在翻译支持列表（llm.languages），无法翻译");
            return;
        }
        long now = System.currentTimeMillis();
        if (withinCooldown(player.getUUID(), now)) {
            this.overlay(player, "翻译请求过于频繁（冷却 5 秒）");
            return;
        }
        if (this.pending.get() >= MAX_PENDING) {
            this.overlay(player, "翻译队列已满，请稍后再试");
            return;
        }
        this.markTranslation(player.getUUID(), now);
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        UUID playerUuid = player.getUUID();
        String requestText = truncate(content, this.config.llmMaxTextLength());
        this.pending.incrementAndGet();
        this.client.translateAsync(requestText, "未知语言", target.get().promptName())
                .whenComplete((translated, error) -> {
                    this.pending.decrementAndGet();
                    if (error != null) {
                        LOGGER.warn("[icbm-slave] {}翻译失败（fail closed）: {}", kind, error.getMessage());
                        return;
                    }
                    this.deliverOverlay(server, playerUuid, target.get(), translated);
                });
    }

    // ===================== 送达（回 Server thread；玩家下线 → 丢弃） =====================

    /** 聊天译文：绿色 [已翻译] 前缀 + 白色译文（系统消息）。 */
    private void deliverChat(MinecraftServer server, List<ServerPlayer> receivers,
                             TranslationLanguages target, String translated) {
        server.execute(() -> {
            for (ServerPlayer receiver : receivers) {
                if (server.getPlayerList().getPlayer(receiver.getUUID()) == null) {
                    continue; // 已下线 → 丢弃
                }
                receiver.sendSystemMessage(translatedMessage(target, translated));
            }
        });
    }

    /** 告示牌/书本译文：绿色 Action Bar（行以 / 连接；Action Bar 单条渲染，超宽客户端截断）。 */
    private void deliverOverlay(MinecraftServer server, UUID playerUuid,
                                TranslationLanguages target, String translated) {
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null) {
                return; // 已下线 → 丢弃
            }
            String joined = Arrays.stream(translated.split("\n"))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" / "));
            player.sendOverlayMessage(translatedMessage(target, joined));
        });
    }

    /** 绿色 [已翻译]（按接收方 locale 本地化标记）前缀 + 白色译文。 */
    private static Component translatedMessage(TranslationLanguages target, String translated) {
        return Component.literal("[" + target.marker() + "] ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(translated));
    }

    /** 同步状态提示（命令路径，Server thread 直接发送）。 */
    private void overlay(ServerPlayer player, String text) {
        player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    // ===================== 工具 =====================

    /** 冷却判定（包内可见，测试友好）。 */
    boolean withinCooldown(UUID player, long nowMillis) {
        Long last = this.lastTranslationAt.get(player);
        return last != null && nowMillis - last < COOLDOWN_MILLIS;
    }

    /** 记录翻译时间戳（包内可见，测试友好）。 */
    void markTranslation(UUID player, long nowMillis) {
        this.lastTranslationAt.put(player, nowMillis);
    }

    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "…";
    }

    public void close() {
        this.client.close();
    }
}

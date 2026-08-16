package cn.cubegarden.icbm.caching.slave.translate;

import java.util.Optional;
import java.util.Set;

/**
 * 基本语言判断逻辑（纯函数，测试友好）：识别「场景是否需要翻译」。
 *
 * <p>场景判定（{@link #targetLanguage}，全部通过才需要翻译）：
 * <ol>
 *   <li>接收方 toggle 关闭 → 否（玩家 {@code /bmtool translate toggle} 手动关闭）；</li>
 *   <li>接收方客户端 locale 不在支持表 / 不在配置 {@code llm.languages} → 否
 *       （fail closed，不猜测目标语言）；</li>
 *   <li>文本空白 → 否；</li>
 *   <li>发送方与接收方同语言（归一化后短码相同）→ 否（原文已可读）；</li>
 *   <li>同为中文变体（zh_cn/zh_hk/zh_tw 互发，CJK 文本互相可读）→ 否（基础判断不细分
 *       简繁/白话，有意取舍见 draft/llm-translation §3.6）；</li>
 *   <li>内容已处于目标语言（脚本启发式，{@link #alreadyInTargetLanguage}）→ 否；</li>
 *   <li>其余 → 需要翻译。</li>
 * </ol>
 *
 * <p>脚本启发式（只跳过「确信已处于目标语言」的情况）：
 * <ul>
 *   <li>ja 目标：文本含假名 → 已是日文；</li>
 *   <li>zh 目标：文本含 CJK 且不含假名 → 已是中文（日文文本含假名 → 仍需翻译）；</li>
 *   <li>en/de/fr 目标：拉丁字母无法廉价区分英语/德语/法语 → 不按内容跳过
 *       （仅依赖 locale 比较，避免把德语误判为英语）。</li>
 * </ul>
 */
public final class LanguageJudge {

    private LanguageJudge() {
    }

    /**
     * 对接收方计算目标翻译语言；无需翻译 → empty。
     *
     * @param senderLocale   发送方客户端 locale（null → 无发送方，不翻译）
     * @param receiverLocale 接收方客户端 locale
     * @param text           待判定的消息/文本
     * @param supportedCodes 配置启用的语言短码（{@code llm.languages}）
     */
    public static Optional<TranslationLanguages> targetLanguage(
            String senderLocale, String receiverLocale, String text, Set<String> supportedCodes) {
        if (senderLocale == null || senderLocale.isBlank()) {
            return Optional.empty();
        }
        Optional<TranslationLanguages> target = TranslationLanguages.fromClientLocale(receiverLocale);
        if (target.isEmpty() || !supportedCodes.contains(target.get().code())) {
            return Optional.empty();
        }
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        Optional<TranslationLanguages> source = TranslationLanguages.fromClientLocale(senderLocale);
        if (source.isEmpty()) {
            // 发送方语言未知：只要接收方语言受支持即翻译（源语言由 LLM 自行识别）
            return target;
        }
        if (source.get().code().equals(target.get().code())) {
            return Optional.empty(); // 同语言，无需翻译
        }
        if (TranslationLanguages.isChineseVariant(source.get().code())
                && TranslationLanguages.isChineseVariant(target.get().code())) {
            return Optional.empty(); // 中文变体互发：基础判断视为可读，不细分简繁/白话
        }
        if (alreadyInTargetLanguage(trimmed, target.get())) {
            return Optional.empty();
        }
        return target;
    }

    /** 内容是否已处于目标语言（脚本启发式；不确定一律 false → 交给 LLM 翻译）。 */
    static boolean alreadyInTargetLanguage(String text, TranslationLanguages target) {
        return switch (target) {
            case JA -> containsKana(text);
            case ZH_CN, ZH_HK, ZH_TW -> containsCjk(text) && !containsKana(text);
            case EN, DE, FR -> false; // 拉丁字母无法廉价区分 → 不跳过
        };
    }

    /** 含平假名/片假名（U+3040–U+30FF）。 */
    static boolean containsKana(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u3040' && c <= '\u30FF') {
                return true;
            }
        }
        return false;
    }

    /** 含 CJK 表意文字（U+4E00–U+9FFF 基本区 + U+3400–U+4DBF 扩展A）。 */
    static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF')) {
                return true;
            }
        }
        return false;
    }
}

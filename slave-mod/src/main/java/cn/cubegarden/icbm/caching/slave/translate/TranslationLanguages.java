package cn.cubegarden.icbm.caching.slave.translate;

import java.util.Locale;
import java.util.Optional;

/**
 * slave 支持的翻译目标语言表（需求固定 7 种）：zh_hk（繁体中文·香港粤语白话）/
 * zh_tw（繁体中文·台湾）/ en（英文）/ ja（日文）/ de（德文）/ fr（法文）/ zh_cn（简体中文）。
 *
 * <p>每个语言项三要素：
 * <ul>
 *   <li>{@link #code()}——配置短码（{@code llm.languages} 逗号分隔，默认全 7 种）；</li>
 *   <li>{@link #promptName()}——提示词 {@code {targetLanguage}} 占位符的填充值；</li>
 *   <li>{@link #marker()}——按该语言本地化的 {@code [已翻译]} 醒目标记（译文行前缀/
 *       Action Bar 前缀；需求：不同客户端 locale 显示对应语言的「已翻译」）。</li>
 * </ul>
 *
 * <p>客户端 locale 归一化（{@link #fromClientLocale}）：MC 客户端发送完整 locale
 * （如 {@code en_us}/{@code ja_jp}/{@code de_de}/{@code fr_fr}），归一化到短码；
 * 未知 locale → {@code empty}（fail closed，不猜测目标语言）。
 */
public enum TranslationLanguages {

    ZH_HK("zh_hk", "繁体中文（香港，粤语白话）", "已翻譯"),
    ZH_TW("zh_tw", "繁体中文（台湾）", "已翻譯"),
    EN("en", "English", "translated"),
    JA("ja", "日语", "翻訳済み"),
    DE("de", "德语", "übersetzt"),
    FR("fr", "法语", "traduit"),
    ZH_CN("zh_cn", "简体中文", "已翻译");

    private final String code;
    private final String promptName;
    private final String marker;

    TranslationLanguages(String code, String promptName, String marker) {
        this.code = code;
        this.promptName = promptName;
        this.marker = marker;
    }

    /** 配置短码（与 {@code llm.languages} 一致）。 */
    public String code() {
        return this.code;
    }

    /** 提示词占位符 {@code {targetLanguage}} 填充值（LLM 可理解的语言名）。 */
    public String promptName() {
        return this.promptName;
    }

    /** 本地化 {@code [已翻译]} 标记（译文前缀，绿色醒目）。 */
    public String marker() {
        return this.marker;
    }

    /** 按配置短码查语言项；未知 → empty（fail closed）。 */
    public static Optional<TranslationLanguages> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (TranslationLanguages lang : values()) {
            if (lang.code.equals(normalized)) {
                return Optional.of(lang);
            }
        }
        return Optional.empty();
    }

    /** MC 客户端 locale（{@code ServerPlayer.clientInformation().language()}）→ 语言项；
     *  未知/空白 → empty（不翻译，fail closed）。 */
    public static Optional<TranslationLanguages> fromClientLocale(String clientLocale) {
        if (clientLocale == null || clientLocale.isBlank()) {
            return Optional.empty();
        }
        return fromCode(normalizeClientLocale(clientLocale));
    }

    /** 客户端 locale 归一化：英文/中文/日文/德文/法文各变体 → 短码；未知原样返回。 */
    static String normalizeClientLocale(String clientLocale) {
        String lower = clientLocale.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "en", "en_us", "en_gb", "en_ca", "en_au", "en_nz", "en_pt", "en_ud" -> "en";
            case "zh", "zh_cn" -> "zh_cn";
            case "zh_hk" -> "zh_hk";
            case "zh_tw" -> "zh_tw";
            case "ja", "ja_jp" -> "ja";
            case "de", "de_de" -> "de";
            case "fr", "fr_fr" -> "fr";
            default -> lower;
        };
    }

    /** 是否中文变体（zh_cn/zh_hk/zh_tw——基础语言判断不细分简繁/白话，互发视为可读）。 */
    public static boolean isChineseVariant(String code) {
        return ZH_CN.code.equals(code) || ZH_HK.code.equals(code) || ZH_TW.code.equals(code);
    }
}

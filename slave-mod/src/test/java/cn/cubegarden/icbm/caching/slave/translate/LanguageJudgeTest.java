package cn.cubegarden.icbm.caching.slave.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 基本语言判断逻辑测试（草稿 llm-translation §4）：locale 归一化、场景判定各分支——
 * 同语言跳过、中文变体互发跳过、脚本启发式（假名/CJK）、en/de/fr 拉丁不按内容跳过、
 * 空文本/不支持 locale/未知发送方语言。
 */
class LanguageJudgeTest {

    private static final Set<String> ALL = Set.of("zh_hk", "zh_tw", "en", "ja", "de", "fr", "zh_cn");

    private Optional<TranslationLanguages> judge(String sender, String receiver, String text) {
        return LanguageJudge.targetLanguage(sender, receiver, text, ALL);
    }

    // ===================== locale 归一化 =====================

    @Test
    void fromClientLocaleNormalizesVariants() {
        assertEquals(Optional.of(TranslationLanguages.EN),
                TranslationLanguages.fromClientLocale("en_us"));
        assertEquals(Optional.of(TranslationLanguages.EN),
                TranslationLanguages.fromClientLocale("en_gb"));
        assertEquals(Optional.of(TranslationLanguages.ZH_CN),
                TranslationLanguages.fromClientLocale("zh_cn"));
        assertEquals(Optional.of(TranslationLanguages.ZH_HK),
                TranslationLanguages.fromClientLocale("zh_hk"));
        assertEquals(Optional.of(TranslationLanguages.ZH_TW),
                TranslationLanguages.fromClientLocale("zh_tw"));
        assertEquals(Optional.of(TranslationLanguages.JA),
                TranslationLanguages.fromClientLocale("ja_jp"));
        assertEquals(Optional.of(TranslationLanguages.DE),
                TranslationLanguages.fromClientLocale("de_de"));
        assertEquals(Optional.of(TranslationLanguages.FR),
                TranslationLanguages.fromClientLocale("fr_fr"));
    }

    @Test
    void fromClientLocaleUnknownIsEmpty() {
        assertTrue(TranslationLanguages.fromClientLocale("ko_kr").isEmpty());
        assertTrue(TranslationLanguages.fromClientLocale("").isEmpty());
        assertTrue(TranslationLanguages.fromClientLocale(null).isEmpty());
    }

    // ===================== 场景判定 =====================

    @Test
    void sameLocaleNoTranslation() {
        assertFalse(judge("en_us", "en_gb", "hello world").isPresent());
        assertFalse(judge("zh_cn", "zh_cn", "你好").isPresent());
    }

    @Test
    void chineseVariantMutualNoTranslation() {
        // 简繁/白话互发：基础判断视为可读，不细分（有意取舍）
        assertFalse(judge("zh_cn", "zh_hk", "你好世界").isPresent());
        assertFalse(judge("zh_tw", "zh_cn", "你好世界").isPresent());
    }

    @Test
    void differentLocaleTranslates() {
        assertEquals(Optional.of(TranslationLanguages.ZH_CN), judge("en_us", "zh_cn", "hello world"));
        assertEquals(Optional.of(TranslationLanguages.EN), judge("zh_cn", "en_us", "你好世界"));
        assertEquals(Optional.of(TranslationLanguages.JA), judge("en_us", "ja_jp", "hello world"));
        assertEquals(Optional.of(TranslationLanguages.DE), judge("en_us", "de_de", "hello world"));
        assertEquals(Optional.of(TranslationLanguages.FR), judge("en_us", "fr_fr", "hello world"));
    }

    @Test
    void unsupportedReceiverLocaleNoTranslation() {
        assertTrue(judge("en_us", "ko_kr", "hello").isEmpty());
    }

    @Test
    void receiverLocaleNotInConfiguredLanguagesNoTranslation() {
        // 配置只启用 en：zh_cn 接收方不受支持 → 不翻译；en 接收方 → 翻译
        Set<String> onlyEn = Set.of("en");
        assertEquals(Optional.of(TranslationLanguages.EN),
                LanguageJudge.targetLanguage("zh_cn", "en_us", "你好", onlyEn));
        assertTrue(LanguageJudge.targetLanguage("en_us", "zh_cn", "hello", onlyEn).isEmpty());
        assertTrue(LanguageJudge.targetLanguage("zh_cn", "zh_cn", "你好", onlyEn).isEmpty());
    }

    @Test
    void blankTextNoTranslation() {
        assertFalse(judge("en_us", "zh_cn", "   ").isPresent());
        assertFalse(judge("en_us", "zh_cn", "").isPresent());
        assertFalse(judge("en_us", "zh_cn", null).isPresent());
    }

    @Test
    void unknownSenderLanguageStillTranslates() {
        // 发送方语言未知（如 ko_kr）：只要接收方语言受支持即翻译，源语言交 LLM 识别
        assertEquals(Optional.of(TranslationLanguages.ZH_CN), judge("ko_kr", "zh_cn", "any text"));
    }

    @Test
    void nullSenderNoTranslation() {
        assertTrue(judge(null, "zh_cn", "hello").isEmpty());
    }

    // ===================== 脚本启发式 =====================

    @Test
    void japaneseTargetSkipsKanaText() {
        assertFalse(judge("zh_cn", "ja_jp", "こんにちは世界").isPresent());
    }

    @Test
    void japaneseTargetTranslatesCjkWithoutKana() {
        // 中文发送方到日文接收方：纯汉字不是日文 → 翻译
        assertEquals(Optional.of(TranslationLanguages.JA), judge("zh_cn", "ja_jp", "你好世界"));
    }

    @Test
    void chineseTargetSkipsCjkWithoutKana() {
        assertFalse(judge("en_us", "zh_cn", "这是一段中文").isPresent());
        assertFalse(judge("en_us", "zh_hk", "這是一段中文").isPresent());
    }

    @Test
    void chineseTargetTranslatesJapaneseText() {
        // 日文（含假名）到中文接收方：仍需翻译
        assertEquals(Optional.of(TranslationLanguages.ZH_CN), judge("ja_jp", "zh_cn", "こんにちは"));
    }

    @Test
    void latinTargetsNeverSkipByContent() {
        // en/de/fr 拉丁字母无法廉价区分 → 只依赖 locale 比较（德语文本到英文接收方仍翻译）
        assertEquals(Optional.of(TranslationLanguages.EN), judge("de_de", "en_us", "Hallo Welt"));
        assertEquals(Optional.of(TranslationLanguages.DE), judge("en_us", "de_de", "Hello world"));
        assertEquals(Optional.of(TranslationLanguages.FR), judge("en_us", "fr_fr", "Hello world"));
    }

    @Test
    void alreadyInTargetLanguageHelpers() {
        assertTrue(LanguageJudge.containsKana("こんにちは"));
        assertFalse(LanguageJudge.containsKana("你好"));
        assertTrue(LanguageJudge.containsCjk("你好世界"));
        assertFalse(LanguageJudge.containsCjk("hello"));
        assertTrue(LanguageJudge.alreadyInTargetLanguage("こんにちは", TranslationLanguages.JA));
        assertTrue(LanguageJudge.alreadyInTargetLanguage("你好", TranslationLanguages.ZH_CN));
        assertFalse(LanguageJudge.alreadyInTargetLanguage("こんにちは", TranslationLanguages.ZH_CN));
        assertFalse(LanguageJudge.alreadyInTargetLanguage("hello", TranslationLanguages.EN));
        assertFalse(LanguageJudge.alreadyInTargetLanguage("Hallo", TranslationLanguages.DE));
    }

    @Test
    void markersLocalizedPerLanguage() {
        assertEquals("已翻译", TranslationLanguages.ZH_CN.marker());
        assertEquals("已翻譯", TranslationLanguages.ZH_HK.marker());
        assertEquals("已翻譯", TranslationLanguages.ZH_TW.marker());
        assertEquals("translated", TranslationLanguages.EN.marker());
        assertEquals("翻訳済み", TranslationLanguages.JA.marker());
        assertEquals("übersetzt", TranslationLanguages.DE.marker());
        assertEquals("traduit", TranslationLanguages.FR.marker());
    }
}

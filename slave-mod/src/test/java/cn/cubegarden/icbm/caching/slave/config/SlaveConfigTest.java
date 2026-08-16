package cn.cubegarden.icbm.caching.slave.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SlaveConfig 配置解析：品牌（icbm.brandLocation）默认空 → 无地段后缀；配置后
 * → ICBM Caching Server (地段名)；必需键缺失 fail closed。
 */
class SlaveConfigTest {

    @TempDir
    Path tempDir;

    private SlaveConfig load(String propsContent) throws Exception {
        Path worldRoot = this.tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        Files.writeString(worldRoot.resolve("icbm-control.properties"), propsContent);
        return SlaveConfig.load(worldRoot);
    }

    private String base() {
        return "control.masterHost=127.0.0.1\n"
                + "control.masterPort=11420\n"
                + "control.psk=test-psk\n"
                + "control.uuid=1112131415161718191a1b1c1d1e1f20\n"
                + "control.serverName=lobby-1\n";
    }

    @Test
    void brandDefaultsToNoLocation() throws Exception {
        SlaveConfig config = load(base());
        assertEquals("ICBM Caching Server", config.brand());
    }

    @Test
    void brandAppendsConfiguredLocation() throws Exception {
        SlaveConfig config = load(base() + "icbm.brandLocation=HongKong:Kowloon\n");
        assertEquals("ICBM Caching Server (HongKong:Kowloon)", config.brand());
    }

    @Test
    void brandTrimsWhitespaceAndAllowsMainlandExample() throws Exception {
        SlaveConfig config = load(base() + "icbm.brandLocation=  ChinaMainland:Beijing  \n");
        assertEquals("ICBM Caching Server (ChinaMainland:Beijing)", config.brand());
    }

    @Test
    void missingRequiredKeysFailClosed() throws Exception {
        // 无 psk → 抛 IllegalStateException（控制通道不启动）
        assertThrows(IllegalStateException.class, () -> load(
                "control.masterHost=127.0.0.1\ncontrol.masterPort=11420\n"
                        + "control.uuid=1112131415161718191a1b1c1d1e1f20\ncontrol.serverName=lobby-1\n"));
    }

    @Test
    void llmDisabledByDefaultWithoutApiKey() throws Exception {
        SlaveConfig config = load(base());
        assertFalse(config.translationEnabled(), "apiKey 空 → 翻译功能关闭（fail closed）");
        assertEquals("https://api.deepseek.com", config.llmBaseUrl());
        assertEquals("deepseek-chat", config.llmModel());
        assertEquals(7, config.llmLanguages().size());
        assertEquals(512, config.llmMaxTextLength());
        assertTrue(config.llmSystemPrompt().contains("{sourceLanguage}"));
        assertTrue(config.llmSystemPrompt().contains("{targetLanguage}"));
    }

    @Test
    void llmEnabledWithApiKeyAndCustomSettings() throws Exception {
        SlaveConfig config = load(base()
                + "llm.apiKey=sk-test\n"
                + "llm.baseUrl=https://example.com/v1\n"
                + "llm.model=custom-model\n"
                + "llm.timeoutMillis=30000\n"
                + "llm.maxTextLength=256\n"
                + "llm.languages=zh_hk,en\n"
                + "llm.proxyHost=127.0.0.1\n"
                + "llm.proxyPort=7890\n");
        assertTrue(config.translationEnabled());
        assertEquals("https://example.com/v1", config.llmBaseUrl());
        assertEquals("custom-model", config.llmModel());
        assertEquals(30_000, config.llmTimeoutMillis());
        assertEquals(256, config.llmMaxTextLength());
        assertEquals(java.util.List.of("zh_hk", "en"), config.llmLanguages());
        assertEquals("127.0.0.1", config.llmProxyHost());
        assertEquals(7890, config.llmProxyPort());
    }

    @Test
    void llmCustomPromptKeepsPlaceholders() throws Exception {
        String prompt = "翻译为 {targetLanguage}，源语言 {sourceLanguage}";
        SlaveConfig config = load(base() + "llm.apiKey=sk-test\n"
                + "llm.systemPrompt=" + prompt + "\n");
        assertEquals(prompt, config.llmSystemPrompt());
    }

    @Test
    void llmUnknownLanguageCodeFailsClosed() throws Exception {
        // llm.languages 未知短码 → 抛异常（不静默降级）
        assertThrows(IllegalStateException.class, () -> load(
                base() + "llm.languages=zh_hk,xx_yy\n"));
    }

    @Test
    void llmInvalidTimeoutFailsClosed() throws Exception {
        assertThrows(IllegalStateException.class, () -> load(
                base() + "llm.apiKey=sk-test\nllm.timeoutMillis=0\n"));
    }
}

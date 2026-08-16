package cn.cubegarden.icbm.caching.slave.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * LlmClient 纯函数测试（草稿 llm-translation §4）：端点拼接、系统提示词占位符填充、
 * OpenAI 兼容请求体结构、响应解析（choices[0].message.content）与错误响应 fail closed。
 */
class LlmClientTest {

    // ===================== endpoint =====================

    @Test
    void endpointAppendsChatCompletions() {
        assertEquals("https://api.deepseek.com/chat/completions",
                LlmClient.endpoint("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/chat/completions",
                LlmClient.endpoint("https://api.deepseek.com/"));
    }

    @Test
    void endpointKeepsFullUrl() {
        assertEquals("https://example.com/v1/chat/completions",
                LlmClient.endpoint("https://example.com/v1/chat/completions"));
    }

    // ===================== system prompt 占位符 =====================

    @Test
    void systemPromptFillsPlaceholders() {
        String prompt = LlmClient.systemPrompt(
                "将以下 {sourceLanguage} 文本翻译成 {targetLanguage}",
                "简体中文", "日语");
        assertEquals("将以下 简体中文 文本翻译成 日语", prompt);
    }

    // ===================== 请求体 =====================

    @Test
    void requestBodyStructure() {
        JsonObject body = LlmClient.requestBody("deepseek-chat", "sys", "你好");
        assertEquals("deepseek-chat", body.get("model").getAsString());
        assertEquals(2, body.getAsJsonArray("messages").size());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("sys", body.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("content").getAsString());
        assertEquals("user", body.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("你好", body.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString());
        assertEquals(false, body.get("stream").getAsBoolean());
    }

    // ===================== 响应解析 =====================

    @Test
    void extractContentParsesChoices() throws Exception {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"こんにちは"}}]}""";
        assertEquals("こんにちは", LlmClient.extractContent(body));
    }

    @Test
    void extractContentTrimsWhitespace() throws Exception {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"  你好  "}}]}""";
        assertEquals("你好", LlmClient.extractContent(body));
    }

    @Test
    void extractContentMissingChoicesFailsClosed() {
        assertThrows(LlmClient.LlmException.class,
                () -> LlmClient.extractContent("{}"));
        assertThrows(LlmClient.LlmException.class,
                () -> LlmClient.extractContent("{\"choices\":[]}"));
    }

    @Test
    void extractContentMissingMessageContentFailsClosed() {
        assertThrows(LlmClient.LlmException.class,
                () -> LlmClient.extractContent("{\"choices\":[{\"message\":{}}]}"));
        assertThrows(LlmClient.LlmException.class,
                () -> LlmClient.extractContent("{\"choices\":[{\"message\":{\"content\":null}}]}"));
    }

    @Test
    void extractContentEmptyContentFailsClosed() {
        assertThrows(LlmClient.LlmException.class,
                () -> LlmClient.extractContent("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"));
    }

    @Test
    void extractContentInvalidJsonFailsClosed() {
        assertThrows(LlmClient.LlmException.class, () -> LlmClient.extractContent("not json"));
    }

    @Test
    void extractContentOpenAiErrorFailsClosed() {
        String body = "{\"error\":{\"message\":\"invalid api key\",\"type\":\"authentication_error\"}}";
        LlmClient.LlmException e = assertThrows(LlmClient.LlmException.class,
                () -> LlmClient.extractContent(body));
        assertTrue(e.getMessage().contains("authentication_error"));
    }
}

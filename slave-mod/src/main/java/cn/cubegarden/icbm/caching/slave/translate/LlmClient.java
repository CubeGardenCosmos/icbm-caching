package cn.cubegarden.icbm.caching.slave.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 翻译客户端（OpenAI 兼容 Chat Completions，参考 llm-translator llm.py 的做法）：
 * {@code POST {baseUrl}/chat/completions}，system prompt 支持
 * {@code {sourceLanguage}}/{@code {targetLanguage}} 占位符；取
 * {@code choices[0].message.content}。
 *
 * <p>约束：
 * <ul>
 *   <li>HTTP 用 JDK 内置 {@link HttpClient}（异步，不阻塞 Server tick 热路径）；网络 I/O
 *       全部在单线程 daemon Executor 上执行，结果经 {@link CompletableFuture} 回调；</li>
 *   <li>JSON 用 Minecraft 运行时提供的 Gson（libraries/gson-2.14.0.jar；com.google.gson
 *       随服务器 classpath 可用，不再引入新依赖）；</li>
 *   <li>内网/代理环境：支持配置 {@code llm.proxyHost}/{@code llm.proxyPort}
 *       （不自动读取 HTTPS_PROXY，与 AGENTS.md 联网约定一致由配置显式给出）；</li>
 *   <li>失败（超时/网络/非 200/响应缺内容）→ {@link LlmException} 抛给调用方，
 *       fail closed（调用方只丢弃译文，原文已按原版路径广播）。</li>
 * </ul>
 */
public final class LlmClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmClient.class);

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String systemPromptTemplate;
    private final long timeoutMillis;
    private final HttpClient http;
    private final ExecutorService executor;

    public LlmClient(String baseUrl, String model, String apiKey, String systemPromptTemplate,
                     long timeoutMillis, String proxyHost, int proxyPort) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.systemPromptTemplate = systemPromptTemplate;
        this.timeoutMillis = timeoutMillis;
        this.http = buildHttpClient(proxyHost, proxyPort, timeoutMillis);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "icbm-llm-translator");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 异步翻译：{@code text} → {@code targetLanguage}（源语言名 {@code sourceLanguage}，
     * 未知传 {@code "unknown language"}）。异常不吞——future 以 {@link LlmException} 完成，
     * 调用方决定降级方式。
     */
    public CompletableFuture<String> translateAsync(String text, String sourceLanguage, String targetLanguage) {
        return CompletableFuture.supplyAsync(
                () -> translateSync(text, sourceLanguage, targetLanguage), this.executor);
    }

    /** 同步翻译（仅 Executor 线程调用；超时/网络/解析失败 → {@link LlmException}）。 */
    private String translateSync(String text, String sourceLanguage, String targetLanguage) {
        String prompt = systemPrompt(this.systemPromptTemplate, sourceLanguage, targetLanguage);
        String body = requestBody(this.model, prompt, text).toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint(this.baseUrl)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.apiKey)
                .timeout(Duration.ofMillis(this.timeoutMillis))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("LLM HTTP " + response.statusCode() + ": "
                        + truncate(response.body(), 200));
            }
            String translated = extractContent(response.body());
            LOGGER.debug("[icbm-slave] 翻译完成: {}→{} 输入{}字 输出{}字",
                    sourceLanguage, targetLanguage, text.length(), translated.length());
            return translated;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM 请求失败: " + e, e);
        }
    }

    public void close() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(2, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.executor.shutdownNow();
        }
    }

    private static HttpClient buildHttpClient(String proxyHost, int proxyPort, long timeoutMillis) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis));
        if (proxyHost != null && !proxyHost.isBlank()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return builder.build();
    }

    // ===================== 纯函数（测试友好） =====================

    /** 端点：{@code baseUrl} 末尾已含 {@code /chat/completions} 则原样，否则追加。 */
    static String endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    /** 系统提示词：填充 {@code {sourceLanguage}}/{@code {targetLanguage}} 占位符。 */
    static String systemPrompt(String template, String sourceLanguage, String targetLanguage) {
        return template.replace("{sourceLanguage}", sourceLanguage)
                .replace("{targetLanguage}", targetLanguage);
    }

    /** OpenAI 兼容请求体：model + system/user 消息（参考 llm.py messages 结构）。 */
    static JsonObject requestBody(String model, String systemPrompt, String userText) {
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userText);
        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("temperature", 0.3);
        return body;
    }

    /** 响应解析：{@code choices[0].message.content}；缺失/空 → {@link LlmException}。 */
    static String extractContent(String responseBody) throws LlmException {
        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (Exception e) {
            throw new LlmException("LLM 响应不是合法 JSON: " + truncate(responseBody, 200), e);
        }
        if (root.has("error")) {
            throw new LlmException("LLM 返回错误: " + root.get("error"));
        }
        JsonArray choices = root.has("choices") ? root.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("LLM 响应缺 choices: " + truncate(responseBody, 200));
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new LlmException("LLM 响应缺 choices[0].message.content: " + truncate(responseBody, 200));
        }
        String content = message.get("content").getAsString();
        if (content == null || content.isBlank()) {
            throw new LlmException("LLM 返回空内容（fail closed）");
        }
        return content.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** LLM 调用失败（超时/网络/非 200/响应格式不符）——调用方降级，不吞错。 */
    public static final class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package cn.cubegarden.icbm.caching.slave.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * slave 配置（服务器世界根目录 {@code icbm-control.properties}，与源服/开发工具同源）。
 *
 * <pre>
 *   control.masterHost=127.0.0.1
 *   control.masterPort=11420
 *   control.psk=...
 *   control.uuid=1112131415161718191a1b1c1d1e1f20   （AUTH 身份，master roleBindings 绑定 SLAVE）
 *   control.serverName=lobby-1                       （缓存隔离键）
 *   control.storeRoot=icbm-objects                   （磁盘对象库目录，相对世界根）
 *   control.dimensions=minecraft:overworld           （订阅维度，逗号分隔）
 *   control.viewDistance=8
 *   control.selfcheck=true
 *   icbm.brandLocation=HongKong:Kowloon        （可选：服务器品牌地段名，格式「行政区:城市或区域」，
 *                                              默认空 → brand=ICBM Caching Server；配置后 →
 *                                              ICBM Caching Server (HongKong:Kowloon)，F3 可见）
 *   llm.baseUrl=https://api.deepseek.com       （可选 LLM 翻译：OpenAI 兼容 Chat Completions
 *   llm.model=deepseek-chat                    端点/模型/密钥；apiKey 为空 → 翻译功能关闭，
 *   llm.apiKey=                                聊天原文直通，fail closed）
 *   llm.systemPrompt=你是 Minecraft 服务器翻译助手……（提示词，支持
 *                                              {sourceLanguage}/{targetLanguage} 占位符）
 *   llm.timeoutMillis=15000                     （单次 LLM 请求超时）
 *   llm.maxTextLength=512                       （单条输入最大字符数，超出截断）
 *   llm.languages=zh_hk,zh_tw,en,ja,de,fr,zh_cn （启用目标语言短码，逗号分隔）
 *   llm.proxyHost=                              （可选 HTTP 代理；不自动读 HTTPS_PROXY）
 *   llm.proxyPort=0
 * </pre>
 *
 * <p>配置缺失/无效 → fail closed：控制通道不启动（玩家登录被拒，不服务不确定基线）。
 */
public final class SlaveConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlaveConfig.class);

    private final String masterHost;
    private final int masterPort;
    private final String psk;
    private final String uuidHex;
    private final String serverName;
    private final Path storeRoot;
    private final List<String> dimensions;
    private final int viewDistance;
    private final long readTimeoutMillis;
    private final long reconnectBackoffMillis;
    private final long heartbeatIntervalMillis;
    private final boolean selfcheck;
    private final String brandLocation;
    private final String llmBaseUrl;
    private final String llmModel;
    private final String llmApiKey;
    private final String llmSystemPrompt;
    private final long llmTimeoutMillis;
    private final int llmMaxTextLength;
    private final List<String> llmLanguages;
    private final String llmProxyHost;
    private final int llmProxyPort;

    private SlaveConfig(Builder b) {
        this.masterHost = b.masterHost;
        this.masterPort = b.masterPort;
        this.psk = b.psk;
        this.uuidHex = b.uuidHex;
        this.serverName = b.serverName;
        this.storeRoot = b.storeRoot;
        this.dimensions = b.dimensions;
        this.viewDistance = b.viewDistance;
        this.readTimeoutMillis = b.readTimeoutMillis;
        this.reconnectBackoffMillis = b.reconnectBackoffMillis;
        this.heartbeatIntervalMillis = b.heartbeatIntervalMillis;
        this.selfcheck = b.selfcheck;
        this.brandLocation = b.brandLocation;
        this.llmBaseUrl = b.llmBaseUrl;
        this.llmModel = b.llmModel;
        this.llmApiKey = b.llmApiKey;
        this.llmSystemPrompt = b.llmSystemPrompt;
        this.llmTimeoutMillis = b.llmTimeoutMillis;
        this.llmMaxTextLength = b.llmMaxTextLength;
        this.llmLanguages = b.llmLanguages;
        this.llmProxyHost = b.llmProxyHost;
        this.llmProxyPort = b.llmProxyPort;
    }

    public static SlaveConfig load(Path worldRoot) {
        Path configPath = worldRoot.resolve("icbm-control.properties");
        Properties props = new Properties();
        if (Files.exists(configPath)) {
            // UTF-8 读取（Properties.load(InputStream) 按 ISO-8859-1 解码会乱码中文提示词）
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("读取 icbm-control.properties 失败（fail closed）: " + configPath, e);
            }
        }
        String masterHost = props.getProperty("control.masterHost", "");
        String masterPort = props.getProperty("control.masterPort", "");
        String psk = props.getProperty("control.psk", "");
        String uuid = props.getProperty("control.uuid", "");
        String serverName = props.getProperty("control.serverName", "");
        if (masterHost.isEmpty() || masterPort.isEmpty() || psk.isEmpty() || uuid.isEmpty() || serverName.isEmpty()) {
            throw new IllegalStateException("icbm-control.properties 缺少 masterHost/masterPort/psk/uuid/serverName"
                    + "（fail closed：slave 控制通道不启动）: " + configPath);
        }
        String storeRoot = props.getProperty("control.storeRoot", "icbm-objects");
        List<String> dimensions = Arrays.stream(props.getProperty("control.dimensions", "minecraft:overworld")
                .split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        int view = Integer.parseInt(props.getProperty("control.viewDistance", "8"));
        long readTimeout = Long.parseLong(props.getProperty("control.readTimeoutMillis", "30000"));
        long backoff = Long.parseLong(props.getProperty("control.reconnectBackoffMillis", "3000"));
        // 心跳 < master 读超时（HELLO_TIMEOUT_MILLIS=10s，EncapsulatedControlServer 未放宽）：
        // slave 必须主动保活，否则 master 误断（里程碑 7 端到端暴露）
        long heartbeat = Long.parseLong(props.getProperty("control.heartbeatIntervalMillis", "8000"));
        boolean selfcheck = Boolean.parseBoolean(props.getProperty("control.selfcheck", "true"));
        String brandLocation = props.getProperty("icbm.brandLocation", "").trim();
        String llmBaseUrl = props.getProperty("llm.baseUrl", "https://api.deepseek.com").trim();
        String llmModel = props.getProperty("llm.model", "deepseek-chat").trim();
        String llmApiKey = props.getProperty("llm.apiKey", "").trim();
        String llmSystemPrompt = props.getProperty("llm.systemPrompt", DEFAULT_LLM_SYSTEM_PROMPT);
        long llmTimeout = Long.parseLong(props.getProperty("llm.timeoutMillis", "15000"));
        int llmMaxTextLength = Integer.parseInt(props.getProperty("llm.maxTextLength", "512"));
        List<String> llmLanguages = parseLanguages(props.getProperty("llm.languages",
                "zh_hk,zh_tw,en,ja,de,fr,zh_cn"));
        String llmProxyHost = props.getProperty("llm.proxyHost", "").trim();
        int llmProxyPort = Integer.parseInt(props.getProperty("llm.proxyPort", "0"));
        if (llmTimeout <= 0 || llmMaxTextLength <= 0 || llmProxyPort < 0) {
            throw new IllegalStateException("llm.timeoutMillis/llm.maxTextLength/llm.proxyPort 无效"
                    + "（fail closed：LLM 翻译不启用）: " + configPath);
        }
        Builder b = new Builder()
                .masterHost(masterHost).masterPort(Integer.parseInt(masterPort))
                .psk(psk).uuidHex(uuid).serverName(serverName)
                .storeRoot(worldRoot.resolve(storeRoot))
                .dimensions(dimensions).viewDistance(view)
                .readTimeoutMillis(readTimeout).reconnectBackoffMillis(backoff)
                .heartbeatIntervalMillis(heartbeat)
                .selfcheck(selfcheck).brandLocation(brandLocation)
                .llmBaseUrl(llmBaseUrl).llmModel(llmModel).llmApiKey(llmApiKey)
                .llmSystemPrompt(llmSystemPrompt).llmTimeoutMillis(llmTimeout)
                .llmMaxTextLength(llmMaxTextLength).llmLanguages(llmLanguages)
                .llmProxyHost(llmProxyHost).llmProxyPort(llmProxyPort);
        SlaveConfig config = b.build();
        LOGGER.info("[icbm-slave] 配置: master={}:{} serverName={} dims={} storeRoot={} viewDistance={}",
                config.masterHost(), config.masterPort(), config.serverName(),
                config.dimensions(), config.storeRoot(), config.viewDistance());
        return config;
    }

    public String masterHost() {
        return this.masterHost;
    }

    public int masterPort() {
        return this.masterPort;
    }

    public String psk() {
        return this.psk;
    }

    public String uuidHex() {
        return this.uuidHex;
    }

    public String serverName() {
        return this.serverName;
    }

    public Path storeRoot() {
        return this.storeRoot;
    }

    public List<String> dimensions() {
        return this.dimensions;
    }

    public int viewDistance() {
        return this.viewDistance;
    }

    public long readTimeoutMillis() {
        return this.readTimeoutMillis;
    }

    public long reconnectBackoffMillis() {
        return this.reconnectBackoffMillis;
    }

    /** 心跳间隔（对 master 保活；须小于 master 侧读超时）。 */
    public long heartbeatIntervalMillis() {
        return this.heartbeatIntervalMillis;
    }

    public boolean selfcheck() {
        return this.selfcheck;
    }

    /** 服务器品牌（F3 Brand）：地段名空 → {@code ICBM Caching Server}；
     *  配置后 → {@code ICBM Caching Server (<地段名>)}，如 (HongKong:Kowloon)。 */
    public String brand() {
        return this.brandLocation.isEmpty()
                ? "ICBM Caching Server"
                : "ICBM Caching Server (" + this.brandLocation + ")";
    }

    /** LLM 翻译是否启用（apiKey 非空）。未配置 → 翻译功能整体关闭（fail closed）。 */
    public boolean translationEnabled() {
        return !this.llmApiKey.isEmpty();
    }

    public String llmBaseUrl() {
        return this.llmBaseUrl;
    }

    public String llmModel() {
        return this.llmModel;
    }

    public String llmApiKey() {
        return this.llmApiKey;
    }

    /** LLM 系统提示词（含 {sourceLanguage}/{targetLanguage} 占位符）。 */
    public String llmSystemPrompt() {
        return this.llmSystemPrompt;
    }

    /** 单次 LLM 请求超时（毫秒）。 */
    public long llmTimeoutMillis() {
        return this.llmTimeoutMillis;
    }

    /** 单条输入最大字符数（超出截断）。 */
    public int llmMaxTextLength() {
        return this.llmMaxTextLength;
    }

    /** 启用的目标语言短码（llm.languages，已校验合法）。 */
    public List<String> llmLanguages() {
        return this.llmLanguages;
    }

    /** HTTP 代理主机（空 → 直连；不自动读 HTTPS_PROXY）。 */
    public String llmProxyHost() {
        return this.llmProxyHost;
    }

    public int llmProxyPort() {
        return this.llmProxyPort;
    }

    /** 默认 LLM 系统提示词（支持 {sourceLanguage}/{targetLanguage} 占位符；
     *  提示词集成在 slave 配置中，运维可覆盖）。 */
    static final String DEFAULT_LLM_SYSTEM_PROMPT =
            "你是 Minecraft 服务器翻译助手。将以下 {sourceLanguage} 文本翻译成 {targetLanguage}，"
            + "只返回翻译结果，不要任何解释、注释或多余内容。Minecraft 专有名词"
            + "（物品、方块、生物等）使用游戏官方译名。";

    /** 解析 llm.languages 逗号分隔短码；未知短码 → 抛异常（fail closed，不静默降级）。 */
    private static List<String> parseLanguages(String raw) {
        List<String> codes = Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String code : codes) {
            if (cn.cubegarden.icbm.caching.slave.translate.TranslationLanguages.fromCode(code).isEmpty()) {
                throw new IllegalStateException("llm.languages 含未知短码: " + code
                        + "（fail closed：LLM 翻译不启用）");
            }
        }
        return codes;
    }

    public static final class Builder {
        private String masterHost = "";
        private int masterPort;
        private String psk = "";
        private String uuidHex = "";
        private String serverName = "";
        private Path storeRoot;
        private List<String> dimensions = List.of("minecraft:overworld");
        private int viewDistance = 8;
        private long readTimeoutMillis = 30_000;
        private long reconnectBackoffMillis = 3_000;
        private long heartbeatIntervalMillis = 8_000;
        private boolean selfcheck = true;
        private String brandLocation = "";
        private String llmBaseUrl = "https://api.deepseek.com";
        private String llmModel = "deepseek-chat";
        private String llmApiKey = "";
        private String llmSystemPrompt = DEFAULT_LLM_SYSTEM_PROMPT;
        private long llmTimeoutMillis = 15_000;
        private int llmMaxTextLength = 512;
        private List<String> llmLanguages = List.of("zh_hk", "zh_tw", "en", "ja", "de", "fr", "zh_cn");
        private String llmProxyHost = "";
        private int llmProxyPort = 0;

        public Builder masterHost(String v) {
            this.masterHost = v;
            return this;
        }

        public Builder masterPort(int v) {
            this.masterPort = v;
            return this;
        }

        public Builder psk(String v) {
            this.psk = v;
            return this;
        }

        public Builder uuidHex(String v) {
            this.uuidHex = v;
            return this;
        }

        public Builder serverName(String v) {
            this.serverName = v;
            return this;
        }

        public Builder storeRoot(Path v) {
            this.storeRoot = v;
            return this;
        }

        public Builder dimensions(List<String> v) {
            this.dimensions = v;
            return this;
        }

        public Builder viewDistance(int v) {
            this.viewDistance = v;
            return this;
        }

        public Builder readTimeoutMillis(long v) {
            this.readTimeoutMillis = v;
            return this;
        }

        public Builder reconnectBackoffMillis(long v) {
            this.reconnectBackoffMillis = v;
            return this;
        }

        public Builder heartbeatIntervalMillis(long v) {
            this.heartbeatIntervalMillis = v;
            return this;
        }

        public Builder selfcheck(boolean v) {
            this.selfcheck = v;
            return this;
        }

        public Builder brandLocation(String v) {
            this.brandLocation = v == null ? "" : v.trim();
            return this;
        }

        public Builder llmBaseUrl(String v) {
            this.llmBaseUrl = v;
            return this;
        }

        public Builder llmModel(String v) {
            this.llmModel = v;
            return this;
        }

        public Builder llmApiKey(String v) {
            this.llmApiKey = v == null ? "" : v.trim();
            return this;
        }

        public Builder llmSystemPrompt(String v) {
            this.llmSystemPrompt = v;
            return this;
        }

        public Builder llmTimeoutMillis(long v) {
            this.llmTimeoutMillis = v;
            return this;
        }

        public Builder llmMaxTextLength(int v) {
            this.llmMaxTextLength = v;
            return this;
        }

        public Builder llmLanguages(List<String> v) {
            this.llmLanguages = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public Builder llmProxyHost(String v) {
            this.llmProxyHost = v == null ? "" : v.trim();
            return this;
        }

        public Builder llmProxyPort(int v) {
            this.llmProxyPort = v;
            return this;
        }

        public SlaveConfig build() {
            return new SlaveConfig(this);
        }
    }
}

package cn.cubegarden.icbm.caching.control.auth;

import cn.cubegarden.icbm.caching.protocol.Messages;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mojang 正版验证（Auth Gateway 唯一验证者；HIERARCHY §2 / CACHE_PROTOCOL §9）。
 *
 * <p>复刻 26.3 官方语义（ServerLoginPacketListenerImpl.handleKey:194 对照）：
 * serverId = 客户端侧 sha1-hex(serverId ‖ publicKey ‖ sharedSecret)（slave 递交的
 * serverHash），master 调用
 * {@code https://sessionserver.mojang.com/session/minecraft/hasJoined?username=X&serverId=Y&ip=Z}
 * （Velocity LoginSessionHandler.java:59 同 URL）→ 200 JSON（id/name/properties）或 204 未验证。
 *
 * <p>fail closed：超时/不可用 → {@link AuthenticationUnavailable}（官方 authservers_down 语义，
 * 拒绝登录而非放行）；JSON 非法 → 拒绝。offline 开关（verifyMojang=false）→ 直接放行
 * （开发/测试惯例，生产必须开启，运维手册约束）。
 */
public final class MojangSessionVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(MojangSessionVerifier.class);
    private static final String HAS_JOINED_URL =
            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s&ip=%s";

    /** Mojang 验证服务不可用（官方 AuthenticationUnavailable 语义；fail closed 拒绝登录）。 */
    public static final class AuthenticationUnavailable extends RuntimeException {
        public AuthenticationUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final boolean verifyMojang;
    private final long timeoutMillis;
    private final String baseUrl;
    private final HttpClient http;

    public MojangSessionVerifier(boolean verifyMojang, long timeoutMillis) {
        this(verifyMojang, timeoutMillis, HAS_JOINED_URL);
    }

    /** 测试注入 baseUrl（默认官方 hasJoined URL；格式含 %s 占位，官方无占位时拼 query）。 */
    MojangSessionVerifier(boolean verifyMojang, long timeoutMillis, String baseUrl) {
        this.verifyMojang = verifyMojang;
        this.timeoutMillis = timeoutMillis;
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).build();
    }

    /**
     * 验证并返回 profile（含 properties）。offline → 构造离线 profile（name + 随机 UUID，
     * 无 properties）。null → 未验证（hasJoined 204）。
     *
     * @throws AuthenticationUnavailable 服务不可用（fail closed）
     */
    public Profile verify(String name, String serverHash, String ip) {
        if (!this.verifyMojang) {
            LOGGER.info("[control] offline 模式放行（未调用 Mojang）: {}", name);
            return new Profile(UUID.randomUUID(), name, new Messages.ProfileProperty[0]);
        }
        String query = "username=%s&serverId=%s&ip=%s".formatted(
                URLEncoder.encode(name, StandardCharsets.UTF_8),
                URLEncoder.encode(serverHash, StandardCharsets.UTF_8),
                URLEncoder.encode(ip, StandardCharsets.UTF_8));
        String url = this.baseUrl.contains("%s") ? this.baseUrl.formatted(
                URLEncoder.encode(name, StandardCharsets.UTF_8),
                URLEncoder.encode(serverHash, StandardCharsets.UTF_8),
                URLEncoder.encode(ip, StandardCharsets.UTF_8))
                : this.baseUrl + "?" + query;
        try {
            HttpResponse<String> response = this.http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(this.timeoutMillis))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 204) {
                return null; // 未验证（官方 unverified_username 语义）
            }
            if (response.statusCode() != 200) {
                throw new AuthenticationUnavailable("hasJoined HTTP " + response.statusCode(), null);
            }
            return parse(response.body());
        } catch (AuthenticationUnavailable e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthenticationUnavailable("hasJoined 调用失败: " + e.getMessage(), e);
        }
    }

    /** hasJoined 结果：UUID/name/properties（textures 签名属性随断言传 slave）。 */
    public record Profile(UUID uuid, String name, Messages.ProfileProperty[] properties) {
    }

    private static Profile parse(String json) {
        try {
            String id = jsonField(json, "id");
            String name = jsonField(json, "name");
            if (id == null || name == null) {
                throw new IllegalArgumentException("hasJoined JSON 缺少 id/name");
            }
            UUID uuid = UUID.fromString(id.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            List<Messages.ProfileProperty> properties = new ArrayList<>();
            String propsStart = "\"properties\":[";
            int start = json.indexOf(propsStart);
            if (start >= 0) {
                // 逐项解析 name/value/signature（极简 JSON 子集解析，字段顺序按 Mojang 输出）
                int pos = start + propsStart.length();
                while (pos < json.length() && json.charAt(pos) != ']') {
                    String propName = jsonStringValue(json, "\"name\":", pos);
                    pos = json.indexOf("\"value\":", pos);
                    String propValue = jsonStringValue(json, "\"value\":", pos);
                    pos = json.indexOf("\"signature\":", pos);
                    String signature = pos >= 0 && json.indexOf('}', pos) > pos
                            ? jsonStringValue(json, "\"signature\":", pos) : "";
                    properties.add(new Messages.ProfileProperty(propName, propValue, signature));
                    int close = json.indexOf('}', pos < 0 ? 0 : pos);
                    pos = close >= 0 ? close + 1 : json.length();
                }
            }
            return new Profile(uuid, name, properties.toArray(new Messages.ProfileProperty[0]));
        } catch (Exception e) {
            throw new IllegalArgumentException("hasJoined JSON 非法（fail closed）: " + e.getMessage());
        }
    }

    private static String jsonField(String json, String field) {
        int index = json.indexOf("\"" + field + "\":");
        return index < 0 ? null : jsonStringValue(json, "\"" + field + "\":", index);
    }

    private static String jsonStringValue(String json, String marker, int from) {
        int start = json.indexOf(marker, from);
        if (start < 0) {
            return "";
        }
        int quote = json.indexOf('"', start + marker.length());
        if (quote < 0) {
            return "";
        }
        int end = json.indexOf('"', quote + 1);
        return end < 0 ? "" : json.substring(quote + 1, end);
    }
}

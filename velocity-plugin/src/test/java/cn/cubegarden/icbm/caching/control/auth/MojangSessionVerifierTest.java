package cn.cubegarden.icbm.caching.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.Messages;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mojang 正版验证：本地假 session server（JDK HttpServer）→ hasJoined 200 JSON
 * （id/name/properties）与 204 未验证；offline 开关直接放行；服务不可用（500）
 * → AuthenticationUnavailable（fail closed）。
 */
class MojangSessionVerifierTest {

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final CopyOnWriteArrayList<Integer> responses = new CopyOnWriteArrayList<>();
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/session/minecraft/hasJoined", exchange -> {
            this.lastQuery.set(exchange.getRequestURI().getQuery());
            int code = this.responses.isEmpty() ? 200 : this.responses.remove(0);
            if (code == 204) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] body = ("{\"id\":\"7d0d8c4a-3a4b-4c5d-8e6f-9a0b1c2d3e4f\","
                        + "\"name\":\"Steve\",\"properties\":[{\"name\":\"textures\","
                        + "\"value\":\"eyJ0ZXh0dXJlcyI6e30=\",\"signature\":\"sig123\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        this.server.start();
        this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort()
                + "/session/minecraft/hasJoined?username=%s&serverId=%s&ip=%s";
    }

    @AfterEach
    void tearDown() {
        this.server.stop(0);
    }

    private MojangSessionVerifier verifier(boolean verifyMojang) {
        return new MojangSessionVerifier(verifyMojang, 5_000, this.baseUrl);
    }

    @Test
    void hasJoinedVerifiedWithProperties() {
        MojangSessionVerifier.Profile profile = verifier(true).verify("Steve", "digesthex", "127.0.0.1");
        assertNotNull(profile);
        assertEquals("Steve", profile.name());
        assertEquals("7d0d8c4a-3a4b-4c5d-8e6f-9a0b1c2d3e4f", profile.uuid().toString());
        assertEquals(1, profile.properties().length);
        assertEquals("textures", profile.properties()[0].name());
        assertEquals("eyJ0ZXh0dXJlcyI6e30=", profile.properties()[0].value());
        assertEquals("sig123", profile.properties()[0].signature());
        // hasJoined 参数复刻 26.3（ServerLoginPacketListenerImpl.handleKey:194）：username/serverId/ip
        assertTrue(this.lastQuery.get().contains("username=Steve"), this.lastQuery.get());
        assertTrue(this.lastQuery.get().contains("serverId=digesthex"), this.lastQuery.get());
        assertTrue(this.lastQuery.get().contains("ip=127.0.0.1"), this.lastQuery.get());
    }

    @Test
    void unverifiedReturnsNull() {
        this.responses.add(204);
        assertNull(verifier(true).verify("Steve", "digesthex", "127.0.0.1"),
                "hasJoined 204 → 未验证（null，拒绝登录）");
    }

    @Test
    void offlineModeSkipsMojang() {
        MojangSessionVerifier.Profile profile = verifier(false).verify("Steve", "hash", "127.0.0.1");
        assertNotNull(profile);
        assertEquals("Steve", profile.name());
        assertEquals(0, profile.properties().length);
    }

    @Test
    void serverErrorFailsClosed() {
        this.responses.add(500);
        assertThrows(MojangSessionVerifier.AuthenticationUnavailable.class,
                () -> verifier(true).verify("Steve", "hash", "127.0.0.1"),
                "服务端错误 → AuthenticationUnavailable（fail closed 拒绝，不猜测放行）");
    }

    @Test
    void malformedJsonRejected() {
        this.responses.add(200);
        // 覆盖非法 JSON：先用合法内容验证路径，再验证畸形响应被拒
        verifier(true).verify("Steve", "hash", "127.0.0.1");
        this.responses.add(200);
        this.server.removeContext("/session/minecraft/hasJoined");
        this.server.createContext("/session/minecraft/hasJoined", exchange -> {
            byte[] body = "{not json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        assertThrows(IllegalArgumentException.class,
                () -> verifier(true).verify("Steve", "hash", "127.0.0.1"),
                "畸形 JSON → 拒绝（fail closed）");
    }
}

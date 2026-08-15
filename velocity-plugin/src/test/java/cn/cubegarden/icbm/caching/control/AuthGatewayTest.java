package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener;
import cn.cubegarden.icbm.caching.control.auth.IdentitySigner;
import cn.cubegarden.icbm.caching.control.auth.MojangSessionVerifier;
import cn.cubegarden.icbm.caching.control.auth.SessionRegistry;
import cn.cubegarden.icbm.caching.control.auth.TicketVault;
import cn.cubegarden.icbm.caching.control.bridge.CompositeSessionListener;
import cn.cubegarden.icbm.caching.control.bridge.McPassthroughBridge;
import cn.cubegarden.icbm.caching.control.bridge.PacketLog;
import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlServer;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.net.SourceConnector;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.routing.BackendHealth;
import cn.cubegarden.icbm.caching.control.routing.CircuitBreaker;
import cn.cubegarden.icbm.caching.control.routing.RoutingPolicy;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Auth Gateway 完整流程（里程碑 5）：LOGIN_VERIFY 流——票据单次兑换（重放拒）→ 全局
 * 会话记账（重复会话拒新）→ 落点白名单 → Mojang 验证（本地假 session server）→ 断言
 * 签名（keyId + profileProperties）→ RES；SESSION_CLOSED 注销；slave 断开自动注销。
 */
class AuthGatewayTest {

    private static final String PSK = "auth-test-psk";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";
    private static final int PROTOCOL = 1_073_742_154;
    private static final String SLAVE_UUID = "1112131415161718191a1b1c1d1e1f20";
    private static final String ASSERTION_KEY = "assertion-key-0123456789abcdef";

    @TempDir
    Path tempDir;

    private ServiceConfig config;
    private SnapshotObjectStore store;
    private EventLog eventLog;
    private PeerRegistry registry;
    private ControlService service;
    private Replicator replicator;
    private EncapsulatedControlServer encapsulated;
    private SessionRegistry sessionRegistry;
    private TicketVault ticketVault;
    private IdentitySigner signer;
    private MojangSessionVerifier verifier;
    private RoutingPolicy routing;
    private BackendHealth backendHealth;
    private CircuitBreaker circuitBreaker;
    private McPassthroughBridge bridge;
    private TestBackendServer backend;
    private SourceConnector sourceConnector;

    @BeforeEach
    void setUp() throws Exception {
        this.config = new ServiceConfig(
                "127.0.0.1",
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1", "lobby-1", PSK, 0,
                Map.of(SLAVE_UUID, "SLAVE"), null, 16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL,
                new ServiceConfig.AuthConfig(false, 5_000, ASSERTION_KEY), List.of());
        this.store = new SnapshotObjectStore(this.config.storeRoot(), this.config.serverName());
        this.store.recover();
        this.eventLog = new EventLog(this.config.eventLogPath(), this.config.maxEventLogEntries());
        this.eventLog.recover();
        this.registry = new PeerRegistry(this.config.roleBindings());
        this.replicator = new Replicator(this.store, this.eventLog, this.config);
        this.service = new ControlService(this.store, this.eventLog, this.replicator, this.registry, this.config);
        this.sessionRegistry = new SessionRegistry();
        this.service.setSessionRegistry(this.sessionRegistry);
        this.ticketVault = new TicketVault();
        this.service.setTicketVault(this.ticketVault);
        this.signer = IdentitySigner.fromKey(ASSERTION_KEY.getBytes(StandardCharsets.UTF_8));
        this.verifier = new MojangSessionVerifier(false, 5_000);
        this.backendHealth = new BackendHealth();
        this.circuitBreaker = new CircuitBreaker(3, 30_000);
        this.routing = new RoutingPolicy(List.of(), this.backendHealth, this.circuitBreaker, 90_000);
        this.bridge = new McPassthroughBridge(this.routing, this.circuitBreaker, new PacketLog(64));
        AuthGatewayListener authGateway = new AuthGatewayListener(this.sessionRegistry, this.ticketVault,
                this.verifier, this.signer, this.routing, "master-1", 60_000);
        this.encapsulated = new EncapsulatedControlServer(this.config, this.service, this.replicator,
                this.registry, new CompositeSessionListener(authGateway, this.bridge));
        this.encapsulated.start();
        // slave 连接需源服先注册（PeerRegistry 约束）：起假源服 + SourceConnector
        this.backend = new TestBackendServer(TestBackendServer.SECRET, TestBackendServer.SECRET);
        ServiceConfig withBackend = new ServiceConfig(
                "127.0.0.1",
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1", "lobby-1", PSK, 0,
                Map.of(SLAVE_UUID, "SLAVE"),
                new ServiceConfig.BackendServer("127.0.0.1", this.backend.port(), TestBackendServer.SECRET),
                16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
        this.sourceConnector = new SourceConnector(withBackend, this.service, this.replicator, this.registry);
        this.sourceConnector.start();
        this.bridge.attachSourceConnector(this.sourceConnector);
        assertTrue(this.backend.helloAcked.await(5, TimeUnit.SECONDS), "源服必须注册: " + this.backend.events);
        this.backendHealth.markConnected(System.currentTimeMillis());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.sourceConnector != null) {
            this.sourceConnector.close();
        }
        if (this.backend != null) {
            this.backend.close();
        }
        this.encapsulated.close();
    }

    private byte[] loginVerifyReq() {
        return loginVerifyReq(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});
    }

    private byte[] loginVerifyReq(byte[] nonce) {
        return EncapsulatedChannelClient.encode(MessageType.LOGIN_VERIFY_REQ,
                new Messages.LoginVerifyReq("Steve", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                        new Messages.ProfileProperty[0], "serverHash", new byte[16],
                        "lobby-1", "127.0.0.1:50000", nonce, 1_700_000_000_000L));
    }

    private EncapsulatedChannelClient newSlave(String slaveId, java.util.List<Messages.LoginVerifyRes> responses,
                                               CountDownLatch first) throws Exception {
        return new EncapsulatedChannelClient("127.0.0.1",
                this.encapsulated.port(), slaveId, SLAVE_UUID, PSK,
                new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, "lobby-1", "26.3", PROTOCOL,
                        "0.19.3", "0.157.1+26.3", "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0),
                5000, 100, false, new EncapsulatedChannelClient.Listener() {
                    @Override
                    public void onLoginVerifyResponse(byte[] body) {
                        responses.add(EncapsulatedChannelClient.decode(MessageType.LOGIN_VERIFY_RES, body));
                        first.countDown();
                    }
                });
    }

    private static Messages.LoginVerifyRes awaitRes(java.util.List<Messages.LoginVerifyRes> responses,
                                                    int index) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (responses.size() > index) {
                return responses.get(index);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 LOGIN_VERIFY_RES[" + index + "] 超时（共 " + responses.size() + " 条）");
    }

    @Test
    void loginVerifySignedAndDuplicateRejected() throws Exception {
        java.util.List<Messages.LoginVerifyRes> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", responses, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            assertTrue(slave.sendLoginVerify(loginVerifyReq()), "LOGIN_VERIFY_REQ 必须发出");
            Messages.LoginVerifyRes firstRes = awaitRes(responses, 0);
            assertTrue(firstRes.verified(), "offline 放行: " + firstRes.reason());
            assertNotNull(firstRes.identity());
            Messages.MasterVerifiedIdentity identity = firstRes.identity();
            assertEquals("master-1", identity.issuer());
            assertEquals(this.signer.keyId(), identity.keyId());
            assertEquals("lobby-1", identity.audience());
            assertTrue(identity.signature().length > 0, "断言必须签名");
            assertEquals(null, this.signer.verify(identity), "签名必须验证通过");
            assertEquals(1, this.sessionRegistry.activeCount(), "验证通过必须记账");
            assertTrue(this.ticketVault.isActive(identity.sessionId()), "票据必须登记");

            // 同一玩家再次登录（新 nonce）：重复会话被拒
            assertTrue(slave.sendLoginVerify(loginVerifyReq(new byte[] {8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8})),
                    "第二次 LOGIN_VERIFY_REQ 必须发出");
            Messages.LoginVerifyRes secondRes = awaitRes(responses, 1);
            assertFalse(secondRes.verified(), "重复会话必须被拒");
            assertEquals("DUPLICATE_SESSION", secondRes.reason());
            assertEquals(1, this.sessionRegistry.activeCount(), "拒绝不改变记账");
        }
    }

    @Test
    void replayedNonceRejected() throws Exception {
        java.util.List<Messages.LoginVerifyRes> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        byte[] nonce = new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", responses, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            assertTrue(slave.sendLoginVerify(loginVerifyReq(nonce)));
            assertTrue(awaitRes(responses, 0).verified());

            // 同一 nonce 重放：票据不重放
            assertTrue(slave.sendLoginVerify(loginVerifyReq(nonce)));
            Messages.LoginVerifyRes second = awaitRes(responses, 1);
            assertFalse(second.verified(), "同一 nonce 重放必须被拒");
            assertEquals("REPLAYED_NONCE", second.reason());
            assertEquals(1, this.sessionRegistry.activeCount(), "重放不改变记账");
        }
    }

    @Test
    void sessionClosedUnregisters() throws Exception {
        java.util.List<Messages.LoginVerifyRes> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", responses, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            slave.sendLoginVerify(loginVerifyReq());
            String sessionId = awaitRes(responses, 0).identity().sessionId();
            assertEquals(1, this.sessionRegistry.activeCount());

            slave.send(MessageType.SESSION_CLOSED, 1, 0, EncapsulatedChannelClient.encode(
                    MessageType.SESSION_CLOSED, new Messages.SessionClosed(
                            new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                            sessionId, "disconnect")));
            Thread.sleep(300);
            assertEquals(0, this.sessionRegistry.activeCount(), "SESSION_CLOSED 必须注销记账");
            assertFalse(this.ticketVault.isActive(sessionId), "票据必须吊销");
        }
    }

    @Test
    void slaveDisconnectCleansUpSessions() throws Exception {
        java.util.List<Messages.LoginVerifyRes> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", responses, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            slave.sendLoginVerify(loginVerifyReq());
            awaitRes(responses, 0);
            assertEquals(1, this.sessionRegistry.activeCount());
        }
        // slave 连接断开 → 会话自动注销（SESSION_CLOSED 未送达）
        Thread.sleep(300);
        assertEquals(0, this.sessionRegistry.activeCount(), "slave 断开必须自动注销活跃会话");
    }
}

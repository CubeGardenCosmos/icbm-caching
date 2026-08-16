package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener;
import cn.cubegarden.icbm.caching.control.auth.IdentitySigner;
import cn.cubegarden.icbm.caching.control.auth.LoginHandshake;
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
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Auth Gateway 完整流程（里程碑 5 重构后）：LOGIN_VERIFY 流两阶段——阶段 A（LOGIN_VERIFY_REQ
 * → challenge + master 公钥）→ 客户端加密响应 → 阶段 B（LOGIN_KEY_REQ → sharedSecret +
 * 断言）。票据单次兑换（重放拒）→ 落点白名单 →（阶段 B）Mojang/offline 验证 → 以验证 UUID
 * 记账（重复会话拒新）→ 断言签名；SESSION_CLOSED 注销；slave 断开自动注销。
 */
class AuthGatewayTest {

    private static final String PSK = "auth-test-psk";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";
    private static final int PROTOCOL = 1_073_742_154;
    private static final String SLAVE_UUID = "1112131415161718191a1b1c1d1e1f20";
    private static final String ASSERTION_KEY = "assertion-key-0123456789abcdef";
    private static final byte[] CLAIMED_UUID = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

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
                new ServiceConfig.AuthConfig(false, 5_000, ASSERTION_KEY, false), List.of());
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
        this.backendHealth = new BackendHealth();
        this.circuitBreaker = new CircuitBreaker(3, 30_000);
        this.routing = new RoutingPolicy(List.of(), this.backendHealth, this.circuitBreaker, 90_000);
        this.bridge = new McPassthroughBridge(this.routing, this.circuitBreaker, new PacketLog(64));
        // offline 验证（verifyMojang=false）：不调用 Mojang，仍记账；身份 = 客户端声明
        LoginHandshake handshake = new LoginHandshake(false, false);
        AuthGatewayListener authGateway = new AuthGatewayListener(this.sessionRegistry, this.ticketVault,
                handshake, this.signer, this.routing, "master-1", 60_000);
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
        this.sourceConnector = new SourceConnector(withBackend, this.service, this.replicator, this.registry, new cn.cubegarden.icbm.caching.control.routing.BackendHealth());
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

    /** 模拟客户端：两阶段登录，返回 (阶段A RES, 阶段B RES)。 */
    private record LoginOutcome(Messages.LoginVerifyRes challenge, Messages.LoginKeyRes result) {
    }

    private LoginOutcome doLogin(EncapsulatedChannelClient slave, List<Messages.LoginVerifyRes> verifyRes,
                                 List<Messages.LoginKeyRes> keyRes, byte[] nonce) throws Exception {
        assertTrue(slave.sendLoginVerify(MessageType.LOGIN_VERIFY_REQ,
                        EncapsulatedChannelClient.encode(MessageType.LOGIN_VERIFY_REQ, loginVerifyMsg(nonce))),
                "LOGIN_VERIFY_REQ 必须发出");
        int verifyIndex = verifyRes.size();
        Messages.LoginVerifyRes challenge = awaitVerifyRes(verifyRes, verifyIndex);
        assertTrue(challenge.ok(), "阶段A 必须拿到挑战: " + challenge.reason());
        // 客户端加密响应：用 master 公钥加密共享密钥 + 挑战回显（ServerboundKeyPacket 语义）
        byte[] sharedSecret = new byte[16];
        new SecureRandom().nextBytes(sharedSecret);
        byte[] encryptedSecret = encryptRsa(challenge.publicKeyDer(), sharedSecret);
        byte[] encryptedChallenge = encryptRsa(challenge.publicKeyDer(), challenge.challenge());
        assertTrue(slave.sendLoginVerify(MessageType.LOGIN_KEY_REQ,
                        EncapsulatedChannelClient.encode(MessageType.LOGIN_KEY_REQ,
                                new Messages.LoginKeyReq(CLAIMED_UUID, nonce, encryptedSecret, encryptedChallenge))),
                "LOGIN_KEY_REQ 必须发出");
        return new LoginOutcome(challenge, awaitKeyRes(keyRes, keyRes.size()));
    }

    private byte[] loginVerifyReq() {
        return EncapsulatedChannelClient.encode(MessageType.LOGIN_VERIFY_REQ, loginVerifyMsg(loginNonce()));
    }

    private Messages.LoginVerifyReq loginVerifyMsg(byte[] nonce) {
        return new Messages.LoginVerifyReq("Steve", CLAIMED_UUID,
                "lobby-1", "127.0.0.1:50000", nonce, 1_700_000_000_000L);
    }

    private EncapsulatedChannelClient newSlave(String slaveId, List<Messages.LoginVerifyRes> verifyRes,
                                               List<Messages.LoginKeyRes> keyRes,
                                               CountDownLatch first) throws Exception {
        return new EncapsulatedChannelClient("127.0.0.1",
                this.encapsulated.port(), slaveId, SLAVE_UUID, PSK,
                new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, "lobby-1", "26.3", PROTOCOL,
                        "0.19.3", "0.157.1+26.3", "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0),
                5000, 100, false, new EncapsulatedChannelClient.Listener() {
                    @Override
                    public void onLoginVerifyResponse(MessageType type, byte[] body) {
                        if (type == MessageType.LOGIN_KEY_RES) {
                            keyRes.add(EncapsulatedChannelClient.decode(MessageType.LOGIN_KEY_RES, body));
                        } else {
                            verifyRes.add(EncapsulatedChannelClient.decode(MessageType.LOGIN_VERIFY_RES, body));
                        }
                        first.countDown();
                    }
                });
    }

    private static Messages.LoginVerifyRes awaitVerifyRes(List<Messages.LoginVerifyRes> responses, int index)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (responses.size() > index) {
                return responses.get(index);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 LOGIN_VERIFY_RES[" + index + "] 超时（共 " + responses.size() + " 条）");
    }

    private static Messages.LoginKeyRes awaitKeyRes(List<Messages.LoginKeyRes> responses, int index)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (responses.size() > index) {
                return responses.get(index);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 LOGIN_KEY_RES[" + index + "] 超时（共 " + responses.size() + " 条）");
    }

    private static byte[] encryptRsa(byte[] publicKeyDer, byte[] plain) throws Exception {
        java.security.PublicKey publicKey =
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyDer));
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(plain);
    }

    @Test
    void loginVerifySignedAndDuplicateRejected() throws Exception {
        List<Messages.LoginVerifyRes> verifyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Messages.LoginKeyRes> keyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", verifyRes, keyRes, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            LoginOutcome first = this.doLogin(slave, verifyRes, keyRes, loginNonce());
            assertTrue(first.result().verified(), "offline 放行: " + first.result().reason());
            assertNotNull(first.result().identity());
            Messages.MasterVerifiedIdentity identity = first.result().identity();
            assertEquals("master-1", identity.issuer());
            assertEquals(this.signer.keyId(), identity.keyId());
            assertEquals("lobby-1", identity.audience());
            assertTrue(identity.signature().length > 0, "断言必须签名");
            assertEquals(null, this.signer.verify(identity), "签名必须验证通过");
            assertEquals(16, first.result().sharedSecret().length, "会话密钥必须 16 字节");
            assertEquals(1, this.sessionRegistry.activeCount(), "验证通过必须记账");
            assertTrue(this.ticketVault.isActive(identity.sessionId()), "票据必须登记");

            // 同一玩家再次登录（新 nonce、同一声明 UUID）：offline 以声明身份记账 → 重复会话被拒
            LoginOutcome second = this.doLogin(slave, verifyRes, keyRes, loginNonce());
            assertFalse(second.result().verified(), "重复会话必须被拒");
            assertEquals("DUPLICATE_SESSION", second.result().reason());
            assertEquals(1, this.sessionRegistry.activeCount(), "拒绝不改变记账");
        }
    }

    @Test
    void replayedNonceRejected() throws Exception {
        List<Messages.LoginVerifyRes> verifyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Messages.LoginKeyRes> keyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        byte[] nonce = new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", verifyRes, keyRes, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            assertTrue(this.doLogin(slave, verifyRes, keyRes, nonce).result().verified());

            // 同一 nonce 重放：阶段A 票据不重放
            assertTrue(slave.sendLoginVerify(MessageType.LOGIN_VERIFY_REQ,
                    EncapsulatedChannelClient.encode(MessageType.LOGIN_VERIFY_REQ, loginVerifyMsg(nonce))));
            Messages.LoginVerifyRes second = awaitVerifyRes(verifyRes, 1);
            assertFalse(second.ok(), "同一 nonce 重放必须被拒");
            assertEquals("REPLAYED_NONCE", second.reason());
            assertEquals(1, this.sessionRegistry.activeCount(), "重放不改变记账");
        }
    }

    @Test
    void sessionClosedUnregisters() throws Exception {
        List<Messages.LoginVerifyRes> verifyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Messages.LoginKeyRes> keyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", verifyRes, keyRes, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            Messages.LoginKeyRes res = this.doLogin(slave, verifyRes, keyRes, loginNonce()).result();
            String sessionId = res.identity().sessionId();
            assertEquals(1, this.sessionRegistry.activeCount());

            slave.send(MessageType.SESSION_CLOSED, 1, 0, EncapsulatedChannelClient.encode(
                    MessageType.SESSION_CLOSED, new Messages.SessionClosed(
                            CLAIMED_UUID, sessionId, "disconnect")));
            Thread.sleep(300);
            assertEquals(0, this.sessionRegistry.activeCount(), "SESSION_CLOSED 必须注销记账");
            assertFalse(this.ticketVault.isActive(sessionId), "票据必须吊销");
        }
    }

    @Test
    void slaveDisconnectCleansUpSessions() throws Exception {
        List<Messages.LoginVerifyRes> verifyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Messages.LoginKeyRes> keyRes = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave("lobby-1", verifyRes, keyRes, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            this.doLogin(slave, verifyRes, keyRes, loginNonce());
            assertEquals(1, this.sessionRegistry.activeCount());
        }
        // slave 连接断开 → 会话自动注销（SESSION_CLOSED 未送达）
        Thread.sleep(300);
        assertEquals(0, this.sessionRegistry.activeCount(), "slave 断开必须自动注销活跃会话");
    }

    private static byte[] loginNonce() {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }
}

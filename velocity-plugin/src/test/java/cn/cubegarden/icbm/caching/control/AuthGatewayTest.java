package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener;
import cn.cubegarden.icbm.caching.control.auth.SessionRegistry;
import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlServer;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Auth Gateway 最小实现（里程碑 4）集成测试：LOGIN_VERIFY 流——slave 递交材料 →
 * master 记账放行（RES verified）→ 重复会话（同 UUID）被拒（DUPLICATE_SESSION）。
 * 完整 Mojang 验证/签名随里程碑 5。
 */
class AuthGatewayTest {

    private static final String PSK = "auth-test-psk";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";
    private static final int PROTOCOL = 1_073_742_154;
    private static final String SLAVE_UUID = "1112131415161718191a1b1c1d1e1f20";

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
    private TestBackendServer backend;
    private cn.cubegarden.icbm.caching.control.net.SourceConnector sourceConnector;

    @BeforeEach
    void setUp() throws Exception {
        this.config = new ServiceConfig(
                "127.0.0.1",
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1", "lobby-1", PSK, 0,
                Map.of(SLAVE_UUID, "SLAVE"), null, 16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
        this.store = new SnapshotObjectStore(this.config.storeRoot(), this.config.serverName());
        this.store.recover();
        this.eventLog = new EventLog(this.config.eventLogPath(), this.config.maxEventLogEntries());
        this.eventLog.recover();
        this.registry = new PeerRegistry(this.config.roleBindings());
        this.replicator = new Replicator(this.store, this.eventLog, this.config);
        this.service = new ControlService(this.store, this.eventLog, this.replicator, this.registry, this.config);
        this.sessionRegistry = new SessionRegistry();
        this.service.setSessionRegistry(this.sessionRegistry);
        this.encapsulated = new EncapsulatedControlServer(this.config, this.service, this.replicator,
                this.registry, new AuthGatewayListener(this.sessionRegistry));
        this.encapsulated.start();
        // slave 连接需源服先注册（PeerRegistry 约束）：起假源服 + SourceConnector
        this.backend = new TestBackendServer(TestBackendServer.SECRET, TestBackendServer.SECRET);
        ServiceConfig withBackend = new ServiceConfig(
                "127.0.0.1",
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1", "lobby-1", PSK, 0,
                java.util.Map.of(SLAVE_UUID, "SLAVE"),
                new ServiceConfig.BackendServer("127.0.0.1", this.backend.port(), TestBackendServer.SECRET),
                16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
        this.sourceConnector = new cn.cubegarden.icbm.caching.control.net.SourceConnector(
                withBackend, this.service, this.replicator, this.registry);
        this.sourceConnector.start();
        assertTrue(this.backend.helloAcked.await(5, TimeUnit.SECONDS), "源服必须注册: " + this.backend.events);
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
        return EncapsulatedChannelClient.encode(MessageType.LOGIN_VERIFY_REQ,
                new Messages.LoginVerifyReq("Steve", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                        new Messages.ProfileProperty[0], "serverHash", new byte[16],
                        "lobby-1", "127.0.0.1:50000", new byte[16], 1_700_000_000_000L));
    }

    @Test
    void loginVerifyAcceptedAndDuplicateRejected() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        AtomicReference<Messages.LoginVerifyRes> firstRes = new AtomicReference<>();
        AtomicReference<Messages.LoginVerifyRes> secondRes = new AtomicReference<>();
        try (EncapsulatedChannelClient slave = new EncapsulatedChannelClient("127.0.0.1",
                this.encapsulated.port(), "slave-a", SLAVE_UUID, PSK,
                new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, "lobby-1", "26.3", PROTOCOL,
                        "0.19.3", "0.157.1+26.3", "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0),
                5000, 100, false, new EncapsulatedChannelClient.Listener() {
                    @Override
                    public void onLoginVerifyResponse(byte[] body) {
                        Messages.LoginVerifyRes res = EncapsulatedChannelClient.decode(
                                MessageType.LOGIN_VERIFY_RES, body);
                        if (firstRes.get() == null) {
                            firstRes.set(res);
                            first.countDown();
                        } else {
                            secondRes.set(res);
                            second.countDown();
                        }
                    }
                })) {
            slave.start();
            Thread.sleep(300);
            // 第一个会话：放行 + 记账
            assertTrue(slave.sendLoginVerify(loginVerifyReq()), "LOGIN_VERIFY_REQ 必须发出");
            assertTrue(first.await(5, TimeUnit.SECONDS), "必须收到 LOGIN_VERIFY_RES");
            assertTrue(firstRes.get().verified(), "offline 放行: " + firstRes.get().reason());
            assertNotNull(firstRes.get().identity());
            assertEquals(1, this.sessionRegistry.activeCount(), "验证通过必须记账");

            // 同一玩家再次登录：重复会话被拒
            assertTrue(slave.sendLoginVerify(loginVerifyReq()), "第二次 LOGIN_VERIFY_REQ 必须发出");
            assertTrue(second.await(5, TimeUnit.SECONDS), "必须收到第二次 LOGIN_VERIFY_RES");
            assertFalse(secondRes.get().verified(), "重复会话必须被拒");
            assertEquals("DUPLICATE_SESSION", secondRes.get().reason());
            assertEquals(1, this.sessionRegistry.activeCount(), "拒绝不改变记账");
        }
    }

    @Test
    void sessionClosedUnregisters() throws Exception {
        CountDownLatch res = new CountDownLatch(1);
        AtomicReference<Messages.LoginVerifyRes> firstRes = new AtomicReference<>();
        try (EncapsulatedChannelClient slave = new EncapsulatedChannelClient("127.0.0.1",
                this.encapsulated.port(), "slave-a", SLAVE_UUID, PSK,
                new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, "lobby-1", "26.3", PROTOCOL,
                        "0.19.3", "0.157.1+26.3", "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0),
                5000, 100, false, new EncapsulatedChannelClient.Listener() {
                    @Override
                    public void onLoginVerifyResponse(byte[] body) {
                        firstRes.set(EncapsulatedChannelClient.decode(MessageType.LOGIN_VERIFY_RES, body));
                        res.countDown();
                    }
                })) {
            slave.start();
            Thread.sleep(300);
            slave.sendLoginVerify(loginVerifyReq());
            assertTrue(res.await(5, TimeUnit.SECONDS));
            String sessionId = firstRes.get().identity().sessionId();
            assertEquals(1, this.sessionRegistry.activeCount());

            // 玩家登出：SESSION_CLOSED → 注销记账
            slave.send(MessageType.SESSION_CLOSED, 1, 0, EncapsulatedChannelClient.encode(
                    MessageType.SESSION_CLOSED, new Messages.SessionClosed(
                            new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                            sessionId, "disconnect")));
            Thread.sleep(300);
            assertEquals(0, this.sessionRegistry.activeCount(), "SESSION_CLOSED 必须注销记账");
        }
    }
}

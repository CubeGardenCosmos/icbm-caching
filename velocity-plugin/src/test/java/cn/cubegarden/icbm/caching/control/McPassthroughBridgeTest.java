package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.control.auth.AuthGatewayListener;
import cn.cubegarden.icbm.caching.control.auth.IdentitySigner;
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
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 权威会话桥（里程碑 5 v1）：slave 明文 MC 报文（MC_PASSTHROUGH 流）→ master →
 * 源服 backend 双向字节透传 + 数据包缓存（PacketLog）；源服不健康/断路器 OPEN →
 * 拒绝（后端故障 fallback，slave 继续缓存会话）。
 */
class McPassthroughBridgeTest {

    private static final String PSK = "bridge-test-psk";
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
    private TestBackendServer backend;
    private SourceConnector sourceConnector;
    private McPassthroughBridge bridge;
    private BackendHealth backendHealth;
    private CircuitBreaker circuitBreaker;
    private PacketLog packetLog;

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
        SessionRegistry sessionRegistry = new SessionRegistry();
        this.service.setSessionRegistry(sessionRegistry);
        TicketVault ticketVault = new TicketVault();
        this.service.setTicketVault(ticketVault);
        IdentitySigner signer = IdentitySigner.fromKey("bridge-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        this.backendHealth = new BackendHealth();
        this.circuitBreaker = new CircuitBreaker(3, 30_000);
        RoutingPolicy routing = new RoutingPolicy(List.of(), this.backendHealth, this.circuitBreaker, 90_000);
        this.packetLog = new PacketLog(64);
        this.bridge = new McPassthroughBridge(routing, this.circuitBreaker, this.packetLog);
        AuthGatewayListener authGateway = new AuthGatewayListener(sessionRegistry, ticketVault,
                new cn.cubegarden.icbm.caching.control.auth.LoginHandshake(false, false),
                signer, routing, "master-1", 60_000);
        this.encapsulated = new EncapsulatedControlServer(this.config, this.service, this.replicator,
                this.registry, new CompositeSessionListener(authGateway, this.bridge));
        this.encapsulated.start();

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

    private EncapsulatedChannelClient newSlave(CopyOnWriteArrayList<byte[]> received, CountDownLatch latch)
            throws Exception {
        return new EncapsulatedChannelClient("127.0.0.1",
                this.encapsulated.port(), "lobby-1", SLAVE_UUID, PSK,
                new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, "lobby-1", "26.3", PROTOCOL,
                        "0.19.3", "0.157.1+26.3", "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0),
                5000, 100, false, new EncapsulatedChannelClient.Listener() {
                    @Override
                    public void onMcPacket(byte[] body) {
                        received.add(body);
                        latch.countDown();
                    }
                });
    }

    /** MC_PASSTHROUGH body：头 8 字节流序号 + 4 字节长度 + 明文报文。 */
    /** body v2 = [playerUuid(16)][流序号(8)][长度(4)][报文]（CACHE_PROTOCOL §10 v2）。 */
    private static byte[] mcBody(long streamSequence, byte[] packet) {
        byte[] body = new byte[28 + packet.length];
        java.util.UUID playerUuid = java.util.UUID.nameUUIDFromBytes(
                "rcon-player".getBytes(StandardCharsets.UTF_8));
        java.nio.ByteBuffer.wrap(body)
                .putLong(0, playerUuid.getMostSignificantBits())
                .putLong(8, playerUuid.getLeastSignificantBits())
                .putLong(16, streamSequence)
                .putInt(24, packet.length);
        System.arraycopy(packet, 0, body, 28, packet.length);
        return body;
    }

    @Test
    void slavePacketForwardedToBackendAndLogged() throws Exception {
        CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave(received, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            byte[] movePacket = "move-packet-bytes".getBytes(StandardCharsets.UTF_8);
            assertTrue(slave.sendMcPassthrough(mcBody(1, movePacket)), "slave 报文必须发出");

            long deadline = System.currentTimeMillis() + 5_000;
            while (this.backend.receivedMcPackets.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, this.backend.receivedMcPackets.size(), "源服必须收到转发的 slave 报文");
            assertTrue(new String(this.backend.receivedMcPackets.get(0), StandardCharsets.UTF_8)
                    .contains("move-packet-bytes"));
            assertTrue(this.packetLog.size() >= 1, "数据包缓存必须记录转发");
            assertEquals("SLAVE_TO_BACKEND", this.packetLog.snapshot().get(0).direction());
        }
    }

    @Test
    void backendPacketForwardedToSlaveAndLogged() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave(received, latch)) {
            slave.start();
            Thread.sleep(300);
            // 桥由 slave 侧建立（单一活跃权威会话）：先发一个报文登记 activeSlaveSession
            slave.sendMcPassthrough(mcBody(1, "move".getBytes(StandardCharsets.UTF_8)));
            long deadline = System.currentTimeMillis() + 5_000;
            while (this.backend.receivedMcPackets.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            byte[] chunkPacket = "chunk-data-packet-bytes".getBytes(StandardCharsets.UTF_8);
            this.backend.sendMcPassthrough(mcBody(7, chunkPacket));
            assertTrue(latch.await(5, TimeUnit.SECONDS), "slave 必须收到源服下行包");
            assertEquals(1, received.size());
            assertTrue(new String(received.get(0), StandardCharsets.UTF_8).contains("chunk-data-packet-bytes"));
            boolean hasBackendToSlave = this.packetLog.snapshot().stream()
                    .anyMatch(e -> e.direction().equals("BACKEND_TO_SLAVE"));
            assertTrue(hasBackendToSlave, "数据包缓存必须记录 BACKEND_TO_SLAVE 方向: "
                    + this.packetLog.snapshot());
        }
    }

    @Test
    void backendDownFallsBackToCacheSession() throws Exception {
        // 源服未连接（health 未 markConnected）→ 权威桥拒绝转发（slave 继续缓存会话）
        CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave(received, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            this.backendHealth.markDisconnected();
            assertTrue(slave.sendMcPassthrough(mcBody(2, "move".getBytes(StandardCharsets.UTF_8))));
            Thread.sleep(300);
            assertTrue(this.backend.receivedMcPackets.isEmpty(), "源服故障 → 不转发（fallback）");
            assertEquals(0, this.packetLog.size(), "被拒报文不入缓存");
        }
    }

    @Test
    void circuitBreakerOpenRejectsBridge() throws Exception {
        CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
        try (EncapsulatedChannelClient slave = this.newSlave(received, new CountDownLatch(1))) {
            slave.start();
            Thread.sleep(300);
            this.circuitBreaker.recordFailure(System.currentTimeMillis());
            this.circuitBreaker.recordFailure(System.currentTimeMillis());
            this.circuitBreaker.recordFailure(System.currentTimeMillis());
            assertTrue(slave.sendMcPassthrough(mcBody(3, "move".getBytes(StandardCharsets.UTF_8))));
            Thread.sleep(300);
            assertTrue(this.backend.receivedMcPackets.isEmpty(), "断路器 OPEN → 拒绝权威会话");
            assertFalse(this.circuitBreaker.allow(System.currentTimeMillis()));
        }
    }
}

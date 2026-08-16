package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlServer;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.net.SourceConnector;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 乐观操作路由集成测试（里程碑 7 阶段 5）：master 纯转发（不裁决），
 * slave → master → 源服（TestBackendServer）→ 终态 → master → 发起 slave。
 * 覆盖：提交转发、COMMITTED 回发、REJECTED 回发、GET_OPERATION_STATUS 查询回发、
 * 源服未连接时 fail closed（拒绝 + resync 范围）。
 */
class OperationRoutingTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final String SOURCE_ID = "source-server";
    private static final String SLAVE_A = "slave-a";
    private static final String FINGERPRINT = "modpack-hash";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";
    private static final String PSK = "test-psk-1234567890";

    @TempDir
    Path tempDir;

    private ServiceConfig config;
    private SnapshotObjectStore store;
    private EventLog eventLog;
    private PeerRegistry registry;
    private ControlService service;
    private Replicator replicator;
    private EncapsulatedControlServer server;
    private SourceConnector sourceConnector;
    private TestBackendServer backend;

    @BeforeEach
    void setUp() throws Exception {
        this.backend = new TestBackendServer(TestBackendServer.SECRET, TestBackendServer.SECRET);
        this.config = new ServiceConfig(
                "127.0.0.1",
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1", "lobby-1", PSK, 0,
                roleBindings("source-server", "SOURCE", "slave-a", "SLAVE", "slave-b", "SLAVE"),
                new ServiceConfig.BackendServer("127.0.0.1", this.backend.port(), TestBackendServer.SECRET),
                16 * 1024 * 1024, 8, 20_000, 40_000,
                1_073_742_154
        );
        this.store = new SnapshotObjectStore(this.config.storeRoot(), this.config.serverName());
        this.store.recover();
        this.eventLog = new EventLog(this.config.eventLogPath(), this.config.maxEventLogEntries());
        this.eventLog.recover();
        this.registry = new PeerRegistry(this.config.roleBindings());
        this.replicator = new Replicator(this.store, this.eventLog, this.config);
        this.service = new ControlService(this.store, this.eventLog, this.replicator, this.registry, this.config);
        this.server = new EncapsulatedControlServer(this.config, this.service, this.replicator, this.registry,
                new EncapsulatedControlSession.Listener() {
                });
        this.server.start();
        this.sourceConnector = new SourceConnector(this.config, this.service, this.replicator, this.registry, new cn.cubegarden.icbm.caching.control.routing.BackendHealth());
        this.service.setSourceConnector(this.sourceConnector);
        this.sourceConnector.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        this.sourceConnector.close();
        this.server.close();
        this.backend.close();
    }

    private int serverPort() {
        return this.server.port();
    }

    private static String uuidOf(String id) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)), 0, 16);
    }

    private static Map<String, String> roleBindings(String... idRolePairs) throws Exception {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (int i = 0; i < idRolePairs.length; i += 2) {
            bindings.put(uuidOf(idRolePairs[i]), idRolePairs[i + 1]);
        }
        return bindings;
    }

    private static byte[] uuidBytes(String id) {
        UUID uuid = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new byte[16];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return bytes;
    }

    private static Messages.SubmitOperation op(String operationId) {
        return new Messages.SubmitOperation(
                uuidBytes(operationId), uuidBytes("player"), "sess-1", 1,
                Messages.OperationType.PLACE, DIM, 10, 64, 20, 5,
                "sha256-air", "minecraft:diamond_block", System.currentTimeMillis());
    }

    private static final class WireMessage {
        final MessageType type;
        final Envelope envelope;
        final byte[] body;

        WireMessage(MessageType type, Envelope envelope, byte[] body) {
            this.type = type;
            this.envelope = envelope;
            this.body = body;
        }
    }

    /** 假 slave：封装通道连接 master（与 ControlServiceIntegrationTest.TestClient 同款）。 */
    private static final class TestSlave implements AutoCloseable {
        final EncapsulatedChannelClient channel;
        final AtomicLong requestIds = new AtomicLong();
        final List<WireMessage> received = new ArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);

        TestSlave(String id, int port) throws Exception {
            this.channel = new EncapsulatedChannelClient("127.0.0.1", port, id, uuidOf(id), PSK,
                    new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, id, "26.3", 1_073_742_154,
                            "0.19.3", "0.157.1+26.3", FINGERPRINT, REGISTRY_FINGERPRINT, (byte) 0),
                    5000, 100, false, new EncapsulatedChannelClient.Listener() {
                        @Override
                        public void onConnected(String serverId) {
                            connected.countDown();
                        }

                        @Override
                        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
                            received.add(new WireMessage(type, envelope, body));
                        }
                    });
        }

        void start() {
            this.channel.start();
        }

        void awaitConnected() throws Exception {
            assertTrue(this.connected.await(5, TimeUnit.SECONDS), "slave 未连上 master");
        }

        void send(MessageType type, Object body) {
            this.channel.send(type, this.requestIds.incrementAndGet(), 0,
                    EncapsulatedChannelClient.encode(type, body));
        }

        @Override
        public void close() {
            this.channel.close();
        }
    }

    @Test
    void submitRoutedToSourceAndCommittedBackToSlave() throws Exception {
        try (TestSlave slave = new TestSlave(SLAVE_A, serverPort())) {
            slave.start();
            slave.awaitConnected();
            Messages.SubmitOperation op = op("op-commit-1");
            slave.send(MessageType.SUBMIT_OPERATION, op);
            // 源服收到并回 COMMITTED
            assertTrue(this.backend.operationReceived.await(5, TimeUnit.SECONDS), "源服未收到操作");
            assertEquals(1, this.backend.receivedOperations.size());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    uuidBytes("op-commit-1"), this.backend.receivedOperations.get(0).operationId());
            // slave 收到 COMMITTED（终态回发）
            WireMessage terminal = awaitMessage(slave, MessageType.OPERATION_COMMITTED);
            Messages.OperationCommitted committed = EncapsulatedChannelClient.decode(
                    MessageType.OPERATION_COMMITTED, terminal.body);
            assertEquals("minecraft:diamond_block", committed.canonicalState());
            assertEquals(7, committed.chunkRevision());
        }
    }

    @Test
    void rejectedBackToSlave() throws Exception {
        try (TestSlave slave = new TestSlave(SLAVE_A, serverPort())) {
            slave.start();
            slave.awaitConnected();
            this.backend.rejectedReply = new Messages.OperationRejected(
                    new byte[16], "precondition-mismatch", DIM,
                    new int[] {0}, new int[] {1}, System.currentTimeMillis());
            Messages.SubmitOperation op = op("op-reject-1");
            slave.send(MessageType.SUBMIT_OPERATION, op);
            assertTrue(this.backend.operationReceived.await(5, TimeUnit.SECONDS));
            WireMessage terminal = awaitMessage(slave, MessageType.OPERATION_REJECTED);
            Messages.OperationRejected rejected = EncapsulatedChannelClient.decode(
                    MessageType.OPERATION_REJECTED, terminal.body);
            assertEquals("precondition-mismatch", rejected.reason());
            assertEquals(0, rejected.resyncChunkX()[0]);
            assertEquals(1, rejected.resyncChunkZ()[0]);
        }
    }

    @Test
    void statusQueryRoutedToSourceAndBack() throws Exception {
        try (TestSlave slave = new TestSlave(SLAVE_A, serverPort())) {
            slave.start();
            slave.awaitConnected();
            slave.send(MessageType.SUBMIT_OPERATION, op("op-status-1"));
            assertTrue(this.backend.operationReceived.await(5, TimeUnit.SECONDS));
            slave.send(MessageType.GET_OPERATION_STATUS, new Messages.GetOperationStatus(uuidBytes("op-status-1")));
            WireMessage res = awaitMessage(slave, MessageType.OPERATION_STATUS_RES);
            Messages.OperationStatusRes statusRes = EncapsulatedChannelClient.decode(
                    MessageType.OPERATION_STATUS_RES, res.body);
            assertEquals(Messages.OperationStatusRes.OperationStatus.COMMITTED, statusRes.status());
        }
    }

    @Test
    void sourceDownFailsClosedWithRejected() throws Exception {
        try (TestSlave slave = new TestSlave(SLAVE_A, serverPort())) {
            slave.start();
            slave.awaitConnected();
            // 源服掉线（源已注册，slave 连接不受影响；操作无法裁决 → fail closed 拒绝）
            this.sourceConnector.close();
            Thread.sleep(200);
            slave.send(MessageType.SUBMIT_OPERATION, op("op-nosource-1"));
            WireMessage terminal = awaitMessage(slave, MessageType.OPERATION_REJECTED);
            Messages.OperationRejected rejected = EncapsulatedChannelClient.decode(
                    MessageType.OPERATION_REJECTED, terminal.body);
            assertEquals("source-unavailable", rejected.reason());
            assertEquals(0, rejected.resyncChunkX()[0], "resync 范围 = 目标 chunk (10,20) → (0,1)");
            assertEquals(1, rejected.resyncChunkZ()[0]);
        }
    }

    private static WireMessage awaitMessage(TestSlave slave, MessageType type) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (WireMessage message : slave.received) {
                if (message.type == type) {
                    return message;
                }
            }
            Thread.sleep(50);
        }
        StringBuilder dump = new StringBuilder();
        for (WireMessage message : slave.received) {
            dump.append(message.type).append(":");
            if (message.type == MessageType.ERROR) {
                dump.append(EncapsulatedChannelClient.<Messages.ErrorMessage>decode(
                        MessageType.ERROR, message.body).reason());
            }
            dump.append(" ");
        }
        throw new AssertionError("slave 未收到 " + type + "（收到: " + dump + "）");
    }
}

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
import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 8.2 重放缺陷回归：master 重放 SNAPSHOT_COMMITTED 事件必须按事件 revision 取 payload
 * （Replicator.writeEvent 修复）。场景：源服对同一 chunk 提交 rev=1、rev=2 两个快照，
 * slave 断线后重连订阅（ackedSequence=0）→ 重放两个事件 → 两个 SNAPSHOT_PUSH 的
 * payloadHash 必须与各自 payload 匹配（旧实现用 loadCommittedPayload 取最新 payload 配
 * 旧事件 hash → slave REJECTED_HASH_MISMATCH 风暴）。断言 slave 侧对象库应用后
 * committed 水位与 master 一致。
 */
class ReplicatorReplayTest {

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
        this.sourceConnector = new SourceConnector(this.config, this.service, this.replicator, this.registry);
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

    private static byte[] gzip(String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String sha(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private static int decompressedLength(byte[] gzipPayload) throws Exception {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(gzipPayload))) {
            return in.readAllBytes().length;
        }
    }

    private static final class WireMessage {
        final MessageType type;
        final Messages.SnapshotPush push;

        WireMessage(MessageType type, Messages.SnapshotPush push) {
            this.type = type;
            this.push = push;
        }
    }

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
                            if (type == MessageType.SNAPSHOT_PUSH) {
                                received.add(new WireMessage(type,
                                        EncapsulatedChannelClient.decode(MessageType.SNAPSHOT_PUSH, body)));
                            }
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
    void replayPushesExactRevisionPayloadAndWatermarkMatches() throws Exception {
        // 等源服握手完成，先声明 epoch 1
        assertTrue(this.backend.helloAcked.await(5, TimeUnit.SECONDS), "源服未完成握手");
        this.backend.sendWorldEpoch(1);
        Thread.sleep(200);

        // 源服对同一 chunk 提交两个 revision（内容不同）
        ChunkRef key = ChunkRef.of(DIM, 3, -1);
        byte[] payload1 = gzip("data-rev-1");
        byte[] payload2 = gzip("data-rev-2");
        this.backend.sendInvalidate(1);
        this.backend.sendPut(1, payload1);
        assertTrue(this.backend.putAckReceived.await(5, TimeUnit.SECONDS),
                "master 未回 PUT_SNAPSHOT_ACK（events=" + this.backend.events + "）");
        assertTrue(this.backend.events.stream().noneMatch(e -> e.startsWith("put_ack:REJECTED")),
                "PUT 被拒: " + this.backend.events);
        this.backend.sendInvalidate(2);
        this.backend.sendPut(2, payload2);
        Thread.sleep(300);
        assertTrue(this.backend.events.stream().noneMatch(e -> e.startsWith("put_ack:REJECTED")),
                "PUT 被拒: " + this.backend.events);
        // master 对象库水位
        long masterCommitted = this.store.indexOf(key).committed();
        assertEquals(2, masterCommitted, "master committed 水位");

        // slave 连接订阅（断线重连场景：ackedSequence=0 → 重放全部事件）
        try (TestSlave slave = new TestSlave(SLAVE_A, serverPort())) {
            slave.start();
            slave.awaitConnected();
            slave.send(MessageType.SUBSCRIBE,
                    new Messages.Subscribe(new DimensionRef[] {DIM}, true, 0));

            // 等待两个 SNAPSHOT_PUSH 到达
            long deadline = System.currentTimeMillis() + 5000;
            while (slave.received.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, slave.received.size(), "重放应推送两个 SNAPSHOT_PUSH（rev1+rev2）");

            // 缺陷回归：每个 push 的 payloadHash 必须与其 payload 匹配（旧实现旧 hash 配新 payload）
            Messages.PutSnapshot s1 = slave.received.get(0).push.snapshot();
            Messages.PutSnapshot s2 = slave.received.get(1).push.snapshot();
            assertEquals(1, s1.revision());
            assertEquals(2, s2.revision());
            assertEquals(sha(s1.payload()), s1.payloadHash(), "rev1 事件 hash 必须匹配 rev1 payload");
            assertEquals(sha(s2.payload()), s2.payloadHash(), "rev2 事件 hash 必须匹配 rev2 payload");
            assertEquals(decompressedLength(payload1), s1.uncompressedLength());
            assertEquals(decompressedLength(payload2), s2.uncompressedLength());

            // slave 侧对象库应用两个 push → 水位与 master 一致
            Path slaveRoot = this.tempDir.resolve("slave-objects");
            SnapshotObjectStore slaveStore = new SnapshotObjectStore(slaveRoot, "lobby-1");
            slaveStore.recover();
            slaveStore.announceEpoch(DIM, 1);
            slaveStore.raiseRequired(key, 1);
            assertEquals(PutResult.OK, slaveStore.put(key, 1, 1,
                    s1.snapshotFormat(), s1.compression(), s1.uncompressedLength(), s1.payloadHash(), s1.payload()));
            slaveStore.commit(key, 1);
            slaveStore.raiseRequired(key, 2);
            assertEquals(PutResult.OK, slaveStore.put(key, 2, 1,
                    s2.snapshotFormat(), s2.compression(), s2.uncompressedLength(), s2.payloadHash(), s2.payload()));
            slaveStore.commit(key, 2);
            assertEquals(2, slaveStore.indexOf(key).committed(), "slave committed 水位必须与 master 一致");
            // 重放是"当前最新 committed"时会 REJECTED_HASH_MISMATCH —— 上面两次 put 均 OK 即回归通过
        }
    }
}

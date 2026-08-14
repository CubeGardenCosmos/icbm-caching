package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.ControlServer;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.InvalidateReason;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.ResolveStatus;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.ControlChannel;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 控制服务集成测试（真实 netty mTLS + 真实 wire 协议）。
 *
 * <p>验收映射：
 * <ul>
 *   <li>INVALIDATE 到 committed 之间 RESOLVE_CHUNK 不返回旧 HIT；</li>
 *   <li>乱序/重放/损坏快照不降低 revision、不覆盖新对象；</li>
 *   <li>控制服务不可用 → RESOLVE 返回 UNAVAILABLE（fail closed）；</li>
 *   <li>slave 订阅/窗口 ACK/滞后检测/事件日志重放（断线补发）。</li>
 * </ul>
 */
class ControlServiceIntegrationTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final String SOURCE_ID = "source-server";
    private static final String SLAVE_A = "slave-a";
    private static final String FINGERPRINT = "modpack-hash";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";

    @TempDir
    Path tempDir;

    private Path keyStore;
    private Path trustStore;
    private ServiceConfig config;
    private SnapshotObjectStore store;
    private EventLog eventLog;
    private PeerRegistry registry;
    private ControlService service;
    private Replicator replicator;
    private ControlServer server;

    @BeforeEach
    void setUp() throws Exception {
        this.keyStore = copyFixture("tls/master-control.p12");
        this.trustStore = copyFixture("tls/ca-trust.p12");
        this.config = new ServiceConfig(
                "127.0.0.1", 0, // 端口 0：随机
                this.keyStore, "changeit", this.trustStore, "changeit", true,
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1",
                Map.of("source-server", "SOURCE", "slave-a", "VELOCITY", "slave-b", "VELOCITY"),
                16 * 1024 * 1024, 8, 20_000, 40_000,
                1073742154
        );
        this.store = new SnapshotObjectStore(this.config.storeRoot());
        this.store.recover();
        this.eventLog = new EventLog(this.config.eventLogPath(), this.config.maxEventLogEntries());
        this.eventLog.recover();
        this.registry = new PeerRegistry(this.config.roleBindings());
        this.replicator = new Replicator(this.store, this.eventLog, this.config);
        this.service = new ControlService(this.store, this.eventLog, this.replicator, this.registry, this.config);
        this.server = new ControlServer(this.config, this.service, this.replicator, this.registry);
        this.server.start();
    }

    @AfterEach
    void tearDown() {
        this.server.stop();
    }

    private Path copyFixture(String name) throws Exception {
        // 每次复制到独立文件（多次调用同一 fixture 时不冲突）
        String suffix = name.substring(name.lastIndexOf('.') + 1);
        Path target = Files.createTempFile(this.tempDir, "tls-fixture-", "." + suffix);
        try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private int serverPort() {
        return this.server.port();
    }

    // ---- 测试客户端 ----

    /** 同步请求/响应客户端（requestId → 响应队列）。 */
    private static final class TestClient implements AutoCloseable {
        final ControlChannel channel;
        final AtomicLong requestIds = new AtomicLong();
        final Map<Long, BlockingQueue<Object>> pending = new ConcurrentHashMap<>();
        final List<WireMessage> received = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch handshakeDone = new CountDownLatch(1);

        TestClient(String id, Role role, String fingerprint, String registryFingerprint,
                   int port, SSLContext ssl) {
            this(id, role, fingerprint, registryFingerprint, port, ssl, 1073742154, "26.3");
        }

        TestClient(String id, Role role, String fingerprint, String registryFingerprint,
                   int port, SSLContext ssl, int runtimeProtocol) {
            this(id, role, fingerprint, registryFingerprint, port, ssl, runtimeProtocol, "26.3");
        }

        TestClient(String id, Role role, String fingerprint, String registryFingerprint,
                   int port, SSLContext ssl, int runtimeProtocol, String minecraftVersion) {
            this.channel = new ControlChannel("127.0.0.1", port, id,
                    new ControlChannel.HelloConfig(role, minecraftVersion, runtimeProtocol, "0.19.3",
                            "0.157.1+26.3", fingerprint, registryFingerprint, (byte) 0),
                    ssl, 5000, 100, false, new ControlChannel.Listener() {
                        @Override
                        public void onConnected(String serverId) {
                            events.add("connected:" + serverId);
                            connected.countDown();
                            handshakeDone.countDown();
                        }

                        @Override
                        public void onRejected(String reason) {
                            events.add("rejected:" + reason);
                            handshakeDone.countDown();
                        }

                        @Override
                        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
                            WireMessage message = new WireMessage(type, envelope, body);
                            received.add(message);
                            BlockingQueue<Object> queue = pending.remove(envelope.requestId());
                            if (queue != null) {
                                queue.add(message);
                            }
                        }
                    });
            this.channel.start();
        }

        void awaitConnected() throws Exception {
            assertTrue(this.connected.await(5, TimeUnit.SECONDS), "HELLO 握手失败: " + this.events);
        }

        long nextRequestId() {
            return this.requestIds.incrementAndGet();
        }

        void send(MessageType type, long requestId, Object body) {
            assertTrue(this.channel.send(type, requestId, 0, ControlChannel.encode(type, body)),
                    "发送失败（未连接）");
        }

        /** 发送并等待响应（按 requestId 关联）。 */
        WireMessage call(MessageType type, Object body) throws Exception {
            long requestId = this.nextRequestId();
            BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
            this.pending.put(requestId, queue);
            this.send(type, requestId, body);
            Object result = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(result, "等待响应超时: " + type);
            return (WireMessage) result;
        }

        @Override
        public void close() {
            this.channel.close();
        }
    }

    private record WireMessage(MessageType type, Envelope envelope, byte[] body) {
        <T> T decodeBody() {
            return ControlChannel.decode(this.type, this.body);
        }
    }

    private SSLContext sourceSsl() throws Exception {
        return clientSsl("tls/source-server.p12");
    }

    private SSLContext slaveSsl() throws Exception {
        return clientSsl("tls/slave-a.p12");
    }

    private SSLContext clientSsl(String keystore) throws Exception {
        Path ks = copyFixture(keystore);
        return cn.cubegarden.icbm.caching.control.tls.TlsContextFactory.clientContext(
                ks, "changeit", this.trustStore, "changeit");
    }

    private static byte[] gzipPayload(String content) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String sha(byte[] payload) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private static Messages.PutSnapshot snapshot(long revision, byte[] gzipPayload) throws Exception {
        return new Messages.PutSnapshot(DIM, 3, -1, revision,
                ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1, ProtocolConstants.COMPRESSION_GZIP,
                decompressedLength(gzipPayload), sha(gzipPayload), gzipPayload);
    }

    private static int decompressedLength(byte[] gzipPayload) throws Exception {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(gzipPayload))) {
            return in.readAllBytes().length;
        }
    }

    /** 轮询等待 store 达到期望 required（wire 处理异步）。 */
    private void awaitStoreRequired(ChunkRef key, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
            if (index != null && index.required() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
        assertNotNull(index, "INVALIDATE 未到达对象库");
        assertEquals(expected, index.required());
    }

    private Messages.ResolveChunkRes resolve(TestClient client) throws Exception {
        WireMessage res = client.call(MessageType.RESOLVE_CHUNK_REQ,
                new Messages.ResolveChunkReq(DIM, 3, -1, 1073742154, REGISTRY_FINGERPRINT, 0));
        return res.decodeBody();
    }

    // ---- 测试 ----

    @Test
    void invalidateToCommitWindowNeverReturnsOldHit() throws Exception {
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl());
             TestClient slave = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                     serverPort(), slaveSsl())) {
            source.awaitConnected();
            slave.awaitConnected();

            // 初始 MISS
            assertEquals(ResolveStatus.MISS, resolve(slave).status());

            // INVALIDATE rev5 → 从未提交过 → MISS（不得返回旧 HIT）；窗口语义在有过 committed 后体现
            source.send(MessageType.INVALIDATE, source.nextRequestId(),
                    new Messages.Invalidate(DIM, 3, -1, 5, InvalidateReason.BLOCK_STATE));
            assertEquals(ResolveStatus.MISS, resolve(slave).status());

            // PUT rev5 成功 → HIT
            WireMessage ack = source.call(MessageType.PUT_SNAPSHOT, snapshot(5, gzipPayload("data-5")));
            assertEquals(PutResult.OK, ((Messages.PutSnapshotAck) ack.decodeBody()).result());
            assertEquals(ResolveStatus.HIT, resolve(slave).status());

            // INVALIDATE rev6 → committed(5) < required(6) → STALE（窗口语义）
            source.send(MessageType.INVALIDATE, source.nextRequestId(),
                    new Messages.Invalidate(DIM, 3, -1, 6, InvalidateReason.BLOCK_STATE));
            this.awaitStoreRequired(ChunkRef.of(DIM, 3, -1), 6); // 同步：INVALIDATE 已生效
            assertEquals(ResolveStatus.STALE, resolve(slave).status());
            assertEquals(ResolveStatus.STALE, resolveWithMinRevision(slave, 7).status());
        }
    }

    private Messages.ResolveChunkRes resolveWithMinRevision(TestClient client, long minRevision) throws Exception {
        WireMessage res = client.call(MessageType.RESOLVE_CHUNK_REQ,
                new Messages.ResolveChunkReq(DIM, 3, -1, 1073742154, REGISTRY_FINGERPRINT, minRevision));
        return res.decodeBody();
    }

    @Test
    void corruptedSnapshotRejectedAndRequiredStaysRaised() throws Exception {
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl());
             TestClient slave = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                     serverPort(), slaveSsl())) {
            source.awaitConnected();
            slave.awaitConnected();

            source.send(MessageType.INVALIDATE, source.nextRequestId(),
                    new Messages.Invalidate(DIM, 3, -1, 5, InvalidateReason.BLOCK_STATE));

            // 损坏哈希：拒收，required 保持 → 仍非 HIT（fail closed）
            byte[] payload = gzipPayload("data-5");
            Messages.PutSnapshot tampered = new Messages.PutSnapshot(DIM, 3, -1, 5,
                    ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1, ProtocolConstants.COMPRESSION_GZIP,
                    decompressedLength(payload), "deadbeef", payload);
            WireMessage ack = source.call(MessageType.PUT_SNAPSHOT, tampered);
            assertEquals(PutResult.REJECTED_HASH_MISMATCH, ((Messages.PutSnapshotAck) ack.decodeBody()).result());
            assertEquals(ResolveStatus.MISS, resolve(slave).status(), "损坏快照不得产生可读对象");

            // 有效重发 → HIT
            WireMessage ok = source.call(MessageType.PUT_SNAPSHOT, snapshot(5, payload));
            assertEquals(PutResult.OK, ((Messages.PutSnapshotAck) ok.decodeBody()).result());
            assertEquals(ResolveStatus.HIT, resolve(slave).status());

            // 同 revision 重放不同内容（模拟重放）：required 已前进 → 拒收且不覆盖
            source.send(MessageType.INVALIDATE, source.nextRequestId(),
                    new Messages.Invalidate(DIM, 3, -1, 6, InvalidateReason.BLOCK_STATE));
            WireMessage ack2 = source.call(MessageType.PUT_SNAPSHOT, snapshot(6, gzipPayload("newer")));
            assertEquals(PutResult.OK, ((Messages.PutSnapshotAck) ack2.decodeBody()).result());
            WireMessage replay = source.call(MessageType.PUT_SNAPSHOT, snapshot(5, gzipPayload("evil")));
            assertEquals(PutResult.REJECTED_OUT_OF_ORDER, ((Messages.PutSnapshotAck) replay.decodeBody()).result());
            assertEquals(ResolveStatus.HIT, resolveWithMinRevision(slave, 6).status());
            assertEquals(6, resolveWithMinRevision(slave, 6).committedRevision());
        }
    }

    @Test
    void outOfOrderAndReplayedSequenceClosed() throws Exception {
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl())) {
            source.awaitConnected();
            // 非法：revision != required → 拒收（乱序快照）
            WireMessage ack = source.call(MessageType.PUT_SNAPSHOT, snapshot(9, gzipPayload("x")));
            assertEquals(PutResult.REJECTED_OUT_OF_ORDER, ((Messages.PutSnapshotAck) ack.decodeBody()).result());

            // 序号重放：同 requestId 重发 INVALIDATE（envelope seq 递增，但消息语义重放）
            // 由对象库单调性兜底（required 仅增）——发两次相同 INVALIDATE 无副作用
            long requestId = source.nextRequestId();
            source.send(MessageType.INVALIDATE, requestId,
                    new Messages.Invalidate(DIM, 3, -1, 5, InvalidateReason.BLOCK_STATE));
            source.send(MessageType.INVALIDATE, requestId,
                    new Messages.Invalidate(DIM, 3, -1, 5, InvalidateReason.BLOCK_STATE));
            this.awaitStoreRequired(ChunkRef.of(DIM, 3, -1), 5);
            assertEquals(5, this.store.indexOf(ChunkRef.of(DIM, 3, -1)).required());
        }
    }

    @Test
    void rolePermissionViolationClosesChannel() throws Exception {
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl())) {
            source.awaitConnected();
            // SOURCE 无权 SUBSCRIBE → ERROR + 断连
            source.send(MessageType.SUBSCRIBE, source.nextRequestId(),
                    new Messages.Subscribe(new DimensionRef[] {DIM}, true, 0));
            Thread.sleep(300);
            assertTrue(!source.channel.isConnected() || source.events.contains("rejected"),
                    "角色违规必须断连");
        }
    }

    @Test
    void slaveCannotSendSourceOnlyMessages() throws Exception {
        // 多个 slave（slave-a/slave-b 独立证书）都能连接受信 master，但不能冒充 SOURCE
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl());
             TestClient slaveA = new TestClient("slave-a", Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                     serverPort(), clientSsl("tls/slave-a.p12"));
             TestClient slaveB = new TestClient("slave-b", Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                     serverPort(), clientSsl("tls/slave-b.p12"))) {
            source.awaitConnected();
            slaveA.awaitConnected();
            slaveB.awaitConnected();

            // slave 发送 SOURCE 独占的 INVALIDATE → 协议违规 → 断连
            slaveA.send(MessageType.INVALIDATE, slaveA.nextRequestId(),
                    new Messages.Invalidate(DIM, 3, -1, 5, InvalidateReason.BLOCK_STATE));
            Thread.sleep(300);
            assertTrue(!slaveA.channel.isConnected(), "slave 不得以 SOURCE 身份发送写入消息");
            assertTrue(slaveB.channel.isConnected(), "其他 slave 不受影响");
            // slave-b 不能把 slave-a 作为身份来源：HELLO 后仅凭证书 CN 绑定角色，无法冒充
        }
    }

    @Test
    void fingerprintMismatchSlaveRejected() throws Exception {
        try (TestClient slave = new TestClient(SLAVE_A, Role.VELOCITY, "different-modpack",
                REGISTRY_FINGERPRINT, serverPort(), slaveSsl())) {
            assertTrue(slave.handshakeDone.await(5, TimeUnit.SECONDS), "握手必须被拒绝");
            assertTrue(slave.events.stream().anyMatch(e -> e.startsWith("rejected:")),
                    "fingerprint 不一致的 slave 必须被拒: " + slave.events);
        }
    }

    @Test
    void wrongRuntimeProtocolRejected() throws Exception {
        try (TestClient bad = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), slaveSsl(), 999)) {
            assertTrue(bad.handshakeDone.await(5, TimeUnit.SECONDS), "握手必须被拒绝");
            assertTrue(bad.events.stream().anyMatch(e -> e.startsWith("rejected:")),
                    "runtimeProtocol 不匹配必须被拒: " + bad.events);
        }
    }

    @Test
    void minecraftVersionFamilyAcceptedAndOtherRejected() throws Exception {
        // 源模组与虚拟后端 HELLO 携带完整版本名（如 "26.3 Snapshot 8"），
        // 26.3 系列（由 runtimeProtocol 精确锁定）必须被接受；其它系列必须被拒
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl(), 1073742154, "26.3 Snapshot 8")) {
            source.awaitConnected();
        }
        try (TestClient bad = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), slaveSsl(), 1073742154, "26.2 Snapshot 3")) {
            assertTrue(bad.handshakeDone.await(5, TimeUnit.SECONDS), "握手必须被拒绝");
            assertTrue(bad.events.stream().anyMatch(e -> e.startsWith("rejected:")),
                    "非 26.3 系列必须被拒: " + bad.events);
        }
    }

    @Test
    void slaveReceivesPushesAndReconnectReplays() throws Exception {
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl())) {
            source.awaitConnected();

            // 第一个 slave 订阅并接收推送
            try (TestClient slave1 = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                    serverPort(), slaveSsl())) {
                slave1.awaitConnected();
                slave1.send(MessageType.SUBSCRIBE, slave1.nextRequestId(),
                        new Messages.Subscribe(new DimensionRef[] {DIM}, true, 0));

                source.send(MessageType.INVALIDATE, source.nextRequestId(),
                        new Messages.Invalidate(DIM, 3, -1, 5, InvalidateReason.BLOCK_STATE));
                WireMessage ack = source.call(MessageType.PUT_SNAPSHOT, snapshot(5, gzipPayload("data-5")));
                assertEquals(PutResult.OK, ((Messages.PutSnapshotAck) ack.decodeBody()).result());

                Thread.sleep(400);
                assertTrue(slave1.received.stream().anyMatch(m -> m.type() == MessageType.INVALIDATE),
                        "slave 必须收到 INVALIDATE 推送");
                assertTrue(slave1.received.stream().anyMatch(m -> m.type() == MessageType.SNAPSHOT_PUSH),
                        "slave 必须收到 SNAPSHOT_PUSH 推送");
                // 推送 payload 与源一致
                Messages.SnapshotPush push = slave1.received.stream()
                        .filter(m -> m.type() == MessageType.SNAPSHOT_PUSH)
                        .map(m -> (Messages.SnapshotPush) m.decodeBody())
                        .findFirst().orElse(null);
                assertNotNull(push);
                assertEquals("data-5", new String(new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(push.snapshot().payload())).readAllBytes()));
            }

            // 断线重连的 slave：ackedSequence=0 → 重放全部事件（补发）
            try (TestClient slave2 = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                    serverPort(), slaveSsl())) {
                slave2.awaitConnected();
                slave2.send(MessageType.SUBSCRIBE, slave2.nextRequestId(),
                        new Messages.Subscribe(new DimensionRef[] {DIM}, true, 0));
                Thread.sleep(400);
                assertTrue(slave2.received.stream().anyMatch(m -> m.type() == MessageType.SNAPSHOT_PUSH),
                        "重连 slave 必须重放已提交快照");
            }

            // 断线重连且已 ack：不再重放
            long lastSeq = this.eventLog.lastSequence();
            try (TestClient slave3 = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                    serverPort(), slaveSsl())) {
                slave3.awaitConnected();
                slave3.send(MessageType.SUBSCRIBE, slave3.nextRequestId(),
                        new Messages.Subscribe(new DimensionRef[] {DIM}, true, lastSeq));
                Thread.sleep(400);
                assertTrue(slave3.received.stream().noneMatch(m -> m.type() == MessageType.SNAPSHOT_PUSH),
                        "已 ack 的订阅不得重复重放");
            }
        }
    }

    @Test
    void resolveUnavailableWhenServiceShuttingDown() throws Exception {
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl());
             TestClient slave = new TestClient(SLAVE_A, Role.VELOCITY, FINGERPRINT, REGISTRY_FINGERPRINT,
                     serverPort(), slaveSsl())) {
            source.awaitConnected();
            slave.awaitConnected();
            this.service.markShuttingDown();
            Messages.ResolveChunkRes res = resolve(slave);
            assertEquals(ResolveStatus.UNAVAILABLE, res.status(), "服务不可用时必须 fail closed");
        }
    }

    @Test
    void recoveryAfterRestartKeepsCommitted() throws Exception {
        try (TestClient source = new TestClient(SOURCE_ID, Role.SOURCE, FINGERPRINT, REGISTRY_FINGERPRINT,
                serverPort(), sourceSsl())) {
            source.awaitConnected();
            source.send(MessageType.INVALIDATE, source.nextRequestId(),
                    new Messages.Invalidate(DIM, 3, -1, 5, InvalidateReason.BLOCK_STATE));
            WireMessage ack = source.call(MessageType.PUT_SNAPSHOT, snapshot(5, gzipPayload("data-5")));
            assertEquals(PutResult.OK, ((Messages.PutSnapshotAck) ack.decodeBody()).result());
        }
        this.server.stop();

        // 重启（同一对象库目录）：required/committed 恢复
        ServiceConfig restarted = new ServiceConfig(
                "127.0.0.1", 0, this.keyStore, "changeit", this.trustStore, "changeit", true,
                this.config.storeRoot(), this.config.eventLogPath(), 1000, "master-1",
                Map.of("source-server", "SOURCE", "slave-a", "VELOCITY", "slave-b", "VELOCITY"),
                16 * 1024 * 1024, 8, 20_000, 40_000, 1073742154);
        SnapshotObjectStore recovered = new SnapshotObjectStore(restarted.storeRoot());
        recovered.recover();
        assertEquals(5, recovered.indexOf(ChunkRef.of(DIM, 3, -1)).required(), "required 持久恢复");
        assertEquals(5, recovered.indexOf(ChunkRef.of(DIM, 3, -1)).committed(), "committed 持久恢复");
        assertEquals(ResolveStatus.HIT, switch (recovered.resolve(ChunkRef.of(DIM, 3, -1), 5).status()) {
            case HIT -> ResolveStatus.HIT;
            case MISS -> ResolveStatus.MISS;
            case STALE -> ResolveStatus.STALE;
            case INCOMPATIBLE -> ResolveStatus.INCOMPATIBLE;
            case UNAVAILABLE -> ResolveStatus.UNAVAILABLE;
        });
    }
}

package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.InvalidateReason;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.encapsulation.AuthToken;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedCipher;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedFrame;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedHandshake;
import cn.cubegarden.icbm.caching.protocol.encapsulation.StreamId;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SourceConnector 集成测试（里程碑 3：velocity 授信模式，master 主动连源服）。
 *
 * <p>假源服 = 封装通道服务端（AUTH 验证 forwarding secret → 发 HELLO → 等 HELLO_ACK →
 * 发事件）。覆盖：源服事件接入 master 对象库（required/committed/epoch）、serverName
 * 隔离、slave 订阅收到 backend 快照推送、错误 secret fail closed。
 */
class SourceConnectorTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final String SECRET = "velocity-forwarding-secret";
    private static final String PSK = "test-psk-1234567890";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";
    private static final int PROTOCOL = 1_073_742_154;

    @TempDir
    Path tempDir;

    private ServiceConfig config;
    private SnapshotObjectStore store;
    private EventLog eventLog;
    private PeerRegistry registry;
    private ControlService service;
    private Replicator replicator;
    private EncapsulatedControlServer encapsulated;
    private SourceConnector sourceConnector;

    @BeforeEach
    void setUp() throws Exception {
        this.config = new ServiceConfig(
                "127.0.0.1",
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1", "lobby-1", PSK, 0,
                Map.of(), null, 16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
        this.store = new SnapshotObjectStore(this.config.storeRoot(), this.config.serverName());
        this.store.recover();
        this.eventLog = new EventLog(this.config.eventLogPath(), this.config.maxEventLogEntries());
        this.eventLog.recover();
        this.registry = new PeerRegistry(this.config.roleBindings());
        this.replicator = new Replicator(this.store, this.eventLog, this.config);
        this.service = new ControlService(this.store, this.eventLog, this.replicator, this.registry, this.config);
    }

    @AfterEach
    void tearDown() {
        if (this.sourceConnector != null) {
            this.sourceConnector.close();
        }
        if (this.encapsulated != null) {
            this.encapsulated.close();
        }
    }

    private void startMasterEncapsulated() throws Exception {
        this.encapsulated = new EncapsulatedControlServer(this.config, this.service, this.replicator,
                this.registry, new EncapsulatedControlSession.Listener() {
                });
        this.encapsulated.start();
    }

    // ---- 假源服（封装通道服务端，velocity 授信方向） ----

    private static final class FakeBackendServer implements AutoCloseable {
        final ServerSocket server;
        final String secret;
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch helloAcked = new CountDownLatch(1);
        final CountDownLatch putAckReceived = new CountDownLatch(1);
        final AtomicLong sequence = new AtomicLong();
        volatile boolean accepted;
        volatile boolean connected;

        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private EncapsulatedCipher encrypt; // server→client
        private EncapsulatedCipher decrypt; // client→server

        FakeBackendServer(String secret, String serverSecret) throws Exception {
            this.secret = secret;          // master 侧配置（AUTH 客户端用它计算 token）
            this.server = new ServerSocket(0);
            byte[] expectedSecret = serverSecret.getBytes(StandardCharsets.UTF_8);
            Thread t = new Thread(() -> {
                try (Socket s = this.server.accept()) {
                    s.setSoTimeout(8000);
                    this.socket = s;
                    this.in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                    byte[] authFrame = readWireFrame(this.in);
                    byte[] plaintext = EncapsulatedFrame.decodePlaintextPayload(authFrame);
                    EncapsulatedHandshake.Auth auth = EncapsulatedHandshake.decodeAuth(plaintext);
                    if (!AuthToken.verify(expectedSecret, auth.uuid(), auth.clientNonce(), auth.authToken())) {
                        this.events.add("auth_rejected");
                        writeAck(false, "auth failed");
                        return;
                    }
                    byte[] serverNonce = new byte[ProtocolConstants.UUID_BYTES];
                    new SecureRandom().nextBytes(serverNonce);
                    writeAck(true, serverNonce);
                    EncapsulatedCipher.SessionKeys keys = EncapsulatedCipher.SessionKeys.derive(
                            expectedSecret, auth.clientNonce(), serverNonce, auth.uuid());
                    this.encrypt = keys.serverToClient();
                    this.decrypt = keys.clientToServer();
                    this.connected = true;
                    this.sendHello();
                    // 等 HELLO_ACK
                    while (true) {
                        Envelope env = this.readControl();
                        if (env.messageType() == MessageType.HELLO_ACK) {
                            Messages.HelloAck ack = decode(MessageType.HELLO_ACK, env.body());
                            this.accepted = ack.accepted();
                            this.events.add("hello_ack:" + (ack.accepted() ? "accepted" : "rejected:" + ack.reason()));
                            this.helloAcked.countDown();
                            break;
                        }
                    }
                    // 读循环：收 PUT_SNAPSHOT_ACK
                    while (true) {
                        Envelope env = this.readControl();
                        if (env.messageType() == MessageType.PUT_SNAPSHOT_ACK) {
                            Messages.PutSnapshotAck ack = decode(MessageType.PUT_SNAPSHOT_ACK, env.body());
                            this.events.add("put_ack:" + ack.result());
                            this.putAckReceived.countDown();
                        }
                    }
                } catch (Exception e) {
                    this.events.add("error:" + e.toString());
                }
            }, "fake-backend");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return this.server.getLocalPort();
        }

        void sendHello() throws Exception {
            byte[] body = encode(MessageType.HELLO, new Messages.Hello(
                    Role.SOURCE, "lobby-1", "26.3", PROTOCOL, "0.19.3", "0.157.1+26.3",
                    "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0));
            this.send(MessageType.HELLO, 1, 0, body);
        }

        void sendWorldEpoch(long epoch) throws Exception {
            byte[] body = encode(MessageType.WORLD_EPOCH, new Messages.WorldEpoch(
                    DIM, epoch, cn.cubegarden.icbm.caching.protocol.EpochReason.START, REGISTRY_FINGERPRINT));
            this.send(MessageType.WORLD_EPOCH, 0, epoch, body);
        }

        void sendInvalidate(long revision) throws Exception {
            byte[] body = encode(MessageType.INVALIDATE, new Messages.Invalidate(
                    DIM, 3, -1, revision, InvalidateReason.BLOCK_STATE));
            this.send(MessageType.INVALIDATE, 0, 1, body);
        }

        void sendPut(long revision, byte[] gzipPayload) throws Exception {
            byte[] body = encode(MessageType.PUT_SNAPSHOT, new Messages.PutSnapshot(
                    DIM, 3, -1, revision, ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                    ProtocolConstants.COMPRESSION_GZIP, decompressedLength(gzipPayload),
                    sha(gzipPayload), gzipPayload));
            this.send(MessageType.PUT_SNAPSHOT, 7, 1, body);
        }

        void send(MessageType type, long requestId, long worldEpoch, byte[] body) throws Exception {
            long seq = this.sequence.incrementAndGet();
            Envelope envelope = Envelope.of(type, requestId, "source-server", worldEpoch, seq,
                    System.currentTimeMillis(), body);
            byte[] frameBody = FrameCodec.encodeFrame(envelope);
            byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encrypt, StreamId.CONTROL, frameBody);
            synchronized (this.out) {
                this.out.write(frame);
                this.out.flush();
            }
        }

        Envelope readControl() throws Exception {
            byte[] frame = readWireFrame(this.in);
            EncapsulatedFrame.Decrypted decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.decrypt, frame);
            assertEquals(StreamId.CONTROL, decrypted.stream());
            return FrameCodec.decodeFrame(decrypted.body());
        }

        private void writeAck(boolean accepted, byte[] serverNonce) throws Exception {
            byte[] ack = EncapsulatedFrame.encodePlaintextFrame(EncapsulatedHandshake.encodeAuthAck(
                    new EncapsulatedHandshake.AuthAck(accepted, serverNonce, accepted ? "" : "auth failed")));
            this.out.write(ack);
            this.out.flush();
        }

        private void writeAck(boolean accepted, String reason) throws Exception {
            byte[] ack = EncapsulatedFrame.encodePlaintextFrame(EncapsulatedHandshake.encodeAuthAck(
                    new EncapsulatedHandshake.AuthAck(accepted, null, reason)));
            this.out.write(ack);
            this.out.flush();
        }

        @Override
        public void close() throws Exception {
            this.server.close();
            if (this.socket != null) {
                this.socket.close();
            }
        }
    }

    // ---- 工具 ----

    private static byte[] readWireFrame(DataInputStream in) throws Exception {
        byte[] magic = new byte[4];
        in.readFully(magic);
        int length = in.readInt();
        byte[] payload = new byte[length];
        in.readFully(payload);
        byte[] frame = new byte[8 + length];
        System.arraycopy(magic, 0, frame, 0, 4);
        frame[4] = (byte) (length >>> 24);
        frame[5] = (byte) (length >>> 16);
        frame[6] = (byte) (length >>> 8);
        frame[7] = (byte) length;
        System.arraycopy(payload, 0, frame, 8, length);
        return frame;
    }

    private static byte[] encode(MessageType type, Object message) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageBodyCodecSafe.encode(type, new DataOutputStream(bytes), message);
        return bytes.toByteArray();
    }

    private static <T> T decode(MessageType type, byte[] body) throws Exception {
        return MessageBodyCodecSafe.decode(type, new DataInputStream(new ByteArrayInputStream(body)));
    }

    private static byte[] gzipPayload(String content) throws Exception {
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

    // ---- 测试 ----

    @Test
    void sourceEventsReachObjectStore() throws Exception {
        try (FakeBackendServer backend = new FakeBackendServer(SECRET, SECRET)) {
            ServiceConfig withBackend = new ServiceConfig(
                    "127.0.0.1",
                    this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                    "master-1", "lobby-1", PSK, 0,
                    Map.of(), new ServiceConfig.BackendServer("127.0.0.1", backend.port(), SECRET),
                    16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
            this.sourceConnector = new SourceConnector(withBackend, this.service, this.replicator, this.registry);
            this.sourceConnector.start();

            assertTrue(backend.helloAcked.await(5, TimeUnit.SECONDS), "源服 HELLO 必须被接受: " + backend.events);
            assertTrue(backend.accepted, "HELLO_ACK 必须 accepted: " + backend.events);

            // 源服无玩家在线：事件可达（master 主动连接）
            backend.sendWorldEpoch(1);
            backend.sendInvalidate(5);
            backend.sendPut(5, gzipPayload("backend-snapshot"));

            assertTrue(backend.putAckReceived.await(5, TimeUnit.SECONDS), "必须收到 PUT_SNAPSHOT_ACK");
            assertTrue(backend.events.contains("put_ack:OK"), "PUT 必须被接受: " + backend.events);

            // master 对象库：required/committed/epoch
            ChunkRef key = ChunkRef.of(DIM, 3, -1);
            SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
            assertNotNull(index, "源服快照必须进入 master 对象库");
            assertEquals(5, index.required());
            assertEquals(5, index.committed());
            assertEquals(1, this.store.dimensionEpoch(DIM));
            // serverName 隔离：对象库按 serverName 分域
            assertTrue(this.tempDir.resolve("objects").resolve("lobby-1").toFile().exists(),
                    "对象库必须按 serverName 分域");
        }
    }

    @Test
    void wrongSecretAuthRejected() throws Exception {
        try (FakeBackendServer backend = new FakeBackendServer("wrong-secret", SECRET)) {
            ServiceConfig withBackend = new ServiceConfig(
                    "127.0.0.1",
                    this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                    "master-1", "lobby-1", PSK, 0,
                    Map.of(), new ServiceConfig.BackendServer("127.0.0.1", backend.port(), "wrong-secret"),
                    16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
            this.sourceConnector = new SourceConnector(withBackend, this.service, this.replicator, this.registry);
            this.sourceConnector.start();
            Thread.sleep(500);
            assertTrue(backend.events.contains("auth_rejected"), "错误 forwarding secret 必须被拒: " + backend.events);
        }
    }

    @Test
    void slaveReceivesBackendSnapshot() throws Exception {
        this.startMasterEncapsulated();
        try (FakeBackendServer backend = new FakeBackendServer(SECRET, SECRET)) {
            ServiceConfig withBackend = new ServiceConfig(
                    "127.0.0.1",
                    this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                    "master-1", "lobby-1", PSK, 0,
                    Map.of("1112131415161718191a1b1c1d1e1f20", "SLAVE"),
                    new ServiceConfig.BackendServer("127.0.0.1", backend.port(), SECRET),
                    16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
            this.sourceConnector = new SourceConnector(withBackend, this.service, this.replicator, this.registry);
            this.sourceConnector.start();
            assertTrue(backend.helloAcked.await(5, TimeUnit.SECONDS), "源服 HELLO 必须被接受: " + backend.events);

            // slave 订阅
            CountDownLatch snapshotPushed = new CountDownLatch(1);
            List<String> slaveSeen = new CopyOnWriteArrayList<>();
            try (EncapsulatedChannelClient slave = new EncapsulatedChannelClient("127.0.0.1",
                    this.encapsulated.port(), "slave-a", "1112131415161718191a1b1c1d1e1f20", PSK,
                    new EncapsulatedChannelClient.HelloConfig(Role.SLAVE, "lobby-1", "26.3", PROTOCOL,
                            "0.19.3", "0.157.1+26.3", "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0),
                    5000, 100, false, new EncapsulatedChannelClient.Listener() {
                        @Override
                        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
                            slaveSeen.add(type.name());
                            if (type == MessageType.SNAPSHOT_PUSH) {
                                snapshotPushed.countDown();
                            }
                        }
                    })) {
                slave.start();
                Thread.sleep(300);
                slave.send(MessageType.SUBSCRIBE, 1, 0, EncapsulatedChannelClient.encode(
                        MessageType.SUBSCRIBE, new Messages.Subscribe(new DimensionRef[] {DIM}, true, 0)));

                // 源服（无玩家在线）发事件 → master → slave 推送
                backend.sendWorldEpoch(1);
                backend.sendInvalidate(5);
                backend.sendPut(5, gzipPayload("backend-snapshot"));

                assertTrue(snapshotPushed.await(5, TimeUnit.SECONDS), "slave 必须收到 SNAPSHOT_PUSH 推送");
                assertTrue(backend.putAckReceived.await(5, TimeUnit.SECONDS), "master 必须回 PUT_SNAPSHOT_ACK");
                assertTrue(slaveSeen.contains("INVALIDATE"), "slave 必须收到 INVALIDATE 推送: " + slaveSeen);
            }
        }
    }

    /** 局部引用（避免与测试类名冲突的编解码辅助）。 */
    private static final class MessageBodyCodecSafe {
        static void encode(MessageType type, DataOutputStream out, Object message) throws Exception {
            cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.encode(type, out, message);
        }

        @SuppressWarnings("unchecked")
        static <T> T decode(MessageType type, DataInputStream in) throws Exception {
            return (T) cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.decode(type, in);
        }
    }
}

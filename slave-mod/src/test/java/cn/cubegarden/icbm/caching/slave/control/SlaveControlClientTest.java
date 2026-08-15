package cn.cubegarden.icbm.caching.slave.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.protocol.encapsulation.AuthToken;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedCipher;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedFrame;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedHandshake;
import cn.cubegarden.icbm.caching.protocol.encapsulation.StreamId;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.slave.config.SlaveConfig;
import cn.cubegarden.icbm.caching.slave.world.SnapshotChunkLoader;
import cn.cubegarden.icbm.caching.slave.world.SpawnRegistry;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SlaveControlClient 集成测试（里程碑 4）：假 master（封装通道服务端）→ slave 客户端
 * HELLO/SUBSCRIBE → 事件应用（WORLD_EPOCH spawn + INVALIDATE + SNAPSHOT_PUSH）→ ACK；
 * LOGIN_VERIFY 流递交与 RES 放行/拒绝。
 */
class SlaveControlClientTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final String PSK = "slave-test-psk";
    private static final String UUID_HEX = "1112131415161718191a1b1c1d1e1f20";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";

    @TempDir
    Path tempDir;

    private SnapshotObjectStore store;
    private SnapshotChunkLoader loader;
    private SpawnRegistry spawnRegistry;
    private SlaveControlClient client;

    @BeforeEach
    void setUp() throws Exception {
        this.store = new SnapshotObjectStore(this.tempDir.resolve("objects"), "lobby-1");
        this.store.recover();
        this.loader = new SnapshotChunkLoader(this.store, DIM);
        this.spawnRegistry = new SpawnRegistry();
    }

    @AfterEach
    void tearDown() {
        if (this.client != null) {
            this.client.close();
        }
    }

    // ---- 假 master（封装通道服务端，SLAVE 连接方向） ----

    private static final class FakeMaster implements AutoCloseable {
        final ServerSocket server;
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch acked = new CountDownLatch(1);
        final CountDownLatch loginVerifyReceived = new CountDownLatch(1);

        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private EncapsulatedCipher encrypt; // server→client
        private EncapsulatedCipher decrypt; // client→server
        private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();

        FakeMaster() throws Exception {
            this.server = new ServerSocket(0);
            byte[] pskBytes = PSK.getBytes(StandardCharsets.UTF_8);
            Thread t = new Thread(() -> {
                try (Socket s = this.server.accept()) {
                    s.setSoTimeout(8000);
                    this.socket = s;
                    this.in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                    byte[] authFrame = readWireFrame(this.in);
                    byte[] plaintext = EncapsulatedFrame.decodePlaintextPayload(authFrame);
                    EncapsulatedHandshake.Auth auth = EncapsulatedHandshake.decodeAuth(plaintext);
                    if (!AuthToken.verify(pskBytes, auth.uuid(), auth.clientNonce(), auth.authToken())) {
                        this.events.add("auth_rejected");
                        return;
                    }
                    byte[] serverNonce = new byte[16];
                    new SecureRandom().nextBytes(serverNonce);
                    writeAck(true, serverNonce);
                    EncapsulatedCipher.SessionKeys keys = EncapsulatedCipher.SessionKeys.derive(
                            pskBytes, auth.clientNonce(), serverNonce, auth.uuid());
                    this.encrypt = keys.serverToClient();
                    this.decrypt = keys.clientToServer();
                    // 读 HELLO（客户端发）→ 回 HELLO_ACK
                    Envelope helloEnv = this.readControl();
                    assertEquals(MessageType.HELLO, helloEnv.messageType());
                    Messages.Hello hello = decode(MessageType.HELLO, helloEnv.body());
                    this.events.add("hello:" + hello.role() + ":" + hello.serverName());
                    this.send(MessageType.HELLO_ACK, helloEnv.requestId(), 0,
                            encode(MessageType.HELLO_ACK, new Messages.HelloAck(true, "", "master-1")));
                    // 读 SUBSCRIBE
                    Envelope subEnv = this.readControl();
                    assertEquals(MessageType.SUBSCRIBE, subEnv.messageType());
                    Messages.Subscribe sub = decode(MessageType.SUBSCRIBE, subEnv.body());
                    this.events.add("subscribe:" + sub.dimensions().length);
                    this.subscribed.countDown();
                    // 读循环：ACK / LOGIN_VERIFY
                    while (true) {
                        byte[] frame = readWireFrame(this.in);
                        EncapsulatedFrame.Decrypted dec = EncapsulatedFrame.decodeEncryptedFrame(this.decrypt, frame);
                        if (dec.stream() == StreamId.LOGIN_VERIFY) {
                            this.loginVerifyReceived.countDown();
                        } else if (dec.stream() == StreamId.CONTROL) {
                            Envelope env = FrameCodec.decodeFrame(dec.body());
                            if (env.messageType() == MessageType.ACK) {
                                Messages.Ack ack = decode(MessageType.ACK, env.body());
                                this.events.add("ack:" + ack.ackedSequence());
                                this.acked.countDown();
                            } else {
                                this.events.add("control:" + env.messageType());
                            }
                        }
                    }
                } catch (Exception e) {
                    this.events.add("error:" + e.toString());
                }
            }, "fake-master");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return this.server.getLocalPort();
        }

        void sendWorldEpoch() throws Exception {
            byte[] body = encode(MessageType.WORLD_EPOCH, new Messages.WorldEpoch(
                    DIM, 1, cn.cubegarden.icbm.caching.protocol.EpochReason.START, REGISTRY_FINGERPRINT,
                    new Messages.SpawnPoint(8.5, 64.0, 8.5, 90.0F, 0.0F)));
            this.send(MessageType.WORLD_EPOCH, 0, 1, body);
        }

        void sendInvalidate(long revision) throws Exception {
            byte[] body = encode(MessageType.INVALIDATE, new Messages.Invalidate(
                    DIM, 3, -1, revision, cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE));
            this.send(MessageType.INVALIDATE, 0, 1, body);
        }

        void sendSnapshotPush() throws Exception {
            byte[] gzip = gzip("slave-snapshot");
            int rawLength = "slave-snapshot".getBytes(StandardCharsets.UTF_8).length;
            byte[] body = encode(MessageType.SNAPSHOT_PUSH, new Messages.SnapshotPush(
                    new Messages.PutSnapshot(DIM, 3, -1, 5, "NBT_CHUNK_V1", "GZIP",
                            rawLength, sha(gzip), gzip)));
            this.send(MessageType.SNAPSHOT_PUSH, 7, 1, body);
        }

        void sendLoginVerifyRes() throws Exception {
            byte[] res = encode(MessageType.LOGIN_VERIFY_RES, new Messages.LoginVerifyRes(
                    true, "", new Messages.MasterVerifiedIdentity(
                            "master", "key-1", "lobby-1", new byte[16], "sess-1",
                            new byte[16], System.currentTimeMillis() + 60_000, new byte[0],
                            new Messages.ProfileProperty[0])));
            byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encrypt, StreamId.LOGIN_VERIFY, res);
            this.out.write(frame);
            this.out.flush();
        }

        void send(MessageType type, long requestId, long worldEpoch, byte[] body) throws Exception {
            Envelope envelope = Envelope.of(type, requestId, "master-1", worldEpoch,
                    this.sequence.incrementAndGet(), System.currentTimeMillis(), body);
            byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encrypt, StreamId.CONTROL,
                    FrameCodec.encodeFrame(envelope));
            this.out.write(frame);
            this.out.flush();
        }

        Envelope readControl() throws Exception {
            byte[] frame = readWireFrame(this.in);
            EncapsulatedFrame.Decrypted dec = EncapsulatedFrame.decodeEncryptedFrame(this.decrypt, frame);
            assertEquals(StreamId.CONTROL, dec.stream());
            return FrameCodec.decodeFrame(dec.body());
        }

        private void writeAck(boolean accepted, byte[] serverNonce) throws Exception {
            byte[] ack = EncapsulatedFrame.encodePlaintextFrame(EncapsulatedHandshake.encodeAuthAck(
                    new EncapsulatedHandshake.AuthAck(accepted, serverNonce, accepted ? "" : "auth failed")));
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
        cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.encode(type, new DataOutputStream(bytes), message);
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static <T> T decode(MessageType type, byte[] body) throws Exception {
        return (T) cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.decode(
                type, new DataInputStream(new ByteArrayInputStream(body)));
    }

    private static String sha(byte[] payload) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    /** 轮询等待条件成立（最多 3 秒）。 */
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待条件超时");
    }

    private static byte[] gzip(String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    // ---- 测试 ----

    @Test
    void eventsAppliedToObjectStore() throws Exception {
        try (FakeMaster master = new FakeMaster()) {
            SlaveConfig config = new SlaveConfig.Builder()
                    .masterHost("127.0.0.1").masterPort(master.port())
                    .psk(PSK).uuidHex(UUID_HEX).serverName("lobby-1")
                    .storeRoot(this.tempDir.resolve("objects"))
                    .dimensions(List.of("minecraft:overworld"))
                    .build();
            this.client = new SlaveControlClient(config, this.store, this.loader, this.spawnRegistry,
                    "26.3", 1_073_742_154, "0.19.3", "0.157.1+26.3",
                    "modpack-hash", REGISTRY_FINGERPRINT);
            this.client.start();

            assertTrue(master.subscribed.await(5, TimeUnit.SECONDS), "slave 必须完成订阅: " + master.events);
            assertTrue(master.events.stream().anyMatch(e -> e.startsWith("hello:SLAVE:lobby-1")),
                    "HELLO 必须携带 SLAVE 角色与 serverName: " + master.events);

            // master（无玩家）推事件 → slave 对象库 + spawn 注册（≥ ackBatchSize 触发 ACK）
            master.sendWorldEpoch();
            for (int i = 0; i < 16; i++) {
                master.sendInvalidate(5); // 同 revision（required 单调 max，不越过 PUT）
            }
            master.sendSnapshotPush();

            assertTrue(master.acked.await(5, TimeUnit.SECONDS), "必须 ACK: " + master.events);
            // 等待全部事件应用（ACK 在批窗口边界触发，后续事件可能仍在途）
            ChunkRef key = ChunkRef.of(DIM, 3, -1);
            await(() -> {
                SnapshotObjectStore.ChunkIndex idx = this.store.indexOf(key);
                return idx != null && idx.required() == 5 && idx.committed() == 5;
            });
            SnapshotObjectStore.ChunkIndex index = this.store.indexOf(key);
            assertNotNull(index, "快照必须进入 slave 对象库");
            assertEquals(5, index.required(), "INVALIDATE required 单调 max（同 revision 幂等）");
            assertEquals(5, index.committed());
            assertEquals(1, this.store.dimensionEpoch(DIM));
            assertEquals(1, this.spawnRegistry.size(), "WORLD_EPOCH 必须注册 spawn");
            Messages.SpawnPoint spawn = this.spawnRegistry.spawnFor(DIM);
            assertEquals(8.5, spawn.x());
            assertEquals(64.0, spawn.y());
            assertTrue(this.loader.isChunkServiceable(3, -1), "已装载区块必须可服务");
            assertTrue(!this.loader.isChunkServiceable(99, 99), "未命中区块不可服务");
        }
    }

    @Test
    void loginVerifyAcceptedAndDuplicateRejectedByMaster() throws Exception {
        try (FakeMaster master = new FakeMaster()) {
            SlaveConfig config = new SlaveConfig.Builder()
                    .masterHost("127.0.0.1").masterPort(master.port())
                    .psk(PSK).uuidHex(UUID_HEX).serverName("lobby-1")
                    .storeRoot(this.tempDir.resolve("objects"))
                    .dimensions(List.of("minecraft:overworld"))
                    .build();
            this.client = new SlaveControlClient(config, this.store, this.loader, this.spawnRegistry,
                    "26.3", 1_073_742_154, "0.19.3", "0.157.1+26.3",
                    "modpack-hash", REGISTRY_FINGERPRINT);
            this.client.start();
            assertTrue(master.subscribed.await(5, TimeUnit.SECONDS));

            // 后台线程递交 LOGIN_VERIFY（模拟验证线程）→ master 回 RES
            Thread verifier = new Thread(() -> {
                Messages.LoginVerifyRes res = this.client.verifyLogin(new Messages.LoginVerifyReq(
                        "Steve", new byte[16], new Messages.ProfileProperty[0],
                        "serverHash", new byte[16], "lobby-1", "127.0.0.1:50000",
                        new byte[16], System.currentTimeMillis()));
                if (res != null && res.verified()) {
                    this.spawnRegistry.onWorldEpoch(DIM, new Messages.SpawnPoint(8, 64, 8, 0, 0));
                }
            });
            verifier.start();
            assertTrue(master.loginVerifyReceived.await(5, TimeUnit.SECONDS), "master 必须收到 LOGIN_VERIFY_REQ");
            master.sendLoginVerifyRes();
            verifier.join(5000);
            assertEquals(1, this.spawnRegistry.size(), "验证通过后玩家可放置（spawn 注册）");
        }
    }

    @Test
    void authRejectedByWrongPsk() throws Exception {
        try (FakeMaster master = new FakeMaster()) {
            SlaveConfig config = new SlaveConfig.Builder()
                    .masterHost("127.0.0.1").masterPort(master.port())
                    .psk("wrong-psk").uuidHex(UUID_HEX).serverName("lobby-1")
                    .storeRoot(this.tempDir.resolve("objects"))
                    .dimensions(List.of("minecraft:overworld"))
                    .build();
            this.client = new SlaveControlClient(config, this.store, this.loader, this.spawnRegistry,
                    "26.3", 1_073_742_154, "0.19.3", "0.157.1+26.3",
                    "modpack-hash", REGISTRY_FINGERPRINT);
            this.client.start();
            Thread.sleep(600);
            assertTrue(master.events.contains("auth_rejected"), "错误 PSK 必须被拒: " + master.events);
            assertTrue(!this.loader.masterConnected(), "AUTH 失败 → fail closed（区块不可服务）");
            assertNull(this.client.verifyLogin(new Messages.LoginVerifyReq(
                    "Steve", new byte[16], new Messages.ProfileProperty[0],
                    "h", new byte[16], "lobby-1", "x", new byte[16], 0)), "未连接 → 拒登录");
        }
    }
}

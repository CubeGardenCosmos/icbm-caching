package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlServer;
import cn.cubegarden.icbm.caching.control.net.EncapsulatedControlSession;
import cn.cubegarden.icbm.caching.control.net.PeerRegistry;
import cn.cubegarden.icbm.caching.control.replication.EventLog;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.ResolveStatus;
import cn.cubegarden.icbm.caching.protocol.Role;
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
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 封装通道服务器测试（阶段1 验收）：非 magic 首字节 RST、AUTH 拒绝/接受、
 * ACTIVE 加密控制流（HELLO/WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/RESOLVE/SUBSCRIBE 推送）。
 */
class EncapsulatedControlServerTest {

    private static final String PSK = "test-psk-1234567890";
    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final String FINGERPRINT = "modpack-hash";
    private static final String REGISTRY_FINGERPRINT = "registry-hash";
    private static final int PROTOCOL = 1_073_742_154;

    @TempDir
    Path tempDir;

    private SnapshotObjectStore store;
    private EncapsulatedControlServer server;

    @BeforeEach
    void setUp() throws Exception {
        ServiceConfig config = new ServiceConfig(
                "127.0.0.1",
                this.tempDir.resolve("objects"), this.tempDir.resolve("events.log"), 1000,
                "master-1", "lobby-1", PSK, 0,
                java.util.Map.of(), null, 16 * 1024 * 1024, 8, 20_000, 40_000, PROTOCOL);
        this.store = new SnapshotObjectStore(config.storeRoot(), config.serverName());
        this.store.recover();
        EventLog eventLog = new EventLog(config.eventLogPath(), config.maxEventLogEntries());
        eventLog.recover();
        PeerRegistry registry = new PeerRegistry(config.roleBindings());
        Replicator replicator = new Replicator(this.store, eventLog, config);
        ControlService service = new ControlService(this.store, eventLog, replicator, registry, config);
        this.server = new EncapsulatedControlServer(config, service, replicator, registry,
                new EncapsulatedControlSession.Listener() {
                });
        this.server.start();
    }

    @AfterEach
    void tearDown() {
        this.server.close();
    }

    // ---- 封装通道测试客户端 ----

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final EncapsulatedCipher enc; // client→server（出站加密）
        final EncapsulatedCipher dec; // server→client（入站解密）
        final String uuidHex;
        final AtomicLong sequence = new AtomicLong(1); // HELLO 用序号 1
        final AtomicLong requestIds = new AtomicLong(1);
        long worldEpoch; // 源侧当前维度 epoch（WORLD_EPOCH 后携带）

        Client(Socket socket, String psk, String uuidHex) throws Exception {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            this.uuidHex = uuidHex;
            byte[] uuid = HexFormat.of().parseHex(uuidHex);
            byte[] clientNonce = new byte[16];
            new SecureRandom().nextBytes(clientNonce);
            byte[] token = AuthToken.compute(psk.getBytes(StandardCharsets.UTF_8), uuid, clientNonce);
            byte[] authFrame = EncapsulatedFrame.encodePlaintextFrame(
                    EncapsulatedHandshake.encodeAuth(new EncapsulatedHandshake.Auth(token, uuid, clientNonce)));
            this.out.write(authFrame);
            this.out.flush();
            byte[] ackFrame = readWireFrame(this.in);
            EncapsulatedHandshake.AuthAck ack = EncapsulatedHandshake.decodeAuthAck(
                    EncapsulatedFrame.decodePlaintextPayload(ackFrame));
            if (!ack.accepted()) {
                throw new IOException("AUTH 被拒绝: " + ack.reason());
            }
            EncapsulatedCipher.SessionKeys keys = EncapsulatedCipher.SessionKeys.derive(
                    psk.getBytes(StandardCharsets.UTF_8), clientNonce, ack.serverNonce(), uuid);
            this.enc = keys.clientToServer();
            this.dec = keys.serverToClient();
        }

        /** 发送 HELLO（AUTH 后第一个控制帧，序号 1）。 */
        void sendHello(Role role, String serverName) throws Exception {
            Envelope hello = Envelope.of(MessageType.HELLO, 1, this.uuidHex, 0, 1,
                    System.currentTimeMillis(), encode(MessageType.HELLO, new Messages.Hello(
                            role, serverName, "26.3", PROTOCOL, "0.19.3", "0.157.1+26.3",
                            FINGERPRINT, REGISTRY_FINGERPRINT, (byte) 0)));
            this.sendControl(hello);
        }

        void sendControl(Envelope envelope) throws Exception {
            byte[] body = FrameCodec.encodeFrame(envelope);
            byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.enc, StreamId.CONTROL, body);
            synchronized (this.out) {
                this.out.write(frame);
                this.out.flush();
            }
        }

        /** 发送（无响应消息：WORLD_EPOCH/INVALIDATE/SUBSCRIBE 等）。 */
        void send(MessageType type, Object body) throws Exception {
            Envelope envelope = Envelope.of(type, this.requestIds.incrementAndGet(), this.uuidHex,
                    this.worldEpoch, this.sequence.incrementAndGet(), System.currentTimeMillis(), encode(type, body));
            this.sendControl(envelope);
        }

        /** 发送并读取下一条响应。 */
        Envelope call(MessageType type, Object body) throws Exception {
            this.send(type, body);
            return this.readControl();
        }

        Envelope readControl() throws Exception {
            byte[] frame = readWireFrame(this.in);
            EncapsulatedFrame.Decrypted decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.dec, frame);
            assertEquals(StreamId.CONTROL, decrypted.stream(), "期望控制流");
            return FrameCodec.decodeFrame(decrypted.body());
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
        }
    }

    private static byte[] encode(MessageType type, Object message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MessageBodyCodec.encode(type, new DataOutputStream(bytes), message);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T decode(Envelope envelope) {
        try {
            return (T) MessageBodyCodec.decode(envelope.messageType(),
                    new DataInputStream(new ByteArrayInputStream(envelope.body())));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readWireFrame(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        int length = in.readInt();
        if (length < 8 || length > ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES) {
            throw new IOException("frame length out of range: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        byte[] frame = new byte[8 + payload.length];
        System.arraycopy(magic, 0, frame, 0, 4);
        frame[4] = (byte) (length >>> 24);
        frame[5] = (byte) (length >>> 16);
        frame[6] = (byte) (length >>> 8);
        frame[7] = (byte) length;
        System.arraycopy(payload, 0, frame, 8, payload.length);
        return frame;
    }

    private static byte[] gzipPayload(String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static String sha(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private static int decompressedLength(byte[] gzipPayload) throws Exception {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(gzipPayload))) {
            return in.readAllBytes().length;
        }
    }

    // ---- 测试 ----

    @Test
    void nonMagicFirstByteGetsRst() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", this.server.port())) {
            socket.getOutputStream().write('X'); // 原版握手/畸形流
            socket.getOutputStream().flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            assertThrows(IOException.class, in::read, "SO_LINGER=0 RST 应表现为连接重置而非干净 EOF");
        }
    }

    @Test
    void authRejectedOnWrongPsk() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", this.server.port())) {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            byte[] uuid = HexFormat.of().parseHex("0102030405060708090a0b0c0d0e0f10");
            byte[] clientNonce = new byte[16];
            new SecureRandom().nextBytes(clientNonce);
            byte[] token = AuthToken.compute("wrong-psk".getBytes(StandardCharsets.UTF_8), uuid, clientNonce);
            byte[] frame = EncapsulatedFrame.encodePlaintextFrame(
                    EncapsulatedHandshake.encodeAuth(new EncapsulatedHandshake.Auth(token, uuid, clientNonce)));
            out.write(frame);
            out.flush();
            EncapsulatedHandshake.AuthAck ack = EncapsulatedHandshake.decodeAuthAck(
                    EncapsulatedFrame.decodePlaintextPayload(readWireFrame(in)));
            assertTrue(!ack.accepted(), "错误 PSK 必须被拒绝");
        }
    }

    @Test
    void sourceAndSlaveFlowOverEncapsulatedChannel() throws Exception {
        // 源：AUTH → HELLO(SOURCE) → WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT → ACK OK
        try (Socket sourceSocket = new Socket("127.0.0.1", this.server.port());
             Client source = new Client(sourceSocket, PSK, "0102030405060708090a0b0c0d0e0f10")) {
            source.sendHello(Role.SOURCE, "lobby-1");
            Messages.HelloAck ack = decode(source.readControl());
            assertTrue(ack.accepted(), "源 HELLO 被拒绝: " + ack.reason());

            source.send(MessageType.WORLD_EPOCH, new Messages.WorldEpoch(DIM, 1,
                    cn.cubegarden.icbm.caching.protocol.EpochReason.START, REGISTRY_FINGERPRINT,
                    Messages.SpawnPoint.DEFAULT));
            source.worldEpoch = 1; // 后续包络携带源侧当前 epoch
            source.send(MessageType.INVALIDATE, new Messages.Invalidate(DIM, 3, -1, 7,
                    cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE));

            byte[] payload = gzipPayload("encapsulated-snapshot");
            Messages.PutSnapshot snapshot = new Messages.PutSnapshot(DIM, 3, -1, 7,
                    ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1, ProtocolConstants.COMPRESSION_GZIP,
                    decompressedLength(payload), sha(payload), payload);
            Messages.PutSnapshotAck putResult = decode(source.call(MessageType.PUT_SNAPSHOT, snapshot));
            assertEquals(PutResult.OK, putResult.result(), "快照应原子提交");

            // slave：AUTH → HELLO(SLAVE) → RESOLVE(HIT)
            try (Socket slaveSocket = new Socket("127.0.0.1", this.server.port());
                 Client slave = new Client(slaveSocket, PSK, "1112131415161718191a1b1c1d1e1f20")) {
                slave.sendHello(Role.SLAVE, "lobby-1");
                Messages.HelloAck slaveHello = decode(slave.readControl());
                assertTrue(slaveHello.accepted(), "slave HELLO 被拒绝: " + slaveHello.reason());

                Messages.ResolveChunkRes res = decode(slave.call(MessageType.RESOLVE_CHUNK_REQ,
                        new Messages.ResolveChunkReq(DIM, 3, -1, PROTOCOL, REGISTRY_FINGERPRINT, 0)));
                assertEquals(ResolveStatus.HIT, res.status(), "committed 快照应可装载");
                assertEquals(7, res.committedRevision());
            }
        }
    }

    @Test
    void slaveSubscribeReceivesPushedSnapshot() throws Exception {
        // 先由源提交快照
        try (Socket s = new Socket("127.0.0.1", this.server.port());
             Client source = new Client(s, PSK, "0102030405060708090a0b0c0d0e0f10")) {
            source.sendHello(Role.SOURCE, "lobby-1");
            assertTrue(decode(source.readControl()) instanceof Messages.HelloAck ack && ack.accepted());
            source.send(MessageType.WORLD_EPOCH, new Messages.WorldEpoch(DIM, 1,
                    cn.cubegarden.icbm.caching.protocol.EpochReason.START, REGISTRY_FINGERPRINT,
                    Messages.SpawnPoint.DEFAULT));
            source.worldEpoch = 1; // 后续包络携带源侧当前 epoch
            source.send(MessageType.INVALIDATE, new Messages.Invalidate(DIM, 3, -1, 7,
                    cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE));
            byte[] payload = gzipPayload("push-me");
            source.call(MessageType.PUT_SNAPSHOT, new Messages.PutSnapshot(DIM, 3, -1, 7,
                    ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1, ProtocolConstants.COMPRESSION_GZIP,
                    decompressedLength(payload), sha(payload), payload));
        }
        // slave 订阅：重放 WORLD_EPOCH/INVALIDATE/SNAPSHOT_PUSH（ackedSequence=0）
        try (Socket s = new Socket("127.0.0.1", this.server.port());
             Client slave = new Client(s, PSK, "1112131415161718191a1b1c1d1e1f20")) {
            slave.sendHello(Role.SLAVE, "lobby-1");
            assertTrue(decode(slave.readControl()) instanceof Messages.HelloAck ack && ack.accepted());
            slave.send(MessageType.SUBSCRIBE, new Messages.Subscribe(new DimensionRef[] {DIM}, true, 0));

            // 依次读到重放的三个事件（SNAPSHOT_PUSH 为最后一个）
            Messages.SnapshotPush push = null;
            for (int i = 0; i < 3; i++) {
                Envelope event = slave.readControl();
                if (event.messageType() == MessageType.SNAPSHOT_PUSH) {
                    push = decode(event);
                }
            }
            assertNotNull(push, "订阅后应收到 SNAPSHOT_PUSH 重放");
            assertEquals(7, push.snapshot().revision());
        }
    }
}

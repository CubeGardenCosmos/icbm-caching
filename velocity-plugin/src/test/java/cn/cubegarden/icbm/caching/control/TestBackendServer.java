package cn.cubegarden.icbm.caching.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.InvalidateReason;
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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 假源服（测试工具，velocity 授信方向）：封装通道服务端——AUTH 校验 forwarding secret →
 * 发 HELLO → 等 HELLO_ACK → 读循环（收 PUT_SNAPSHOT_ACK）。供 SourceConnectorTest /
 * AuthGatewayTest 使用（slave 连接前需源服先注册，PeerRegistry 约束）。
 */
public final class TestBackendServer implements AutoCloseable {

    public static final String SECRET = "velocity-forwarding-secret";
    public static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    public static final String REGISTRY_FINGERPRINT = "registry-hash";
    public static final String SERVER_NAME = "lobby-1";
    public static final int PROTOCOL = 1_073_742_154;

    final ServerSocket server;
    final String secret;
    final List<String> events = new CopyOnWriteArrayList<>();
    final CountDownLatch helloAcked = new CountDownLatch(1);
    final CountDownLatch putAckReceived = new CountDownLatch(1);
    final List<byte[]> receivedMcPackets = new CopyOnWriteArrayList<>();
    final List<Messages.SubmitOperation> receivedOperations = new CopyOnWriteArrayList<>();
    final List<Messages.GetOperationStatus> receivedStatusQueries = new CopyOnWriteArrayList<>();
    final java.util.concurrent.CountDownLatch operationReceived = new java.util.concurrent.CountDownLatch(1);
    /** 测试预设：收到 SUBMIT_OPERATION 后回发的终态（默认 COMMITTED diamond）。 */
    volatile Messages.OperationCommitted committedReply = new Messages.OperationCommitted(
            new byte[16], "minecraft:diamond_block", 7, 1, System.currentTimeMillis());
    volatile Messages.OperationRejected rejectedReply;
    final AtomicLong sequence = new AtomicLong();
    volatile boolean accepted;
    volatile boolean connected;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private EncapsulatedCipher encrypt; // server→client
    private EncapsulatedCipher decrypt; // client→server

    /** @param masterSecret master 侧 AUTH 客户端用于计算 token 的 secret（应与验证用 secret 一致） */
    public TestBackendServer(String masterSecret, String verifySecret) throws Exception {
        this.secret = masterSecret; // master 侧配置（AUTH 客户端用它计算 token）
        this.server = new ServerSocket(0);
        byte[] expectedSecret = verifySecret.getBytes(StandardCharsets.UTF_8);
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
                byte[] serverNonce = new byte[16];
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
                // 读循环：CONTROL（PUT_SNAPSHOT_ACK）与 MC_PASSTHROUGH（权威桥）
                while (true) {
                    byte[] frame = readWireFrame(this.in);
                    EncapsulatedFrame.Decrypted decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.decrypt, frame);
                    if (decrypted.stream() == StreamId.CONTROL) {
                        Envelope env = FrameCodec.decodeFrame(decrypted.body());
                        if (env.messageType() == MessageType.PUT_SNAPSHOT_ACK) {
                            Messages.PutSnapshotAck ack = decode(MessageType.PUT_SNAPSHOT_ACK, env.body());
                            this.events.add("put_ack:" + ack.result());
                            this.putAckReceived.countDown();
                        } else if (env.messageType() == MessageType.SUBMIT_OPERATION) {
                            Messages.SubmitOperation op = decode(MessageType.SUBMIT_OPERATION, env.body());
                            this.receivedOperations.add(op);
                            this.operationReceived.countDown();
                            this.events.add("submit_operation:" + java.util.HexFormat.of().formatHex(op.operationId()));
                            Messages.OperationRejected rejected = this.rejectedReply;
                            if (rejected != null) {
                                this.send(MessageType.OPERATION_REJECTED, 0, 0,
                                        encode(MessageType.OPERATION_REJECTED,
                                                new Messages.OperationRejected(op.operationId(),
                                                        rejected.reason(), op.dimension(),
                                                        rejected.resyncChunkX(), rejected.resyncChunkZ(),
                                                        System.currentTimeMillis())));
                            } else {
                                this.send(MessageType.OPERATION_COMMITTED, 0, 0,
                                        encode(MessageType.OPERATION_COMMITTED,
                                                new Messages.OperationCommitted(op.operationId(),
                                                        this.committedReply.canonicalState(),
                                                        this.committedReply.chunkRevision(),
                                                        this.committedReply.epoch(),
                                                        System.currentTimeMillis())));
                            }
                        } else if (env.messageType() == MessageType.GET_OPERATION_STATUS) {
                            Messages.GetOperationStatus req = decode(MessageType.GET_OPERATION_STATUS, env.body());
                            this.receivedStatusQueries.add(req);
                            this.send(MessageType.OPERATION_STATUS_RES, 0, 0,
                                    encode(MessageType.OPERATION_STATUS_RES,
                                            new Messages.OperationStatusRes(req.operationId(),
                                                    Messages.OperationStatusRes.OperationStatus.COMMITTED,
                                                    "minecraft:diamond_block", 7, "",
                                                    DIM, new int[0], new int[0])));
                        }
                    } else if (decrypted.stream() == StreamId.MC_PASSTHROUGH) {
                        this.receivedMcPackets.add(decrypted.body());
                    }
                }
            } catch (Exception e) {
                this.events.add("error:" + e.toString());
            }
        }, "fake-backend");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return this.server.getLocalPort();
    }

    void sendMcPassthrough(byte[] body) throws Exception {
        byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encrypt, StreamId.MC_PASSTHROUGH, body);
        synchronized (this.out) {
            this.out.write(frame);
            this.out.flush();
        }
    }

    void sendHello() throws Exception {
        byte[] body = encode(MessageType.HELLO, new Messages.Hello(
                Role.SOURCE, SERVER_NAME, "26.3", PROTOCOL, "0.19.3", "0.157.1+26.3",
                "modpack-hash", REGISTRY_FINGERPRINT, (byte) 0));
        this.send(MessageType.HELLO, 1, 0, body);
    }

    void sendWorldEpoch(long epoch) throws Exception {
        byte[] body = encode(MessageType.WORLD_EPOCH, new Messages.WorldEpoch(
                DIM, epoch, cn.cubegarden.icbm.caching.protocol.EpochReason.START, REGISTRY_FINGERPRINT,
                Messages.SpawnPoint.DEFAULT));
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

    static byte[] readWireFrame(DataInputStream in) throws Exception {
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

    static byte[] encode(MessageType type, Object message) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.encode(type, new DataOutputStream(bytes), message);
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static <T> T decode(MessageType type, byte[] body) throws Exception {
        return (T) cn.cubegarden.icbm.caching.protocol.MessageBodyCodec.decode(
                type, new DataInputStream(new ByteArrayInputStream(body)));
    }

    static String sha(byte[] payload) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload));
    }

    static int decompressedLength(byte[] gzipPayload) throws Exception {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(gzipPayload))) {
            return in.readAllBytes().length;
        }
    }
}

package cn.cubegarden.icbm.caching.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.client.ControlChannel;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * slave 复制客户端集成测试（假 master：明文 socket + 帧协议）。
 *
 * <p>覆盖：HELLO → SUBSCRIBE(ackedSequence) → WORLD_EPOCH/INVALIDATE/SNAPSHOT_PUSH
 * 应用与 ACK、落后（required&gt;committed）闸门拒绝、断线 fail closed。
 */
class SlaveReplicationClientTest {

    private static final DimensionRef DIM = DimensionRef.of("minecraft", "overworld");
    private static final ChunkRef KEY = ChunkRef.of(DIM, 1, 2);

    @TempDir
    Path root;

    private static byte[] gzip(String content) throws Exception {
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

    private static Envelope readFrame(DataInputStream in) throws Exception {
        int length = in.readInt();
        byte[] frame = new byte[length];
        in.readFully(frame);
        return FrameCodec.decodeFrame(frame);
    }

    private static void writeFrame(DataOutputStream out, Envelope envelope) throws Exception {
        byte[] frame = FrameCodec.encodeFrame(envelope);
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    /** 假 master：握手 + 读取 SUBSCRIBE + 推送一组事件。 */
    private static Socket handshakeAndSubscribe(ServerSocket server, List<String> serverSeen) throws Exception {
        Socket socket = server.accept();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        Envelope hello = readFrame(in);
        assertEquals(MessageType.HELLO, hello.messageType());
        writeFrame(out, Envelope.of(MessageType.HELLO_ACK, hello.requestId(), "master-1", 0, 1,
                System.currentTimeMillis(), ControlChannel.encode(
                MessageType.HELLO_ACK, new Messages.HelloAck(true, "", "master-1"))));
        Envelope subscribe = readFrame(in);
        assertEquals(MessageType.SUBSCRIBE, subscribe.messageType());
        Messages.Subscribe sub = ControlChannel.decode(MessageType.SUBSCRIBE, subscribe.body());
        serverSeen.add("subscribe:acked=" + sub.ackedSequence());
        return socket;
    }

    private SlaveReplicationClient client(int port, SnapshotObjectStore store, ServeGate gate,
                                          List<String> events) {
        return new SlaveReplicationClient("127.0.0.1", port, "slave-a", "26.3", 1073742154,
                "0.19.3", "0.157.1+26.3", "modpack-hash", "registry-hash", null, 5000, 100,
                store, gate, new DimensionRef[] {DIM}, 2);
    }

    @Test
    void appliesPushesAndGatesServing() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<String> serverSeen = new CopyOnWriteArrayList<>();
            Thread masterThread = new Thread(() -> {
                try {
                    Socket socket = handshakeAndSubscribe(server, serverSeen);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    // 推送：WORLD_EPOCH(1) → INVALIDATE(5) → SNAPSHOT_PUSH(5, epoch 1)
                    writeFrame(out, Envelope.of(MessageType.WORLD_EPOCH, 0, "master-1", 1, 1,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.WORLD_EPOCH,
                            new Messages.WorldEpoch(DIM, 1, cn.cubegarden.icbm.caching.protocol.EpochReason.START,
                                    "registry-hash"))));
                    writeFrame(out, Envelope.of(MessageType.INVALIDATE, 0, "master-1", 1, 2,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.INVALIDATE,
                            new Messages.Invalidate(DIM, 1, 2, 5,
                                    cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE))));
                    byte[] payload = gzip("data-5");
                    writeFrame(out, Envelope.of(MessageType.SNAPSHOT_PUSH, 0, "master-1", 1, 3,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.SNAPSHOT_PUSH,
                            new Messages.SnapshotPush(new Messages.PutSnapshot(DIM, 1, 2, 5,
                                    ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                                    ProtocolConstants.COMPRESSION_GZIP,
                                    6, sha(payload), payload)))));
                    Thread.sleep(400); // 让测试先观察到已提交且不落后
                    // 再推 INVALIDATE(6)（快照未到 → 落后）
                    writeFrame(out, Envelope.of(MessageType.INVALIDATE, 0, "master-1", 1, 4,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.INVALIDATE,
                            new Messages.Invalidate(DIM, 1, 2, 6,
                                    cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE))));
                    Thread.sleep(500);
                    socket.close();
                } catch (Exception ignored) {
                }
            });
            masterThread.setDaemon(true);
            masterThread.start();

            SnapshotObjectStore store = new SnapshotObjectStore(this.root);
            store.recover();
            ServeGate gate = new ServeGate(store);
            SlaveReplicationClient client = client(server.getLocalPort(), store, gate, new CopyOnWriteArrayList<>());
            client.start();

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && (store.indexOf(KEY) == null
                    || store.indexOf(KEY).committed() < 5
                    || store.indexOf(KEY).required() < 5)) {
                Thread.sleep(20);
            }
            assertEquals(5, store.indexOf(KEY).committed(), "SNAPSHOT_PUSH 必须装载到 committed");
            assertEquals(1, store.indexOf(KEY).committedEpoch(), "快照 epoch 必须来自推送包络");
            assertEquals(ServeGate.Status.SERVABLE, gate.status(KEY), "已提交且不落后 → 可服务");

            // master 已失效 required=6 但快照未到 → 落后
            deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && store.indexOf(KEY).required() < 6) {
                Thread.sleep(20);
            }
            assertEquals(ServeGate.Status.STALE, gate.status(KEY),
                    "落后 slave 不得服务低于 master requiredRevision 的区块");
            assertTrue(serverSeen.contains("subscribe:acked=0"), "订阅必须携带 ackedSequence");
            client.close();
        }
    }

    @Test
    void disconnectedFailsClosed() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread masterThread = new Thread(() -> {
                try {
                    Socket socket = handshakeAndSubscribe(server, new CopyOnWriteArrayList<>());
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    byte[] payload = gzip("data-5");
                    writeFrame(out, Envelope.of(MessageType.WORLD_EPOCH, 0, "master-1", 1, 1,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.WORLD_EPOCH,
                            new Messages.WorldEpoch(DIM, 1, cn.cubegarden.icbm.caching.protocol.EpochReason.START,
                                    "registry-hash"))));
                    writeFrame(out, Envelope.of(MessageType.INVALIDATE, 0, "master-1", 1, 2,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.INVALIDATE,
                            new Messages.Invalidate(DIM, 1, 2, 5,
                                    cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE))));
                    byte[] payload2 = gzip("data-5");
                    writeFrame(out, Envelope.of(MessageType.SNAPSHOT_PUSH, 0, "master-1", 1, 3,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.SNAPSHOT_PUSH,
                            new Messages.SnapshotPush(new Messages.PutSnapshot(DIM, 1, 2, 5,
                                    ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                                    ProtocolConstants.COMPRESSION_GZIP,
                                    6, sha(payload2), payload2)))));
                    Thread.sleep(300);
                    socket.close(); // master 掉线
                    Thread.sleep(200);
                } catch (Exception ignored) {
                }
            });
            masterThread.setDaemon(true);
            masterThread.start();

            SnapshotObjectStore store = new SnapshotObjectStore(this.root);
            store.recover();
            ServeGate gate = new ServeGate(store);
            SlaveReplicationClient client = client(server.getLocalPort(), store, gate, new CopyOnWriteArrayList<>());
            client.start();

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && (store.indexOf(KEY) == null || store.indexOf(KEY).committed() < 5)) {
                Thread.sleep(20);
            }
            assertEquals(5, store.indexOf(KEY).committed());
            // master 掉线后：即使本地有 committed 也拒绝服务（fail closed）
            Thread.sleep(500);
            assertEquals(ServeGate.Status.UNAVAILABLE, gate.status(KEY),
                    "控制服务不可用时拒绝不确定基线");
            client.close();
        }
    }

    @Test
    void stalePushDoesNotLowerRevision() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread masterThread = new Thread(() -> {
                try {
                    Socket socket = handshakeAndSubscribe(server, new CopyOnWriteArrayList<>());
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    writeFrame(out, Envelope.of(MessageType.INVALIDATE, 0, "master-1", 1, 1,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.INVALIDATE,
                            new Messages.Invalidate(DIM, 1, 2, 5,
                                    cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE))));
                    byte[] payload = gzip("data-5");
                    // 正常推送 rev5
                    writeFrame(out, Envelope.of(MessageType.SNAPSHOT_PUSH, 0, "master-1", 1, 2,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.SNAPSHOT_PUSH,
                            new Messages.SnapshotPush(new Messages.PutSnapshot(DIM, 1, 2, 5,
                                    ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                                    ProtocolConstants.COMPRESSION_GZIP,
                                    6, sha(payload), payload)))));
                    Thread.sleep(200);
                    // 重放低 revision 推送（乱序/重放）：不得降低或覆盖
                    byte[] evil = gzip("evil-data");
                    writeFrame(out, Envelope.of(MessageType.SNAPSHOT_PUSH, 0, "master-1", 1, 3,
                            System.currentTimeMillis(), ControlChannel.encode(MessageType.SNAPSHOT_PUSH,
                            new Messages.SnapshotPush(new Messages.PutSnapshot(DIM, 1, 2, 5,
                                    ProtocolConstants.SNAPSHOT_FORMAT_NBT_CHUNK_V1,
                                    ProtocolConstants.COMPRESSION_GZIP,
                                    9, sha(evil), evil)))));
                    Thread.sleep(300);
                    socket.close();
                } catch (Exception ignored) {
                }
            });
            masterThread.setDaemon(true);
            masterThread.start();

            SnapshotObjectStore store = new SnapshotObjectStore(this.root);
            store.recover();
            ServeGate gate = new ServeGate(store);
            SlaveReplicationClient client = client(server.getLocalPort(), store, gate, new CopyOnWriteArrayList<>());
            client.start();

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && store.indexOf(KEY) == null) {
                Thread.sleep(20);
            }
            Thread.sleep(300);
            assertEquals(5, store.indexOf(KEY).committed(), "重放不得降低 committed");
            // committed 对象仍是原始 payload（重放守卫拒收异 hash 覆盖）
            byte[] stored = store.loadCommittedPayload(KEY);
            assertTrue(stored != null && !java.util.Arrays.equals(stored, gzip("evil-data")),
                    "重放不得覆盖已提交对象");
            client.close();
        }
    }
}

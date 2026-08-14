package cn.cubegarden.icbm.caching.source.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * 远端 sink 集成测试（假控制服务：明文 socket + 帧协议）。
 *
 * <p>覆盖：HELLO 指纹、INVALIDATE→PUT_SNAPSHOT 顺序与 ACK 回调、断线重连补发、
 * sink 不可用时 fail closed。
 */
class RemoteSnapshotSinkTest {

    private static final ChunkKey KEY = ChunkKey.of(Identifier.fromNamespaceAndPath("minecraft", "overworld"), 3, -1);
    private static final ChunkKey KEY2 = ChunkKey.of(Identifier.fromNamespaceAndPath("minecraft", "overworld"), 4, 5);

    private static ChunkSnapshot snapshot(long revision) {
        return ChunkSnapshot.of(KEY, revision, 1, new byte[] {1, 2, 3}, 3);
    }

    private RemoteSnapshotSink sink(int port, List<String> events) {
        RemoteSnapshotSink sink = new RemoteSnapshotSink(
                "127.0.0.1", port, "test-source", "26.3", 1073742154, "0.19.3", "0.157.1+26.3",
                "modpack-hash", "registry-hash", null, 5000, 100);
        return sink;
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

    /** 假控制服务：接受一次连接，读取 HELLO 与消息，按需回复 ACK。 */
    private static Socket acceptHandshake(ServerSocket server) throws Exception {
        Socket socket = server.accept();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        Envelope hello = readFrame(in);
        assertEquals(MessageType.HELLO, hello.messageType());
        Messages.Hello helloBody = cn.cubegarden.icbm.caching.protocol.client.ControlChannel.decode(
                MessageType.HELLO, hello.body());
        assertEquals("modpack-hash", helloBody.modpackFingerprint());
        assertEquals("registry-hash", helloBody.registryAndDatapackFingerprint());
        writeFrame(out, Envelope.of(MessageType.HELLO_ACK, hello.requestId(), "master-1", 0, 1,
                System.currentTimeMillis(), cn.cubegarden.icbm.caching.protocol.client.ControlChannel.encode(
                MessageType.HELLO_ACK, new Messages.HelloAck(true, "", "master-1"))));
        return socket;
    }

    @Test
    void invalidatePutOrderAndAckCallbacks() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<String> serverSeen = new CopyOnWriteArrayList<>();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = acceptHandshake(server)) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    for (int i = 0; i < 2; i++) {
                        Envelope msg = readFrame(in);
                        serverSeen.add(msg.messageType().name());
                        if (msg.messageType() == MessageType.PUT_SNAPSHOT) {
                            writeFrame(out, Envelope.of(MessageType.PUT_SNAPSHOT_ACK, msg.requestId(),
                                    "master-1", 0, 100 + i, System.currentTimeMillis(),
                                    cn.cubegarden.icbm.caching.protocol.client.ControlChannel.encode(
                                            MessageType.PUT_SNAPSHOT_ACK,
                                            new Messages.PutSnapshotAck(
                                                    cn.cubegarden.icbm.caching.protocol.DimensionRef.of("minecraft", "overworld"),
                                                    3, -1, 5, cn.cubegarden.icbm.caching.protocol.PutResult.OK))));
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            RemoteSnapshotSink sink = sink(server.getLocalPort(), new CopyOnWriteArrayList<>());
            sink.start();
            // 等待连接完成
            Thread.sleep(300);

            sink.raiseRequiredRevision(KEY, 5);
            CompletableFuture<SnapshotSink.PutResult> putDone = new CompletableFuture<>();
            CompletableFuture<Boolean> commitDone = new CompletableFuture<>();
            sink.putSnapshot(snapshot(5), (key, revision, result) -> putDone.complete(result));
            sink.commitRevision(KEY, 5, (key, revision, ok) -> commitDone.complete(ok));

            assertEquals(SnapshotSink.PutResult.OK, putDone.get(5, TimeUnit.SECONDS));
            assertTrue(commitDone.get(5, TimeUnit.SECONDS), "服务端随 PUT 原子提交后 commit 必须成功");
            serverThread.join(3000);
            assertEquals(List.of("INVALIDATE", "PUT_SNAPSHOT"), serverSeen, "顺序必须 INVALIDATE → PUT_SNAPSHOT");
            sink.close();
        }
    }

    @Test
    void notConnectedFailsClosed() {
        RemoteSnapshotSink sink = sink(1, new CopyOnWriteArrayList<>()); // 端口 1 不可达
        assertFalse(sink.available(), "未连接时不可用（fail closed）");
        sink.close();
    }

    @Test
    void disconnectedReplaysOutstandingOps() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<String> serverSeen = new CopyOnWriteArrayList<>();
            // 第一次连接：握手后立即断开 → 触发重连；第二次连接：收到补发
            Thread serverThread = new Thread(() -> {
                try {
                    Socket first = acceptHandshake(server);
                    first.close(); // 强制断开
                    Socket second = acceptHandshake(server);
                    DataInputStream in = new DataInputStream(second.getInputStream());
                    DataOutputStream out = new DataOutputStream(second.getOutputStream());
                    // 重连后补发顺序：INVALIDATE → PUT_SNAPSHOT
                    for (int i = 0; i < 2; i++) {
                        Envelope msg = readFrame(in);
                        serverSeen.add(msg.messageType().name());
                        if (msg.messageType() == MessageType.PUT_SNAPSHOT) {
                            writeFrame(out, Envelope.of(MessageType.PUT_SNAPSHOT_ACK, msg.requestId(),
                                    "master-1", 0, 200, System.currentTimeMillis(),
                                    cn.cubegarden.icbm.caching.protocol.client.ControlChannel.encode(
                                            MessageType.PUT_SNAPSHOT_ACK,
                                            new Messages.PutSnapshotAck(
                                                    cn.cubegarden.icbm.caching.protocol.DimensionRef.of("minecraft", "overworld"),
                                                    3, -1, 5, cn.cubegarden.icbm.caching.protocol.PutResult.OK))));
                            break;
                        }
                    }
                    Thread.sleep(300);
                    second.close();
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            RemoteSnapshotSink sink = sink(server.getLocalPort(), new CopyOnWriteArrayList<>());
            sink.start();
            Thread.sleep(300);

            sink.raiseRequiredRevision(KEY, 5);
            CompletableFuture<SnapshotSink.PutResult> putDone = new CompletableFuture<>();
            sink.putSnapshot(snapshot(5), (key, revision, result) -> putDone.complete(result));
            assertEquals(SnapshotSink.PutResult.OK, putDone.get(8, TimeUnit.SECONDS), "断线后重连必须补发并成功");
            serverThread.join(3000);
            assertFalse(serverSeen.isEmpty(), "重连后必须收到补发的消息");
            sink.close();
        }
    }

}

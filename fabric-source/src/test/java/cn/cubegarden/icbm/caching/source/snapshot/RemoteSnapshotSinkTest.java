package cn.cubegarden.icbm.caching.source.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.client.EncapsulatedChannelClient;
import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * 远端 sink 集成测试（里程碑 3：velocity 授信模式，master 主动连接 backend 端口；
 * 假 master = 封装通道客户端 SERVER_HELLO 方向）。
 *
 * <p>覆盖：AUTH（forwarding secret）→ HELLO（SOURCE + 指纹）→ HELLO_ACK；
 * INVALIDATE→PUT_SNAPSHOT 顺序与 ACK 回调；master 断开重连后补发；
 * sink 未连接时 fail closed。
 */
class RemoteSnapshotSinkTest {

    private static final ChunkKey KEY = ChunkKey.of(Identifier.fromNamespaceAndPath("minecraft", "overworld"), 3, -1);
    private static final String SECRET = "velocity-forwarding-secret";

    private static ChunkSnapshot snapshot(long revision) {
        return ChunkSnapshot.of(KEY, revision, 1, new byte[] {1, 2, 3}, 3);
    }

    private static RemoteSnapshotSink sink() throws Exception {
        RemoteSnapshotSink sink = new RemoteSnapshotSink(
                "127.0.0.1", 0, SECRET, "test-source", "lobby-1", "26.3", 1073742154, "0.19.3", "0.157.1+26.3",
                "modpack-hash", "registry-hash");
        sink.start();
        return sink;
    }

    /** 假 master：AUTH（forwarding secret）→ 等源服 HELLO → 验证 → HELLO_ACK → 收事件。 */
    private static final class FakeMaster implements AutoCloseable {
        final EncapsulatedChannelClient client;
        final List<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch putReceived = new CountDownLatch(1);
        final AtomicLong ackSeq = new AtomicLong(100);

        FakeMaster(int port, String secret) throws Exception {
            this(port, secret, "0102030405060708090a0b0c0d0e0f10");
        }

        FakeMaster(int port, String secret, String uuidHex) throws Exception {
            this.client = new EncapsulatedChannelClient("127.0.0.1", port, "master-1", uuidHex, secret,
                    null, EncapsulatedChannelClient.HandshakeMode.SERVER_HELLO,
                    5000, 100, false, 0, new EncapsulatedChannelClient.Listener() {
                        @Override
                        public boolean onServerHello(Envelope envelope, byte[] body) {
                            Messages.Hello hello = EncapsulatedChannelClient.decode(MessageType.HELLO, body);
                            if (hello.role() != Role.SOURCE || !"modpack-hash".equals(hello.modpackFingerprint())) {
                                FakeMaster.this.client.send(MessageType.HELLO_ACK, envelope.requestId(), 0,
                                        EncapsulatedChannelClient.encode(MessageType.HELLO_ACK,
                                                new Messages.HelloAck(false, "fingerprint 不一致", "master-1")));
                                return false;
                            }
                            FakeMaster.this.client.send(MessageType.HELLO_ACK, envelope.requestId(), 0,
                                    EncapsulatedChannelClient.encode(MessageType.HELLO_ACK,
                                            new Messages.HelloAck(true, "", "master-1")));
                            return true;
                        }

                        @Override
                        public void onConnected(String serverId) {
                            FakeMaster.this.connected.countDown();
                        }

                        @Override
                        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
                            FakeMaster.this.seen.add(type.name());
                            if (type == MessageType.PUT_SNAPSHOT) {
                                FakeMaster.this.putReceived.countDown();
                                FakeMaster.this.client.send(MessageType.PUT_SNAPSHOT_ACK, envelope.requestId(), 0,
                                        EncapsulatedChannelClient.encode(MessageType.PUT_SNAPSHOT_ACK,
                                                new Messages.PutSnapshotAck(
                                                        DimensionRef.of("minecraft", "overworld"),
                                                        3, -1, 5, PutResult.OK)));
                            }
                        }
                    });
            this.client.start();
        }

        void awaitConnected() throws Exception {
            assertTrue(this.connected.await(5, TimeUnit.SECONDS), "backend HELLO 握手失败");
        }

        @Override
        public void close() {
            this.client.close();
        }
    }

    @Test
    void invalidatePutOrderAndAckCallbacks() throws Exception {
        RemoteSnapshotSink sink = sink();
        try (FakeMaster master = new FakeMaster(sink.port(), SECRET)) {
            master.awaitConnected();
            Thread.sleep(200);

            sink.raiseRequiredRevision(KEY, 5);
            CompletableFuture<SnapshotSink.PutResult> putDone = new CompletableFuture<>();
            CompletableFuture<Boolean> commitDone = new CompletableFuture<>();
            sink.putSnapshot(snapshot(5), (key, revision, result) -> putDone.complete(result));
            sink.commitRevision(KEY, 5, (key, revision, ok) -> commitDone.complete(ok));

            assertEquals(SnapshotSink.PutResult.OK, putDone.get(5, TimeUnit.SECONDS));
            assertTrue(commitDone.get(5, TimeUnit.SECONDS), "master 随 PUT 原子提交后 commit 必须成功");
            Thread.sleep(300);
            assertEquals(List.of("INVALIDATE", "PUT_SNAPSHOT"), master.seen,
                    "顺序必须 INVALIDATE → PUT_SNAPSHOT");
        }
        sink.close();
    }

    @Test
    void notConnectedFailsClosed() {
        RemoteSnapshotSink sink = new RemoteSnapshotSink(
                "127.0.0.1", 1, SECRET, "test-source", "lobby-1", "26.3", 1073742154, "0.19.3",
                "0.157.1+26.3", "modpack-hash", "registry-hash");
        assertFalse(sink.available(), "未连接时不可用（fail closed）");
        sink.close();
    }

    @Test
    void wrongSecretAuthRejected() throws Exception {
        RemoteSnapshotSink sink = sink();
        // 错误 forwarding secret：AUTH 校验失败 → 源服拒绝，连接关闭
        try (EncapsulatedChannelClient bad = new EncapsulatedChannelClient("127.0.0.1", sink.port(),
                "evil-master", "0102030405060708090a0b0c0d0e0f10", "wrong-secret",
                null, EncapsulatedChannelClient.HandshakeMode.SERVER_HELLO,
                5000, 100, false, 0, new EncapsulatedChannelClient.Listener() {
                    @Override
                    public boolean onServerHello(Envelope envelope, byte[] body) {
                        return false;
                    }

                    @Override
                    public void onRejected(String reason) {
                        // 期望：AUTH 被拒（未到 HELLO 阶段）
                    }
                })) {
            bad.start();
            Thread.sleep(300);
            assertFalse(bad.isConnected(), "错误 secret 必须被拒（fail closed）");
        }
        sink.close();
    }

    @Test
    void masterDisconnectReconnectReplays() throws Exception {
        RemoteSnapshotSink sink = sink();
        sink.raiseRequiredRevision(KEY, 5);
        CompletableFuture<SnapshotSink.PutResult> putDone = new CompletableFuture<>();
        sink.putSnapshot(snapshot(5), (key, revision, result) -> putDone.complete(result));

        // 第一个 master：收到 INVALIDATE + PUT 并 ACK 后断开（PUT 已完成提交）
        try (FakeMaster first = new FakeMaster(sink.port(), SECRET)) {
            first.awaitConnected();
            assertTrue(first.putReceived.await(5, TimeUnit.SECONDS), "第一个 master 必须收到 PUT_SNAPSHOT");
            Thread.sleep(100);
        }
        // 第二个 master 重连：幂等补发 INVALIDATE；已 ACK 的 PUT 不重发
        try (FakeMaster second = new FakeMaster(sink.port(), SECRET)) {
            second.awaitConnected();
            Thread.sleep(300);
            assertEquals(SnapshotSink.PutResult.OK, putDone.get(8, TimeUnit.SECONDS),
                    "master 重连后必须补发并成功");
            assertEquals(List.of("INVALIDATE"), second.seen,
                    "已 ACK 的 PUT 不重发；INVALIDATE 幂等补发");
        }
        sink.close();
    }
}

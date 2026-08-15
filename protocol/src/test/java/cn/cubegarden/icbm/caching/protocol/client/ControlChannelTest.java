package cn.cubegarden.icbm.caching.protocol.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.Role;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ControlChannel 集成测试（假控制服务：明文 socket + 帧协议）。
 */
class ControlChannelTest {

    private record Handshake(Socket socket, DataInputStream in, DataOutputStream out) {
    }

    private static Handshake accept(Socket socket) throws Exception {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        return new Handshake(socket, in, out);
    }

    private static Envelope readFrame(DataInputStream in) throws Exception {
        int frameLength = in.readInt();
        byte[] frame = new byte[frameLength];
        in.readFully(frame);
        return FrameCodec.decodeFrame(frame);
    }

    private static void writeFrame(DataOutputStream out, Envelope envelope) throws Exception {
        byte[] frame = FrameCodec.encodeFrame(envelope);
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    private ControlChannel.HelloConfig helloConfig() {
        return new ControlChannel.HelloConfig(Role.SOURCE, "lobby-1", "26.3", 1073742154, "0.19.3",
                "0.157.1+26.3", "modpack-hash", "registry-hash", (byte) 0);
    }

    @Test
    void handshakeAndMessageExchange() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch connected = new CountDownLatch(1);
            List<MessageType> received = new ArrayList<>();
            List<String> events = new ArrayList<>();
            ControlChannel client = new ControlChannel("127.0.0.1", server.getLocalPort(),
                    "test-source", helloConfig(), null, 5000, 100, false,
                    new ControlChannel.Listener() {
                        @Override
                        public void onConnected(String serverId) {
                            events.add("connected:" + serverId);
                            connected.countDown();
                        }

                        @Override
                        public void onMessage(MessageType type, Envelope envelope, byte[] body) {
                            received.add(type);
                        }
                    });
            client.start();

            try (Socket socket = server.accept()) {
                Handshake hs = accept(socket);
                Envelope hello = readFrame(hs.in());
                assertEquals(MessageType.HELLO, hello.messageType());
                Messages.Hello helloBody = ControlChannel.decode(MessageType.HELLO, hello.body());
                assertEquals(Role.SOURCE, helloBody.role());
                assertEquals("lobby-1", helloBody.serverName());
                assertEquals("26.3", helloBody.minecraftVersion());
                writeFrame(hs.out(), Envelope.of(MessageType.HELLO_ACK, hello.requestId(), "master-1",
                        0, 1, System.currentTimeMillis(), ControlChannel.encode(
                        MessageType.HELLO_ACK, new Messages.HelloAck(true, "", "master-1"))));

                assertTrue(connected.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("connected:master-1"), events);

                // 客户端发送 INVALIDATE，服务端回 ERROR；验证客户端能收
                client.send(MessageType.INVALIDATE, 7, 1, ControlChannel.encode(MessageType.INVALIDATE,
                        new Messages.Invalidate(cn.cubegarden.icbm.caching.protocol.DimensionRef.of("minecraft", "overworld"),
                                3, -1, 5, cn.cubegarden.icbm.caching.protocol.InvalidateReason.BLOCK_STATE)));
                Envelope invalidate = readFrame(hs.in());
                assertEquals(MessageType.INVALIDATE, invalidate.messageType());
                assertEquals(7, invalidate.requestId());
                writeFrame(hs.out(), Envelope.of(MessageType.ACK, invalidate.requestId(), "master-1",
                        0, 2, System.currentTimeMillis(),
                        ControlChannel.encode(MessageType.ACK, new Messages.Ack(2))));
                Thread.sleep(200);
                assertEquals(List.of(MessageType.ACK), received);
            }
            client.close();
        }
    }

    @Test
    void helloRejectedNotifiesListenerAndStops() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch rejected = new CountDownLatch(1);
            List<String> reasons = new ArrayList<>();
            ControlChannel client = new ControlChannel("127.0.0.1", server.getLocalPort(),
                    "test-source", helloConfig(), null, 5000, 100, true,
                    new ControlChannel.Listener() {
                        @Override
                        public void onRejected(String reason) {
                            reasons.add(reason);
                            rejected.countDown();
                        }
                    });
            client.start();
            try (Socket socket = server.accept()) {
                Handshake hs = accept(socket);
                Envelope hello = readFrame(hs.in());
                writeFrame(hs.out(), Envelope.of(MessageType.HELLO_ACK, hello.requestId(), "master-1",
                        0, 1, System.currentTimeMillis(), ControlChannel.encode(
                        MessageType.HELLO_ACK, new Messages.HelloAck(false, "fingerprint 不一致", "master-1"))));
                assertTrue(rejected.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("fingerprint 不一致"), reasons);
                // 拒绝后不得自动重连：server.accept 必须超时（无第二次连接）
                server.setSoTimeout(400);
                assertThrows(java.net.SocketTimeoutException.class, server::accept);
            }
            client.close();
        }
    }

    @Test
    void autoReconnectRehandshakes() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch secondConnected = new CountDownLatch(1);
            List<String> events = new ArrayList<>();
            ControlChannel client = new ControlChannel("127.0.0.1", server.getLocalPort(),
                    "test-source", helloConfig(), null, 5000, 50, true,
                    new ControlChannel.Listener() {
                        @Override
                        public void onConnected(String serverId) {
                            events.add("connected:" + serverId);
                            if (events.size() == 2) {
                                secondConnected.countDown();
                            }
                        }
                    });
            client.start();

            // 第一次连接
            Socket first = server.accept();
            Handshake hs1 = accept(first);
            Envelope hello1 = readFrame(hs1.in());
            writeFrame(hs1.out(), Envelope.of(MessageType.HELLO_ACK, hello1.requestId(), "master-1",
                    0, 1, System.currentTimeMillis(), ControlChannel.encode(
                    MessageType.HELLO_ACK, new Messages.HelloAck(true, "", "master-1"))));
            // 服务端强制断开 → 客户端应自动重连并再次握手
            first.close();
            Socket second = server.accept();
            Handshake hs2 = accept(second);
            Envelope hello2 = readFrame(hs2.in());
            assertEquals(MessageType.HELLO, hello2.messageType());
            writeFrame(hs2.out(), Envelope.of(MessageType.HELLO_ACK, hello2.requestId(), "master-1",
                    0, 1, System.currentTimeMillis(), ControlChannel.encode(
                    MessageType.HELLO_ACK, new Messages.HelloAck(true, "", "master-1"))));
            assertTrue(secondConnected.await(5, TimeUnit.SECONDS));
            assertEquals(2, events.size());
            client.close();
        }
    }
}

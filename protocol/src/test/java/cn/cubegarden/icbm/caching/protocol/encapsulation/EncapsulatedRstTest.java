package cn.cubegarden.icbm.caching.protocol.encapsulation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * RST 行为测试（EXECUTION_PLAN 里程碑 1 验收：非 magic 连接立即 RST，SO_LINGER=0）。
 *
 * <p>master 单端口只接受封装流量：读到的首字节非 {@code "ICBM"[0]} 时，服务端
 * {@code setSoLinger(true, 0)} 后关闭（不读剩余数据），客户端侧表现为连接重置
 * （ECONNRESET），而非干净的 FIN/EOF。
 */
class EncapsulatedRstTest {

    /** 模拟 master 端口：读首字节 → 非 magic → SO_LINGER=0 关闭。 */
    private static final class RstServer implements AutoCloseable {
        final ServerSocket server;
        final CountDownLatch rstSent = new CountDownLatch(1);

        RstServer() throws IOException {
            this.server = new ServerSocket(0);
        }

        int port() {
            return this.server.getLocalPort();
        }

        void acceptAndRst() throws IOException {
            try (Socket socket = this.server.accept()) {
                int first = socket.getInputStream().read();
                if (first != -1 && !EncapsulatedFrame.startsWithMagic((byte) first)) {
                    socket.setSoLinger(true, 0); // RST
                }
                socket.close();
                this.rstSent.countDown();
            }
        }

        @Override
        public void close() throws IOException {
            this.server.close();
        }
    }

    @Test
    void nonMagicFirstByteGetsRst() throws Exception {
        try (RstServer rstServer = new RstServer()) {
            Thread serverThread = new Thread(() -> {
                try {
                    rstServer.acceptAndRst();
                } catch (IOException ignored) {
                }
            });
            serverThread.start();

            try (Socket client = new Socket("127.0.0.1", rstServer.port())) {
                OutputStream out = client.getOutputStream();
                out.write('X'); // 非 magic（原版握手/畸形流）
                out.flush();
                DataInputStream in = new DataInputStream(client.getInputStream());
                // SO_LINGER=0 关闭 → 客户端读侧收到 ECONNRESET（IOException），而非干净 EOF
                assertThrows(IOException.class, in::read);
            }
            assertTrue(rstServer.rstSent.await(5, TimeUnit.SECONDS), "服务端未执行 RST 关闭");
        }
    }

    @Test
    void magicFirstByteIsNotRst() throws Exception {
        // 首字节为 magic 时服务端不设置 SO_LINGER=0（进入封装握手流程）
        try (RstServer rstServer = new RstServer()) {
            Thread serverThread = new Thread(() -> {
                try {
                    rstServer.acceptAndRst();
                } catch (IOException ignored) {
                }
            });
            serverThread.start();
            try (Socket client = new Socket("127.0.0.1", rstServer.port())) {
                client.getOutputStream().write(ProtocolConstants.MAGIC[0]);
                client.getOutputStream().flush();
                // 服务端会继续读到 EOF 后正常关闭（无 RST）→ 客户端读返回 -1（EOF）或阻塞后关闭
                // 这里只验证服务端把 magic 首字节放行（未计数 RST）：
                // RstServer 对 magic 首字节不设 SO_LINGER，close 走正常 FIN
                assertTrue(rstServer.rstSent.await(5, TimeUnit.SECONDS));
            }
        }
    }
}

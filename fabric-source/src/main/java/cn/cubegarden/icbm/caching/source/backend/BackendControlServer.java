package cn.cubegarden.icbm.caching.source.backend;

import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.encapsulation.AuthToken;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedFrame;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedHandshake;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 源服侧 backend 监听器（草稿 backend-velocity-trust §3；对照 ServerConnectionListener
 * 服务端监听骨架 + 封装通道传输层，阻塞 socket 实现）。
 *
 * <p>master 主动连接本端口：首字节非 magic（原版握手/畸形/扫描）→ 立即 RST（SO_LINGER=0）；
 * magic 命中 → AUTH 帧（公共密码证明，**PSK = velocity forwarding secret**）→ 校验失败
 * AUTH_ACK(false) 关闭；成功 → AUTH_ACK(true)+serverNonce → {@link BackendControlSession}
 * （源服发 HELLO → 等 HELLO_ACK → ACTIVE 事件流）。
 *
 * <p>无玩家在线也可用：连接由 master 主动建立，不依赖玩家会话。
 */
public final class BackendControlServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackendControlServer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 新会话回调（同一时刻一个 master；新连接替换旧会话）。 */
    public interface ConnectionListener {
        /** 新会话（AUTH 校验通过，尚未握手）：调用方替换当前会话并注入会话回调。 */
        void onSession(BackendControlSession session);

        /** AUTH 校验失败（错误 forwarding secret）。 */
        void onAuthRejected(String reason);
    }

    private final String bindHost;
    private final int bindPort;
    private final byte[] forwardingSecret; // velocity forwarding secret（共享密钥）
    private final BackendControlSession.HelloConfig helloConfig;
    private final ConnectionListener connectionListener;

    /** AUTH 帧长度上限（明文握手帧 ~40 字节；未认证连接不得触发帧上限分配，A2 审计修复）。 */
    private static final int MAX_AUTH_FRAME_BYTES = 4096;

    /** 并发连接上限（防半开连接线程耗尽 DoS；A2 审计修复）。 */
    private static final int MAX_CONNECTIONS = 128;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService sessionPool;
    private final java.util.concurrent.Semaphore connectionSlots = new java.util.concurrent.Semaphore(MAX_CONNECTIONS);
    private final AtomicBoolean stopped = new AtomicBoolean();

    public BackendControlServer(String bindHost, int bindPort, String forwardingSecret,
                                BackendControlSession.HelloConfig helloConfig,
                                ConnectionListener connectionListener) {
        this.bindHost = bindHost;
        this.bindPort = bindPort;
        this.forwardingSecret = ProtocolConstants.utf8(forwardingSecret);
        this.helloConfig = helloConfig;
        this.connectionListener = connectionListener;
    }

    public void start() throws IOException {
        if (this.forwardingSecret.length == 0) {
            throw new IllegalStateException("backend forwardingSecret 不能为空（velocity 授信模式）");
        }
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(this.bindHost, this.bindPort));
        this.sessionPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "icbm-backend-session");
            t.setDaemon(true);
            return t;
        });
        this.acceptThread = new Thread(this::acceptLoop, "icbm-backend-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        LOGGER.info("[icbm-source] backend 监听 {}:{}（velocity 授信模式）",
                this.bindHost, this.serverSocket.getLocalPort());
    }

    public int port() {
        return this.serverSocket == null ? -1 : this.serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!this.stopped.get()) {
            try {
                Socket socket = this.serverSocket.accept();
                if (!this.connectionSlots.tryAcquire()) {
                    try {
                        socket.setSoLinger(true, 0);
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    LOGGER.warn("[icbm-source] backend 连接数达上限，拒绝: {}", socket.getRemoteSocketAddress());
                    continue;
                }
                this.sessionPool.execute(() -> {
                    try {
                        this.handleConnection(socket);
                    } finally {
                        this.connectionSlots.release();
                    }
                });
            } catch (IOException e) {
                if (!this.stopped.get()) {
                    LOGGER.warn("[icbm-source] backend accept 异常: {}", e.getMessage());
                }
            }
        }
    }

    /** 单连接：首字节判别 → AUTH 握手（PSK=forwarding secret）→ BackendControlSession。 */
    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout((int) ProtocolConstants.HELLO_TIMEOUT_MILLIS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            int first = in.read();
            if (first < 0) {
                socket.close();
                return;
            }
            if (!EncapsulatedFrame.startsWithMagic((byte) first)) {
                socket.setSoLinger(true, 0);
                socket.close();
                LOGGER.info("[icbm-source] 非 magic 连接被 RST: {}", socket.getRemoteSocketAddress());
                return;
            }
            byte[] rest = new byte[3];
            in.readFully(rest);
            if (rest[0] != 'C' || rest[1] != 'B' || rest[2] != 'M') {
                socket.setSoLinger(true, 0);
                socket.close();
                return;
            }
            long length = in.readInt() & 0xFFFFFFFFL;
            if (length < 8 || length > MAX_AUTH_FRAME_BYTES) {
                socket.setSoLinger(true, 0);
                socket.close();
                return;
            }
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            byte[] frame = new byte[8 + payload.length];
            frame[0] = 'I';
            frame[1] = 'C';
            frame[2] = 'B';
            frame[3] = 'M';
            frame[4] = (byte) (length >>> 24);
            frame[5] = (byte) (length >>> 16);
            frame[6] = (byte) (length >>> 8);
            frame[7] = (byte) length;
            System.arraycopy(payload, 0, frame, 8, payload.length);
            byte[] plaintext = EncapsulatedFrame.decodePlaintextPayload(frame);
            EncapsulatedHandshake.Auth auth = EncapsulatedHandshake.decodeAuth(plaintext);

            if (!AuthToken.verify(this.forwardingSecret, auth.uuid(), auth.clientNonce(), auth.authToken())) {
                byte[] ack = EncapsulatedFrame.encodePlaintextFrame(
                        EncapsulatedHandshake.encodeAuthAck(new EncapsulatedHandshake.AuthAck(
                                false, null, "backend auth failed")));
                socket.getOutputStream().write(ack);
                socket.getOutputStream().flush();
                socket.close();
                this.connectionListener.onAuthRejected("AUTH 校验失败（错误 forwarding secret）");
                LOGGER.warn("[icbm-source] backend AUTH 校验失败（UUID={}）",
                        java.util.HexFormat.of().formatHex(auth.uuid()));
                return;
            }
            byte[] serverNonce = new byte[ProtocolConstants.UUID_BYTES];
            RANDOM.nextBytes(serverNonce);
            byte[] ack = EncapsulatedFrame.encodePlaintextFrame(
                    EncapsulatedHandshake.encodeAuthAck(new EncapsulatedHandshake.AuthAck(true, serverNonce, "")));
            socket.getOutputStream().write(ack);
            socket.getOutputStream().flush();

            BackendControlSession session = new BackendControlSession(
                    socket, this.forwardingSecret, auth.uuid(), auth.clientNonce(), serverNonce,
                    this.helloConfig);
            this.connectionListener.onSession(session);
            session.serve(); // 阻塞读循环（本线程）
        } catch (IOException e) {
            LOGGER.debug("[icbm-source] backend 连接处理结束 {}: {}",
                    socket.getRemoteSocketAddress(), e.toString());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() {
        if (!this.stopped.compareAndSet(false, true)) {
            return;
        }
        if (this.serverSocket != null) {
            try {
                this.serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (this.acceptThread != null) {
            this.acceptThread.interrupt();
        }
        if (this.sessionPool != null) {
            this.sessionPool.shutdownNow();
        }
    }
}

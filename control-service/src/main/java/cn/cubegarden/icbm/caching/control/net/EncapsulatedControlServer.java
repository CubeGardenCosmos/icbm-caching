package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
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
 * master 单端口封装通道服务器（阶段1，草稿 encapsulated-channel §3；对照
 * ServerConnectionListener / Velocity ConnectionManager 骨架，阻塞 socket 实现）。
 *
 * <p>连接处理（每连接一线程）：
 * <ol>
 *   <li>读首字节：非 magic（"ICBM"）→ 立即 **RST**（SO_LINGER=0，不读剩余数据；
 *       原版握手/畸形/扫描流量全被重置）；</li>
 *   <li>magic 命中 → 读 AUTH 帧（明文）：校验公共密码证明 + UUID + clientNonce；</li>
 *   <li>校验失败 → AUTH_ACK(false)+reason 后关闭；成功 → AUTH_ACK(true)+serverNonce，
 *       HKDF 派生双向会话密钥，进入 ACTIVE 加密多路复用（{@link EncapsulatedControlSession}）。</li>
 * </ol>
 *
 * <p>握手超时（{@link ProtocolConstants#HELLO_TIMEOUT_MILLIS}）内未完成 AUTH → 关闭（fail closed）。
 */
public final class EncapsulatedControlServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EncapsulatedControlServer.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceConfig config;
    private final ControlService service;
    private final Replicator replicator;
    private final PeerRegistry registry;
    private final EncapsulatedControlSession.Listener sessionListener;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService sessionPool;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public EncapsulatedControlServer(ServiceConfig config, ControlService service,
                                     Replicator replicator, PeerRegistry registry,
                                     EncapsulatedControlSession.Listener sessionListener) {
        this.config = config;
        this.service = service;
        this.replicator = replicator;
        this.registry = registry;
        this.sessionListener = sessionListener;
    }

    public void start() throws IOException {
        byte[] psk = ProtocolConstants.utf8(this.config.psk());
        if (psk.length == 0) {
            throw new IllegalStateException("control.psk 不能为空（运维手册禁止弱公共密码）");
        }
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(this.config.host(), this.config.encapsulatedPort()));
        this.sessionPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "icbm-encapsulated-session");
            t.setDaemon(true);
            return t;
        });
        this.acceptThread = new Thread(this::acceptLoop, "icbm-encapsulated-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        LOGGER.info("[control] 封装通道监听 {}:{}", this.config.host(), this.serverSocket.getLocalPort());
    }

    public int port() {
        return this.serverSocket == null ? -1 : this.serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!this.stopped.get()) {
            try {
                Socket socket = this.serverSocket.accept();
                this.sessionPool.execute(() -> this.handleConnection(socket));
            } catch (IOException e) {
                if (!this.stopped.get()) {
                    LOGGER.warn("[control] 封装通道 accept 异常: {}", e.getMessage());
                }
            }
        }
    }

    /** 单连接：首字节判别 → AUTH 握手 → ACTIVE 会话（任何失败 → RST/关闭，fail closed）。 */
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
                // 畸形/原版握手/扫描：立即 RST（SO_LINGER=0），不读剩余数据
                socket.setSoLinger(true, 0);
                socket.close();
                LOGGER.info("[control] 非 magic 连接被 RST: {}", socket.getRemoteSocketAddress());
                return;
            }
            // magic 命中：读完整明文 AUTH 帧
            byte[] rest = new byte[3];
            in.readFully(rest);
            if (rest[0] != 'C' || rest[1] != 'B' || rest[2] != 'M') {
                socket.setSoLinger(true, 0);
                socket.close();
                return;
            }
            long length = in.readInt() & 0xFFFFFFFFL;
            if (length < 8 || length > ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES) {
                socket.setSoLinger(true, 0);
                socket.close();
                return;
            }
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            // 重建完整明文帧 [magic(4)][length(4)][payload] 供解码器校验
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

            byte[] psk = ProtocolConstants.utf8(this.config.psk());
            if (!AuthToken.verify(psk, auth.uuid(), auth.clientNonce(), auth.authToken())) {
                // 校验失败：AUTH_ACK(false) 后正常关闭（客户端可见拒绝原因）
                byte[] ack = EncapsulatedFrame.encodePlaintextFrame(
                        EncapsulatedHandshake.encodeAuthAck(new EncapsulatedHandshake.AuthAck(false, null, "auth failed")));
                socket.getOutputStream().write(ack);
                socket.getOutputStream().flush();
                socket.close();
                LOGGER.warn("[control] AUTH 校验失败（UUID={}）", hex(auth.uuid()));
                return;
            }
            byte[] serverNonce = new byte[ProtocolConstants.UUID_BYTES];
            RANDOM.nextBytes(serverNonce);
            byte[] ack = EncapsulatedFrame.encodePlaintextFrame(
                    EncapsulatedHandshake.encodeAuthAck(new EncapsulatedHandshake.AuthAck(true, serverNonce, "")));
            socket.getOutputStream().write(ack);
            socket.getOutputStream().flush();

            EncapsulatedControlSession session = new EncapsulatedControlSession(
                    socket, psk, auth.uuid(), auth.clientNonce(), serverNonce,
                    this.config, this.registry, this.service, this.replicator, this.sessionListener);
            session.serve(); // 阻塞读循环（本线程）
        } catch (IOException e) {
            LOGGER.debug("[control] 封装连接处理结束 {}: {}", socket.getRemoteSocketAddress(), e.toString());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
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

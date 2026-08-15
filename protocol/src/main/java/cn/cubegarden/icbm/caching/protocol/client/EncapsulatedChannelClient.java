package cn.cubegarden.icbm.caching.protocol.client;

import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.encapsulation.AuthToken;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedCipher;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedFrame;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedHandshake;
import cn.cubegarden.icbm.caching.protocol.encapsulation.StreamId;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 封装通道客户端基类（阻塞 socket + 公共密码，草稿 encapsulated-channel §3）。
 *
 * <p>职责（与 {@link ControlChannel} 对齐，但传输为单端口封装通道）：
 * <ol>
 *   <li>连接 → AUTH 握手（HMAC 公共密码证明 + UUID 明文 + clientNonce）→ 读 AUTH_ACK；</li>
 *   <li>HKDF 派生双向会话密钥（ChaCha20-Poly1305）；</li>
 *   <li>HELLO 握手（两种方向）：CLIENT_HELLO（本端发 HELLO，等 HELLO_ACK；slave/源服连
 *       master）或 SERVER_HELLO（等对端源服发 HELLO，回调验证后回 HELLO_ACK；master 连源服
 *       ——backend velocity 授信模式，草稿 backend-velocity-trust §3）；</li>
 *   <li>ACTIVE 后所有帧加密（streamId=CONTROL），包络序号自增；</li>
 *   <li>断线自动重连（退避）并回调 {@link Listener#onConnected} 供子类重放/重订阅。</li>
 * </ol>
 *
 * <p>HELLO 失败（accepted=false）→ {@link Listener#onRejected}，不再自动重连
 * （防 fingerprint 不一致循环）；AUTH 被拒（错误公共密码）同路径。
 */
public class EncapsulatedChannelClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EncapsulatedChannelClient.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** HELLO 握手方向：本端发（连 master）或对端发（master 连源服 backend）。 */
    public enum HandshakeMode {
        CLIENT_HELLO,
        SERVER_HELLO,
    }

    /** 通道事件回调（子类在重连后按需重放/重订阅）。 */
    public interface Listener {
        /** HELLO 握手成功（含重连后）。 */
        default void onConnected(String serverId) {
        }

        /** HELLO/AUTH 被拒绝（reason 非空）；连接随后关闭。 */
        default void onRejected(String reason) {
        }

        /**
         * SERVER_HELLO 模式：对端（源服）发来 HELLO（envelope/body 原始字节）。
         * 调用方验证后必须 {@code send(HELLO_ACK, requestId, ...)} 并返回是否接受；
         * 返回 false → 连接关闭（不进入 ACTIVE）。默认拒绝。
         */
        default boolean onServerHello(Envelope envelope, byte[] body) {
            return false;
        }

        /** 入站消息（body 为包络原始字节；子类按 type 解码）。 */
        default void onMessage(MessageType type, Envelope envelope, byte[] body) {
        }

        /** LOGIN_VERIFY 流（streamId=1）：master 回 LOGIN_VERIFY_RES 原始 body（无包络）。 */
        default void onLoginVerifyResponse(byte[] body) {
        }

        /** MC_PASSTHROUGH 流（streamId=2，权威会话）：对端明文 MC 报文原始 body
         *  （[流序号(8)][长度(4)][报文]，CACHE_PROTOCOL §1.1；字节透传不解析）。 */
        default void onMcPacket(byte[] body) {
        }

        /** 连接关闭（非拒绝路径）。 */
        default void onDisconnected(Throwable cause) {
        }
    }

    /** 握手参数（26.3 冻结值与指纹；serverName 为缓存隔离键）。 */
    public record HelloConfig(
            Role role,
            String serverName,
            String minecraftVersion,
            int runtimeProtocol,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String modpackFingerprint,
            String registryAndDatapackFingerprint,
            byte capabilities
    ) {
    }

    private final String host;
    private final int port;
    private final String sourceServerId;
    private final String uuidHex; // AUTH 身份（每 peer 固定；role 由 master 注册表决定）
    private final byte[] psk;
    private final HelloConfig helloConfig;
    private final HandshakeMode handshakeMode;
    private final long readTimeoutMillis;
    private final long reconnectBackoffMillis;
    private final boolean autoReconnect;
    private final Listener listener;

    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private volatile EncapsulatedCipher encryptCipher; // client→server
    private volatile EncapsulatedCipher decryptCipher; // server→client
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean handshakeRejected;
    private Thread readThread;
    private Thread connectThread;
    private volatile Thread heartbeatThread;
    private final long heartbeatIntervalMillis;

    public EncapsulatedChannelClient(
            String host,
            int port,
            String sourceServerId,
            String uuidHex,
            String psk,
            HelloConfig helloConfig,
            long readTimeoutMillis,
            long reconnectBackoffMillis,
            boolean autoReconnect,
            Listener listener
    ) {
        this(host, port, sourceServerId, uuidHex, psk, helloConfig, HandshakeMode.CLIENT_HELLO,
                readTimeoutMillis, reconnectBackoffMillis, autoReconnect, 0, listener);
    }

    public EncapsulatedChannelClient(
            String host,
            int port,
            String sourceServerId,
            String uuidHex,
            String psk,
            HelloConfig helloConfig,
            HandshakeMode handshakeMode,
            long readTimeoutMillis,
            long reconnectBackoffMillis,
            boolean autoReconnect,
            long heartbeatIntervalMillis,
            Listener listener
    ) {
        this.host = host;
        this.port = port;
        this.sourceServerId = sourceServerId;
        this.uuidHex = uuidHex;
        this.psk = psk.getBytes(StandardCharsets.UTF_8);
        this.helloConfig = helloConfig;
        this.handshakeMode = handshakeMode;
        this.readTimeoutMillis = readTimeoutMillis;
        this.reconnectBackoffMillis = reconnectBackoffMillis;
        this.autoReconnect = autoReconnect;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.listener = listener;
    }

    /** 异步启动连接线程（自动重连由 {@link #autoReconnect} 控制）。 */
    public void start() {
        this.connectThread = new Thread(this::connectLoop, "icbm-encap-client-" + this.sourceServerId);
        this.connectThread.setDaemon(true);
        this.connectThread.start();
    }

    private void connectLoop() {
        while (!this.closed && !this.handshakeRejected) {
            try {
                this.connectAndHandshake();
                return;
            } catch (IOException e) {
                if (this.closed) {
                    return;
                }
                LOGGER.warn("[icbm-encap] 连接失败 {}:{}: {}（{}ms 后重试）",
                        this.host, this.port, e.getMessage(), this.reconnectBackoffMillis);
                try {
                    Thread.sleep(this.reconnectBackoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 同步连接 + AUTH 握手 + HELLO（超时见 {@link ProtocolConstants#HELLO_TIMEOUT_MILLIS}）。 */
    public void connectAndHandshake() throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(this.host, this.port), (int) this.readTimeoutMillis);
        raw.setSoTimeout((int) this.readTimeoutMillis);
        raw.setTcpNoDelay(true);
        this.socket = raw;
        this.in = new DataInputStream(new BufferedInputStream(raw.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(raw.getOutputStream()));
        this.sequence.set(0);

        // AUTH：公共密码证明 + UUID 明文 + clientNonce（明文帧，仅握手期）
        byte[] uuid = HexFormat.of().parseHex(this.uuidHex);
        byte[] clientNonce = new byte[ProtocolConstants.UUID_BYTES];
        RANDOM.nextBytes(clientNonce);
        byte[] token = AuthToken.compute(this.psk, uuid, clientNonce);
        byte[] authFrame = EncapsulatedFrame.encodePlaintextFrame(
                EncapsulatedHandshake.encodeAuth(new EncapsulatedHandshake.Auth(token, uuid, clientNonce)));
        this.out.write(authFrame);
        this.out.flush();

        long deadline = System.currentTimeMillis() + ProtocolConstants.HELLO_TIMEOUT_MILLIS;
        EncapsulatedHandshake.AuthAck ack = null;
        while (System.currentTimeMillis() < deadline) {
            byte[] wireFrame = this.readWireFrame();
            byte[] plaintext = EncapsulatedFrame.decodePlaintextPayload(wireFrame);
            ack = EncapsulatedHandshake.decodeAuthAck(plaintext);
            break;
        }
        if (ack == null) {
            throw new IOException("AUTH 握手超时");
        }
        if (!ack.accepted()) {
            this.handshakeRejected = true;
            this.listener.onRejected(ack.reason());
            this.closeSocket();
            return;
        }
        EncapsulatedCipher.SessionKeys keys =
                EncapsulatedCipher.SessionKeys.derive(this.psk, clientNonce, ack.serverNonce(), uuid);
        this.encryptCipher = keys.clientToServer();
        this.decryptCipher = keys.serverToClient();

        if (this.handshakeMode == HandshakeMode.SERVER_HELLO) {
            this.awaitServerHello();
            return;
        }
        this.sendHandshake();
        deadline = System.currentTimeMillis() + ProtocolConstants.HELLO_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Envelope reply = this.readControlFrame();
            if (reply.messageType() == MessageType.HELLO_ACK) {
                Messages.HelloAck helloAck = MessageBodyCodec.decode(MessageType.HELLO_ACK,
                        new DataInputStream(new java.io.ByteArrayInputStream(reply.body())));
                if (helloAck == null) {
                    throw new IOException("HELLO_ACK 无法解析");
                }
                if (!helloAck.accepted()) {
                    this.handshakeRejected = true;
                    this.listener.onRejected(helloAck.reason());
                    this.closeSocket();
                    return;
                }
                this.listener.onConnected(helloAck.serverId());
                this.startReader();
                return;
            }
        }
        throw new IOException("HELLO 握手超时");
    }

    /** SERVER_HELLO 模式：等对端（源服）发 HELLO，回调验证并回 HELLO_ACK 后进入 ACTIVE。 */
    private void awaitServerHello() throws IOException {
        long deadline = System.currentTimeMillis() + ProtocolConstants.HELLO_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Envelope hello = this.readControlFrame();
            if (hello.messageType() == MessageType.HELLO) {
                boolean accepted = this.listener.onServerHello(hello, hello.body());
                if (!accepted) {
                    this.handshakeRejected = true;
                    this.listener.onRejected("backend HELLO 验证失败");
                    this.closeSocket();
                    return;
                }
                this.listener.onConnected(hello.sourceServerId());
                this.startReader();
                return;
            }
        }
        throw new IOException("backend HELLO 握手超时");
    }

    private void sendHandshake() throws IOException {
        byte[] body = encode(MessageType.HELLO, new Messages.Hello(
                this.helloConfig.role(),
                this.helloConfig.serverName(),
                this.helloConfig.minecraftVersion(),
                this.helloConfig.runtimeProtocol(),
                this.helloConfig.fabricLoaderVersion(),
                this.helloConfig.fabricApiVersion(),
                this.helloConfig.modpackFingerprint(),
                this.helloConfig.registryAndDatapackFingerprint(),
                this.helloConfig.capabilities()
        ));
        this.writeFrame(MessageType.HELLO, 1, 0, body);
    }

    private void startReader() {
        this.readThread = new Thread(this::readLoop, "icbm-encap-reader-" + this.sourceServerId);
        this.readThread.setDaemon(true);
        this.readThread.start();
        if (this.heartbeatIntervalMillis > 0) {
            this.heartbeatThread = new Thread(this::heartbeatLoop, "icbm-encap-heartbeat-" + this.sourceServerId);
            this.heartbeatThread.setDaemon(true);
            this.heartbeatThread.start();
        }
    }

    /** 定期 HEARTBEAT（对端读超时防误断；HEARTBEAT 不受序号递增约束，seq=0）。 */
    private void heartbeatLoop() {
        while (!this.closed && this.socket != null && !this.socket.isClosed()) {
            try {
                Thread.sleep(this.heartbeatIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                synchronized (this.out) {
                    this.writeFrame(MessageType.HEARTBEAT, 0, 0,
                            encode(MessageType.HEARTBEAT,
                                    new Messages.Heartbeat(System.currentTimeMillis(), 0)));
                }
            } catch (IOException e) {
                LOGGER.debug("[icbm-encap] 心跳发送失败（连接将自愈）: {}", e.getMessage());
            }
        }
    }

    private void readLoop() {
        try {
            while (!this.closed) {
                byte[] wireFrame = this.readWireFrame();
                EncapsulatedFrame.Decrypted decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.decryptCipher, wireFrame);
                switch (decrypted.stream()) {
                    case CONTROL -> {
                        Envelope envelope = FrameCodec.decodeFrame(decrypted.body());
                        this.listener.onMessage(envelope.messageType(), envelope, envelope.body());
                    }
                    case LOGIN_VERIFY -> this.listener.onLoginVerifyResponse(decrypted.body());
                    case MC_PASSTHROUGH -> this.listener.onMcPacket(decrypted.body());
                }
            }
        } catch (IOException e) {
            if (this.closed) {
                return;
            }
            LOGGER.warn("[icbm-encap] 连接中断 {}:{}: {}", this.host, this.port, e.getMessage());
            this.listener.onDisconnected(e);
            this.closeSocket();
            if (this.autoReconnect && !this.closed && !this.handshakeRejected) {
                this.connectLoop();
            }
        }
    }

    private Envelope readControlFrame() throws IOException {
        byte[] wireFrame = this.readWireFrame();
        EncapsulatedFrame.Decrypted decrypted;
        try {
            decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.decryptCipher, wireFrame);
        } catch (IOException e) {
            throw new IOException("encrypted frame rejected: " + e.getMessage(), e);
        }
        if (decrypted.stream() != StreamId.CONTROL) {
            throw new IOException("非控制流帧: " + decrypted.stream());
        }
        return FrameCodec.decodeFrame(decrypted.body());
    }

    /** 读一帧线上字节：[magic(4)][length(4)][payload(length)]；长度校验失败 → IOException。 */
    private byte[] readWireFrame() throws IOException {
        DataInputStream in = this.in;
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'I' || magic[1] != 'C' || magic[2] != 'B' || magic[3] != 'M') {
            throw new IOException("bad magic");
        }
        long length = in.readInt() & 0xFFFFFFFFL;
        if (length < 8 || length > ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES) {
            throw new IOException("frame length out of range: " + length);
        }
        byte[] payload = new byte[(int) length];
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

    /** 发送消息（分配下一序号）。返回 false 表示未连接（调用方自行排队重试）。 */
    public boolean send(MessageType type, long requestId, long worldEpoch, byte[] body) {
        DataOutputStream out = this.out;
        if (out == null || !this.isConnected() || this.encryptCipher == null) {
            return false;
        }
        try {
            synchronized (out) {
                this.writeFrame(type, requestId, worldEpoch, body);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[icbm-encap] 发送失败: {}", e.getMessage());
            this.listener.onDisconnected(e);
            this.closeSocket();
            return false;
        }
    }

    /** 发送 MC_PASSTHROUGH 流（streamId=2）原始 body（无包络；权威会话明文 MC 报文）。 */
    public boolean sendMcPassthrough(byte[] body) {
        DataOutputStream out = this.out;
        if (out == null || !this.isConnected() || this.encryptCipher == null) {
            return false;
        }
        try {
            synchronized (out) {
                byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encryptCipher, StreamId.MC_PASSTHROUGH, body);
                out.write(frame);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[icbm-encap] MC_PASSTHROUGH 发送失败: {}", e.getMessage());
            this.listener.onDisconnected(e);
            return false;
        }
    }

    /** 发送 LOGIN_VERIFY 流（streamId=1）原始 body（无包络；登录验证材料递交）。 */
    public boolean sendLoginVerify(byte[] body) {
        DataOutputStream out = this.out;
        if (out == null || !this.isConnected() || this.encryptCipher == null) {
            return false;
        }
        try {
            synchronized (out) {
                byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encryptCipher, StreamId.LOGIN_VERIFY, body);
                out.write(frame);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[icbm-encap] LOGIN_VERIFY 发送失败: {}", e.getMessage());
            this.listener.onDisconnected(e);
            this.closeSocket();
            return false;
        }
    }

    private void writeFrame(MessageType type, long requestId, long worldEpoch, byte[] body) throws IOException {
        DataOutputStream out = this.out;
        long seq = this.sequence.incrementAndGet();
        Envelope envelope = Envelope.of(type, requestId, this.sourceServerId, worldEpoch, seq,
                System.currentTimeMillis(), body);
        byte[] frameBody = FrameCodec.encodeFrame(envelope);
        byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encryptCipher, StreamId.CONTROL, frameBody);
        out.write(frame);
        out.flush();
    }

    public boolean isConnected() {
        Socket s = this.socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    public String sourceServerId() {
        return this.sourceServerId;
    }

    private void closeSocket() {
        try {
            if (this.in != null) {
                this.in.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (this.out != null) {
                this.out.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.closeSocket();
        if (this.connectThread != null) {
            this.connectThread.interrupt();
        }
    }

    public static byte[] encode(MessageType type, Object message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            MessageBodyCodec.encode(type, out, message);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode failed", e);
        }
    }

    public static <T> T decode(MessageType type, byte[] body) {
        try {
            return MessageBodyCodec.decode(type, new DataInputStream(new java.io.ByteArrayInputStream(body)));
        } catch (IOException e) {
            throw new IllegalStateException("decode failed", e);
        }
    }
}

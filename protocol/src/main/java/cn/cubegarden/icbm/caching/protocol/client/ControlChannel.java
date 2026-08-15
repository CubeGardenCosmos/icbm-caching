package cn.cubegarden.icbm.caching.protocol.client;

import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.Role;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制通道客户端基类（阻塞 socket + mTLS，草稿 phase2-control-protocol §4）。
 *
 * <p>职责：连接/HELLO 握手（含版本与 fingerprint）、入站读线程与消息分发、
 * 每连接出站序号递增、断线自动重连（退避）并回调 {@link Listener#onConnected} 供
 * 子类重放/重订阅（源端 outbox、slave 重订阅）。
 *
 * <p>包络 sequence 在同一连接方向递增；HELLO 失败（accepted=false）→
 * {@link Listener#onRejected}，不再自动重连（防指纹不一致循环）。
 */
public class ControlChannel implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlChannel.class);

    /** 通道事件回调（子类在重连后按需重放/重订阅）。 */
    public interface Listener {
        /** HELLO 握手成功（含重连后）。 */
        default void onConnected(String serverId) {
        }

        /** HELLO 被拒绝（reason 非空）；连接随后关闭。 */
        default void onRejected(String reason) {
        }

        /** 入站消息（body 已解码前原始字节；子类按 type 解码）。 */
        default void onMessage(MessageType type, Envelope envelope, byte[] body) {
        }

        /** 连接关闭（非 HELLO 拒绝路径）。 */
        default void onDisconnected(Throwable cause) {
        }
    }

    /** 握手参数（26.3 冻结值与指纹，来自调用方；serverName 为缓存隔离键）。 */
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
    private final HelloConfig helloConfig;
    private final SSLContext sslContext; // null → 明文（仅测试）
    private final long readTimeoutMillis;
    private final long reconnectBackoffMillis;
    private final boolean autoReconnect;
    private final Listener listener;

    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean handshakeRejected;
    private Thread readThread;
    private Thread connectThread;

    public ControlChannel(
            String host,
            int port,
            String sourceServerId,
            HelloConfig helloConfig,
            SSLContext sslContext,
            long readTimeoutMillis,
            long reconnectBackoffMillis,
            boolean autoReconnect,
            Listener listener
    ) {
        this.host = host;
        this.port = port;
        this.sourceServerId = sourceServerId;
        this.helloConfig = helloConfig;
        this.sslContext = sslContext;
        this.readTimeoutMillis = readTimeoutMillis;
        this.reconnectBackoffMillis = reconnectBackoffMillis;
        this.autoReconnect = autoReconnect;
        this.listener = listener;
    }

    /** 异步启动连接线程（自动重连由 {@link #autoReconnect} 控制）。 */
    public void start() {
        this.connectThread = new Thread(this::connectLoop, "icbm-control-client-" + this.sourceServerId);
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
                LOGGER.warn("[icbm-control] 连接失败 {}:{}: {}（{}ms 后重试）",
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

    /** 同步连接 + HELLO 握手（超时见 {@link ProtocolConstants#HELLO_TIMEOUT_MILLIS}）。 */
    public void connectAndHandshake() throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(this.host, this.port), (int) this.readTimeoutMillis);
        raw.setSoTimeout((int) this.readTimeoutMillis);
        raw.setTcpNoDelay(true);
        Socket connected = raw;
        if (this.sslContext != null) {
            SSLSocket ssl = (SSLSocket) this.sslContext.getSocketFactory()
                    .createSocket(raw, this.host, this.port, true);
            ssl.startHandshake();
            ssl.setSoTimeout((int) this.readTimeoutMillis);
            connected = ssl;
        }
        this.socket = connected;
        this.in = new DataInputStream(new BufferedInputStream(connected.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(connected.getOutputStream()));
        this.sequence.set(0);
        this.sendHandshake();
        long deadline = System.currentTimeMillis() + ProtocolConstants.HELLO_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Envelope reply = this.readFrame();
            if (reply.messageType() == MessageType.HELLO_ACK) {
                Messages.HelloAck ack = MessageBodyCodec.decode(MessageType.HELLO_ACK,
                        new DataInputStream(new java.io.ByteArrayInputStream(reply.body())));
                if (ack == null) {
                    throw new IOException("HELLO_ACK 无法解析");
                }
                if (!ack.accepted()) {
                    this.handshakeRejected = true;
                    this.listener.onRejected(ack.reason());
                    this.closeSocket();
                    return;
                }
                this.listener.onConnected(ack.serverId());
                this.startReader();
                return;
            }
        }
        throw new IOException("HELLO 握手超时");
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
        this.readThread = new Thread(this::readLoop, "icbm-control-reader-" + this.sourceServerId);
        this.readThread.setDaemon(true);
        this.readThread.start();
    }

    private void readLoop() {
        try {
            while (!this.closed) {
                Envelope envelope = this.readFrame();
                this.listener.onMessage(envelope.messageType(), envelope, envelope.body());
            }
        } catch (IOException e) {
            if (this.closed) {
                return;
            }
            LOGGER.warn("[icbm-control] 连接中断 {}:{}: {}", this.host, this.port, e.getMessage());
            this.listener.onDisconnected(e);
            this.closeSocket();
            if (this.autoReconnect && !this.closed && !this.handshakeRejected) {
                this.connectLoop();
            }
        }
    }

    private Envelope readFrame() throws IOException {
        int frameLength = this.in.readInt();
        if (frameLength < ProtocolConstants.MAGIC.length || frameLength > ProtocolConstants.MAX_FRAME_HARD_LIMIT + 64) {
            throw new IOException("frame length out of range: " + frameLength);
        }
        byte[] frame = new byte[frameLength];
        this.in.readFully(frame);
        return FrameCodec.decodeFrame(frame);
    }

    /** 发送消息（分配下一序号）。返回 false 表示未连接（调用方自行排队重试）。 */
    public boolean send(MessageType type, long requestId, long worldEpoch, byte[] body) {
        DataOutputStream out = this.out;
        if (out == null || !this.isConnected()) {
            return false;
        }
        try {
            synchronized (out) {
                this.writeFrame(type, requestId, worldEpoch, body);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[icbm-control] 发送失败: {}", e.getMessage());
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
        byte[] frame = FrameCodec.encodeFrame(envelope);
        out.writeInt(frame.length);
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

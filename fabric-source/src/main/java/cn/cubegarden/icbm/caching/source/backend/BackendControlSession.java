package cn.cubegarden.icbm.caching.source.backend;

import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageBodyCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Messages;
import cn.cubegarden.icbm.caching.protocol.ProtocolConstants;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedCipher;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedFrame;
import cn.cubegarden.icbm.caching.protocol.encapsulation.StreamId;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 源服侧 backend 会话（草稿 backend-velocity-trust §3；master 主动连接的受信长连接）。
 *
 * <p>生命周期（由 {@link BackendControlServer} 完成 AUTH 校验后创建）：
 * <ol>
 *   <li>源服（服务端）主动发 HELLO（role=SOURCE + serverName + 指纹 + runtimeProtocol）；</li>
 *   <li>等 HELLO_ACK：accepted → ACTIVE（{@link Listener#onConnected}）；rejected → 关闭并
 *       {@link Listener#onRejected}（fail closed，master 重试）；</li>
 *   <li>ACTIVE：源服经 {@link #send} 保序发送 WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT（包络序号
 *       自增，master 校验防重放）；收 PUT_SNAPSHOT_ACK/ERROR/HEARTBEAT → {@link Listener}。</li>
 * </ol>
 *
 * <p>方向与 slave 通道相反：HELLO 由服务端（源服）发出，客户端（master）验证；
 * 传输层（magic/AUTH/HKDF/ChaCha20-Poly1305/CONTROL 流）完全复用封装通道。
 */
public final class BackendControlSession implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackendControlSession.class);

    /** 会话事件（连接状态 + 入站消息；由 sink 实现）。 */
    public interface Listener {
        /** HELLO_ACK accepted：可开始发送事件。 */
        void onConnected();

        /** HELLO_ACK rejected / 协议违规：连接已关闭（fail closed）。 */
        void onRejected(String reason);

        /** 入站消息（PUT_SNAPSHOT_ACK / ERROR / HEARTBEAT）。 */
        void onMessage(MessageType type, Envelope envelope, byte[] body);

        /** MC_PASSTHROUGH 流（权威会话，streamId=2）：master 转发的 slave 明文 MC 报文
         *  （[流序号(8)][长度(4)][报文]，字节透传；虚拟玩家连接实现随阶段 6/7，默认丢弃）。 */
        default void onMcPassthrough(byte[] body) {
            LOGGER.warn("[icbm-source] MC_PASSTHROUGH 未服务（虚拟玩家连接未实现），丢弃 {} 字节", body.length);
        }

        /** 连接断开（对端关闭/IO 异常）。 */
        void onDisconnected(Throwable cause);
    }

    /** HELLO 内容（源服身份与指纹；serverName 为缓存隔离键）。 */
    public record HelloConfig(
            String sourceServerId,
            String serverName,
            String minecraftVersion,
            int runtimeProtocol,
            String fabricLoaderVersion,
            String fabricApiVersion,
            String modpackFingerprint,
            String registryAndDatapackFingerprint
    ) {
    }

    private final Socket socket;
    private final EncapsulatedCipher decryptCipher; // master→source（入站）
    private final EncapsulatedCipher encryptCipher; // source→master（出站）
    private final HelloConfig helloConfig;
    private volatile Listener listener; // 由连接方（RemoteSnapshotSink）经 onSession 注入
    private final String masterUuidHex;

    private final DataInputStream in;
    private final DataOutputStream out;
    private final AtomicLong sequence = new AtomicLong();
    private final long heartbeatIntervalMillis;
    private volatile boolean closed;
    private volatile boolean active;

    public BackendControlSession(Socket socket, byte[] psk, byte[] masterUuid,
                                 byte[] clientNonce, byte[] serverNonce,
                                 HelloConfig helloConfig) throws IOException {
        this(socket, psk, masterUuid, clientNonce, serverNonce, helloConfig, 15_000);
    }

    public BackendControlSession(Socket socket, byte[] psk, byte[] masterUuid,
                                 byte[] clientNonce, byte[] serverNonce,
                                 HelloConfig helloConfig, long heartbeatIntervalMillis) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        EncapsulatedCipher.SessionKeys keys =
                EncapsulatedCipher.SessionKeys.derive(psk, clientNonce, serverNonce, masterUuid);
        this.decryptCipher = keys.clientToServer();
        this.encryptCipher = keys.serverToClient();
        this.helloConfig = helloConfig;
        this.masterUuidHex = HexFormat.of().formatHex(masterUuid);
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    /** 由连接方注入会话回调（必须在 {@link #serve()} 前完成）。 */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return this.active;
    }

    /** HELLO 握手 + ACTIVE 读循环（每连接一线程）。 */
    public void serve() {
        try {
            this.sendHello();
            long deadline = System.currentTimeMillis() + ProtocolConstants.HELLO_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                Envelope reply = this.readControlFrame();
                if (reply.messageType() == MessageType.HELLO_ACK) {
                    Messages.HelloAck ack = decode(MessageType.HELLO_ACK, reply.body());
                    if (ack == null) {
                        this.listener.onRejected("HELLO_ACK 无法解析");
                        return;
                    }
                    if (!ack.accepted()) {
                        LOGGER.error("[icbm-source] backend HELLO 被拒（fail closed）: {}", ack.reason());
                        this.listener.onRejected(ack.reason());
                        return;
                    }
                    this.active = true;
                    this.listener.onConnected();
                    break;
                }
            }
            if (!this.active) {
                this.listener.onRejected("backend HELLO 握手超时");
                return;
            }
            this.startHeartbeat();
            while (!this.closed) {
                byte[] wireFrame = this.readWireFrame();
                EncapsulatedFrame.Decrypted decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.decryptCipher, wireFrame);
                if (decrypted.stream() == StreamId.CONTROL) {
                    Envelope envelope = FrameCodec.decodeFrame(decrypted.body());
                    this.listener.onMessage(envelope.messageType(), envelope, envelope.body());
                } else if (decrypted.stream() == StreamId.MC_PASSTHROUGH) {
                    this.listener.onMcPassthrough(decrypted.body());
                } else {
                    throw new IOException("未知流: " + decrypted.stream());
                }
            }
        } catch (EOFException e) {
            // 对端正常关闭
        } catch (IOException e) {
            if (!this.closed) {
                LOGGER.warn("[icbm-source] backend 会话 IO 异常 {}: {}", this.masterUuidHex, e.getMessage());
            }
        } finally {
            this.active = false;
            this.listener.onDisconnected(null);
            this.close();
        }
    }

    private void sendHello() throws IOException {
        byte[] body = encode(MessageType.HELLO, new Messages.Hello(
                Role.SOURCE,
                this.helloConfig.serverName(),
                this.helloConfig.minecraftVersion(),
                this.helloConfig.runtimeProtocol(),
                this.helloConfig.fabricLoaderVersion(),
                this.helloConfig.fabricApiVersion(),
                this.helloConfig.modpackFingerprint(),
                this.helloConfig.registryAndDatapackFingerprint(),
                (byte) 0
        ));
        this.writeFrame(MessageType.HELLO, 1, 0, body);
    }

    /** 发送 MC_PASSTHROUGH 流（streamId=2）：源服下行明文 MC 报文回传 master（权威会话）。 */
    public boolean sendMcPassthrough(byte[] body) {
        if (!this.isActive() || this.closed) {
            return false;
        }
        try {
            synchronized (this.out) {
                byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encryptCipher, StreamId.MC_PASSTHROUGH, body);
                this.out.write(frame);
                this.out.flush();
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[icbm-source] MC_PASSTHROUGH 发送失败: {}", e.getMessage());
            return false;
        }
    }

    /** 发送事件消息（出站序号自增；worldEpoch 为源侧当前 epoch）。 */
    public boolean send(MessageType type, long requestId, long worldEpoch, byte[] body) {
        if (!this.isActive() || this.closed) {
            return false;
        }
        try {
            synchronized (this.out) {
                this.writeFrame(type, requestId, worldEpoch, body);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[icbm-source] backend 发送失败: {}", e.getMessage());
            return false;
        }
    }

    /** 定期 HEARTBEAT（master 读超时误断防护；HEARTBEAT 不受序号递增约束，seq=0）。 */
    private void startHeartbeat() {
        Thread heartbeat = new Thread(() -> {
            while (!this.closed && this.active) {
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
                } catch (IOException ignored) {
                    return; // 连接已断开
                }
            }
        }, "icbm-backend-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    private void writeFrame(MessageType type, long requestId, long worldEpoch, byte[] body) throws IOException {
        long seq = this.sequence.incrementAndGet();
        Envelope envelope = Envelope.of(type, requestId, this.helloConfig.sourceServerId(),
                worldEpoch, seq, System.currentTimeMillis(), body);
        byte[] frameBody = FrameCodec.encodeFrame(envelope);
        byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encryptCipher, StreamId.CONTROL, frameBody);
        this.out.write(frame);
        this.out.flush();
    }

    /** 读一帧加密 CONTROL 流并解码为包络。 */
    private Envelope readControlFrame() throws IOException {
        byte[] wireFrame = this.readWireFrame();
        EncapsulatedFrame.Decrypted decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.decryptCipher, wireFrame);
        if (decrypted.stream() != StreamId.CONTROL) {
            throw new IOException("非控制流帧: " + decrypted.stream());
        }
        return FrameCodec.decodeFrame(decrypted.body());
    }

    private byte[] readWireFrame() throws IOException {
        byte[] magic = new byte[4];
        this.in.readFully(magic);
        if (magic[0] != 'I' || magic[1] != 'C' || magic[2] != 'B' || magic[3] != 'M') {
            throw new IOException("bad magic");
        }
        long length = this.in.readInt() & 0xFFFFFFFFL;
        if (length < 8 || length > ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES) {
            throw new IOException("frame length out of range: " + length);
        }
        byte[] payload = new byte[(int) length];
        this.in.readFully(payload);
        byte[] frame = new byte[8 + payload.length];
        System.arraycopy(magic, 0, frame, 0, 4);
        frame[4] = (byte) (length >>> 24);
        frame[5] = (byte) (length >>> 16);
        frame[6] = (byte) (length >>> 8);
        frame[7] = (byte) length;
        System.arraycopy(payload, 0, frame, 8, payload.length);
        return frame;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            this.socket.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] encode(MessageType type, Object message) {
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

    private static <T> T decode(MessageType type, byte[] body) {
        try {
            return MessageBodyCodec.decode(type, new DataInputStream(new java.io.ByteArrayInputStream(body)));
        } catch (IOException e) {
            throw new IllegalStateException("decode failed", e);
        }
    }
}

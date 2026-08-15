package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.control.config.ServiceConfig;
import cn.cubegarden.icbm.caching.control.replication.Replicator;
import cn.cubegarden.icbm.caching.control.service.ControlService;
import cn.cubegarden.icbm.caching.protocol.Envelope;
import cn.cubegarden.icbm.caching.protocol.FrameCodec;
import cn.cubegarden.icbm.caching.protocol.MessageType;
import cn.cubegarden.icbm.caching.protocol.Role;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedCipher;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedFrame;
import cn.cubegarden.icbm.caching.protocol.encapsulation.EncapsulatedHandshake;
import cn.cubegarden.icbm.caching.protocol.encapsulation.StreamId;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
 * master 封装通道会话（草稿 encapsulated-channel §3；阻塞 socket，线程每连接）。
 *
 * <p>生命周期：首字节 magic 判别（由 {@link EncapsulatedControlServer} 完成）→
 * AUTH 校验（公共密码证明 + UUID + nonce，见 {@code AuthToken}）→ AUTH_ACK →
 * HKDF 派生双向会话密钥 → ACTIVE（ChaCha20-Poly1305 加密帧 + 流多路复用）。
 *
 * <p>ACTIVE 后控制帧（streamId=CONTROL）解码为包络并委托共享的
 * {@link ControlSessionLogic}（HELLO/序号/权限/分发与 mTLS 会话完全一致）；
 * LOGIN_VERIFY 与 MC_PASSTHROUGH 流经 {@link Listener} 回调（阶段3/5 实现）。
 *
 * <p>任何协议/加密/帧校验失败 → fail closed（发送 ERROR 或直接关闭，不猜测兼容格式）。
 */
public final class EncapsulatedControlSession implements ControlSessionLogic.Transport, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EncapsulatedControlSession.class);

    /** 非控制流回调（默认 fail closed；阶段3/5 实现具体处理）。 */
    public interface Listener {
        /** LOGIN_VERIFY_REQ 原始 body（阶段3 Auth Gateway 实现）。 */
        default void onLoginVerifyRequest(EncapsulatedControlSession session, byte[] body) {
            LOGGER.warn("[control] LOGIN_VERIFY 流未服务，关闭会话（阶段3 实现）");
            session.fail("LOGIN_VERIFY 未服务");
        }

        /** MC_PASSTHROUGH 明文 MC 报文（阶段5 权威会话桥实现）。 */
        default void onMcPacket(EncapsulatedControlSession session, long streamSequence, byte[] packet) {
            LOGGER.warn("[control] MC_PASSTHROUGH 流未服务，丢弃（阶段5 实现）seq={}", streamSequence);
        }
    }

    private final Socket socket;
    private final ControlSessionLogic logic;
    private final EncapsulatedCipher decryptCipher;   // client→server 密钥
    private final EncapsulatedCipher encryptCipher;   // server→client 密钥
    private final Listener listener;
    private final String uuidHex;

    private final DataInputStream in;
    private final DataOutputStream out;
    private volatile boolean closed;

    public EncapsulatedControlSession(Socket socket, byte[] psk, byte[] uuid, byte[] clientNonce,
                                      byte[] serverNonce, ServiceConfig config, PeerRegistry registry,
                                      ControlService service, Replicator replicator, Listener listener)
            throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        EncapsulatedCipher.SessionKeys keys =
                EncapsulatedCipher.SessionKeys.derive(psk, clientNonce, serverNonce, uuid);
        this.decryptCipher = keys.clientToServer();
        this.encryptCipher = keys.serverToClient();
        this.listener = listener;
        this.uuidHex = HexFormat.of().formatHex(uuid);
        this.logic = new ControlSessionLogic(config, registry, service, replicator, this, this::uuidHex);
    }

    public String uuidHex() {
        return this.uuidHex;
    }

    public boolean isActive() {
        return this.logic.isActive();
    }

    public String sourceServerId() {
        return this.logic.sourceServerId();
    }

    public Role role() {
        return this.logic.role();
    }

    public void send(MessageType type, long requestId, byte[] body) {
        this.logic.send(type, requestId, body);
    }

    /** 发送 LOGIN_VERIFY 流（streamId=1）原始 body（无包络；登录验证结果回执）。 */
    public void sendLoginVerify(byte[] body) {
        try {
            byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encryptCipher, StreamId.LOGIN_VERIFY, body);
            synchronized (this.out) {
                this.out.write(frame);
                this.out.flush();
            }
        } catch (IOException e) {
            LOGGER.warn("[control] LOGIN_VERIFY 发送失败: {}", e.getMessage());
            this.fail("login verify send failed");
        }
    }

    /** 协议违规：发送 ERROR 后关闭（fail closed）。 */
    public void fail(String reason) {
        this.logic.fail(reason);
    }

    /** 读取并处理本连接的全部入站帧（由服务器每连接一线程调用）。 */
    public void serve() {
        try {
            while (!this.closed) {
                byte[] frame = this.readWireFrame();
                EncapsulatedFrame.Decrypted decrypted;
                try {
                    decrypted = EncapsulatedFrame.decodeEncryptedFrame(this.decryptCipher, frame);
                } catch (IOException e) {
                    LOGGER.warn("[control] 封装帧解密/校验失败 {}: {}", this.uuidHex, e.getMessage());
                    this.fail("encrypted frame rejected: " + e.getMessage());
                    return;
                }
                switch (decrypted.stream()) {
                    case CONTROL -> this.handleControl(decrypted.body());
                    case LOGIN_VERIFY -> this.listener.onLoginVerifyRequest(this, decrypted.body());
                    case MC_PASSTHROUGH -> this.listener.onMcPacket(this, 0, decrypted.body());
                }
            }
        } catch (EOFException e) {
            // 对端正常关闭
        } catch (IOException e) {
            if (!this.closed) {
                LOGGER.warn("[control] 封装会话 IO 异常 {}: {}", this.uuidHex, e.getMessage());
            }
        } finally {
            this.logic.onTransportClosed();
            this.close();
        }
    }

    private void handleControl(byte[] body) {
        try {
            Envelope envelope = FrameCodec.decodeFrame(body);
            this.logic.onEnvelope(envelope);
        } catch (IOException e) {
            LOGGER.warn("[control] 控制包络非法 {}: {}", this.uuidHex, e.getMessage());
            this.fail("invalid control envelope");
        }
    }

    /** 读一帧线上字节：[magic(4)][length(4)][payload(length)]；长度校验失败 → IOException。 */
    private byte[] readWireFrame() throws IOException {
        byte[] magic = new byte[4];
        this.in.readFully(magic);
        if (magic[0] != 'I' || magic[1] != 'C' || magic[2] != 'B' || magic[3] != 'M') {
            throw new IOException("bad magic");
        }
        long length = this.in.readInt() & 0xFFFFFFFFL;
        if (length < 8 || length > cn.cubegarden.icbm.caching.protocol.ProtocolConstants.MAX_ENCAPSULATED_FRAME_BYTES) {
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

    // ---- ControlSessionLogic.Transport ----

    @Override
    public void write(Envelope envelope) {
        if (this.closed) {
            throw new IllegalStateException("session closed");
        }
        try {
            byte[] body = FrameCodec.encodeFrame(envelope);
            byte[] frame = EncapsulatedFrame.encodeEncryptedFrame(this.encryptCipher, StreamId.CONTROL, body);
            synchronized (this.out) {
                this.out.write(frame);
                this.out.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException("encapsulated write failed", e);
        }
    }

    @Override
    public void closeTransport() {
        this.close();
    }

    @Override
    public void scheduleHeartbeat(Runnable action, long intervalMillis) {
        Thread heartbeat = new Thread(() -> {
            while (!this.closed) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    action.run();
                } catch (RuntimeException ignored) {
                    return; // 连接已关闭
                }
            }
        }, "icbm-encapsulated-heartbeat-" + this.uuidHex);
        heartbeat.setDaemon(true);
        heartbeat.start();
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
}

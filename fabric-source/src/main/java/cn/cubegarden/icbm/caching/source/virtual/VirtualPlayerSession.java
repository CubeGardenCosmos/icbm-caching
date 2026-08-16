package cn.cubegarden.icbm.caching.source.virtual;

import cn.cubegarden.icbm.caching.source.backend.BackendControlSession;
import io.netty.buffer.Unpooled;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权威会话 v2 虚拟玩家（阶段 9.4；草稿 authoritative-session-v2 §3）：源服 mod 内以原始
 * Socket 回环连接源服游戏端口，手动实现 26.3 登录/配置/play 状态机（帧分割 + 可选压缩 +
 * Minecraft 协议 codec 编解码——不复制常量，全部经 {@link StreamCodec}）。
 *
 * <p>采用原始 Socket 而非 {@code Connection.connectToServer}：26.3 Connection 的
 * UnconfiguredPipelineHandler 协议切换机制在服务端 mod 环境实测不可靠（OutboundConfigurationTask
 * 直达 socket 被 epoll 拒绝 / inbound 配置与服务端回包竞争），手动状态机完全可控。
 *
 * <p>帧格式（26.3）：{@code [VarInt 帧长][帧]}；压缩启用后帧 = {@code [VarInt 原始长度]（0=
 * 未压缩）[Deflater 数据]}（CompressionEncoder.java:34/46）。登录流程：
 * Handshake + Login Start → LoginCompression（启用压缩）→ LoginFinished → LoginAcknowledged
 * + ClientInformation → SelectKnownPacks([]) → RegistryData（忽略）→ FinishConfiguration →
 * play（ClientboundLoginPacket → ready；白名单包回传）。
 */
public final class VirtualPlayerSession implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualPlayerSession.class);
    public static final String NAME_PREFIX = "icbm-";

    /** 回传白名单：权威会话相关 clientbound 包（背包/容器/物品栏/方块互动结果）。
     *  区块/实体全量流（快照管道是区块权威）与登录/配置包不回传。 */
    private static final Set<Class<?>> RELAY_WHITELIST = Set.of(
            ClientboundOpenScreenPacket.class,
            ClientboundContainerClosePacket.class,
            ClientboundContainerSetSlotPacket.class,
            ClientboundContainerSetContentPacket.class,
            ClientboundContainerSetDataPacket.class,
            ClientboundSetCursorItemPacket.class,
            ClientboundSetHeldSlotPacket.class,
            ClientboundSetPlayerInventoryPacket.class,
            ClientboundSetEquipmentPacket.class,
            ClientboundBlockUpdatePacket.class,
            ClientboundSectionBlocksUpdatePacket.class,
            ClientboundEntityEventPacket.class,
            ClientboundSoundPacket.class
    );

    /** 26.3 ClientInformation（net/minecraft/server/level/ClientInformation.java:8）9 字段；
     *  viewDistance=4（服务端 clamp 后最小视距，区块流最小化）。 */
    private static final ClientInformation DEFAULT_CLIENT_INFO = new ClientInformation(
            "en_us", 4, net.minecraft.world.entity.player.ChatVisiblity.FULL,
            true, 0, net.minecraft.world.entity.HumanoidArm.RIGHT,
            false, true, net.minecraft.server.level.ParticleStatus.ALL);

    private static final GameProtocols.Context DEFAULT_GAME_CONTEXT = new GameProtocols.Context() {
        @Override
        public boolean hasInfiniteMaterials() {
            return false;
        }

        @Override
        public boolean canUseCommandBlocks() {
            return false;
        }
    };

    private final MinecraftServer server;
    private final UUID playerUuid;
    private final String playerName;
    private final BackendControlSession backend;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object sendLock = new Object();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final AtomicLong streamSequence = new AtomicLong();

    private volatile ConnectionProtocol phase = ConnectionProtocol.LOGIN;
    private volatile boolean compressionEnabled;
    private int compressionThreshold;

    /** ClientboundLoginCompressionPacket.compressionThreshold 为 private final 且无 getter
     *  （26.3）；反射读取（fail closed：异常时按 0——源服阈值 >0 时服务端拆包失败即断连）。 */
    private static final java.lang.reflect.Field COMPRESSION_THRESHOLD_FIELD =
            initCompressionField();

    private static java.lang.reflect.Field initCompressionField() {
        try {
            java.lang.reflect.Field field = ClientboundLoginCompressionPacket.class
                    .getDeclaredField("compressionThreshold");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("26.3 ClientboundLoginCompressionPacket 无 compressionThreshold（fail closed）", e);
        }
    }
    private volatile boolean ready;
    private volatile boolean closed;
    private Thread readerThread;

    private VirtualPlayerSession(MinecraftServer server, UUID playerUuid, BackendControlSession backend,
                                 Socket socket) throws IOException {
        this.server = server;
        this.playerUuid = playerUuid;
        this.playerName = NAME_PREFIX + playerUuid.toString().replace("-", "").substring(0, 8);
        this.backend = backend;
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /** 建立虚拟玩家连接（回环 127.0.0.1:源服游戏端口）并启动登录线程。 */
    public static VirtualPlayerSession open(MinecraftServer server, UUID playerUuid,
                                            BackendControlSession backend) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5_000);
            VirtualPlayerSession session = new VirtualPlayerSession(server, playerUuid, backend, socket);
            session.readerThread = new Thread(session::loginLoop, "icbm-virtual-player-" + session.playerName);
            session.readerThread.setDaemon(true);
            session.readerThread.start();
            LOGGER.info("[icbm-source] 虚拟玩家会话建立: {} (uuid={}, 连接源服端口 {})",
                    session.playerName, playerUuid, server.getPort());
            return session;
        } catch (IOException e) {
            throw new IllegalStateException("虚拟玩家连接失败（fail closed）: " + e.getMessage(), e);
        }
    }

    public UUID playerUuid() {
        return this.playerUuid;
    }

    public String playerName() {
        return this.playerName;
    }

    public boolean isReady() {
        return this.ready;
    }

    // ---- 上行注入 ----

    /** slave 上行 Serverbound 报文（payload = [packetId VarInt + fields]，无长度）：编码已
     *  由 slave 完成，直接排队/发送。 */
    public void inject(byte[] payload) {
        if (this.closed) {
            return;
        }
        if (this.ready) {
            this.writeFrame(payload);
            LOGGER.info("[icbm-source] 虚拟玩家注入上行报文: {}字节 已发送", payload.length);
        } else {
            synchronized (this.lock()) {
                if (this.ready) {
                    this.writeFrame(payload);
                    LOGGER.info("[icbm-source] 虚拟玩家注入上行报文: {}字节 已发送", payload.length);
                } else {
                    this.pending.add(payload);
                }
            }
        }
    }

    private Object lock() {
        return this.sendLock;
    }

    // ---- 登录状态机（reader 线程） ----

    private void loginLoop() {
        try {
            this.hello();
            while (!this.closed) {
                byte[] payload = this.readPayload();
                if (payload == null) {
                    break; // EOF
                }
                this.dispatch(payload);
            }
        } catch (IOException e) {
            if (!this.closed) {
                LOGGER.warn("[icbm-source] 虚拟玩家连接 IO 异常: {}（{}）", this.playerName, e.getMessage());
            }
        } catch (Throwable t) {
            LOGGER.error("[icbm-source] 虚拟玩家登录/转发异常（fail closed 关闭）: {}: {}",
                    this.playerName, t.toString());
        } finally {
            this.close();
        }
    }

    private void hello() throws IOException {
        // Handshake（INITIAL/HANDSHAKE 协议）+ Login Start（Hello，Login 协议）
        this.sendPacket(HandshakeProtocols.SERVERBOUND.codec(), new ClientIntentionPacket(
                net.minecraft.SharedConstants.getCurrentVersion().protocolVersion(),
                "127.0.0.1", this.server.getPort(), ClientIntent.LOGIN));
        this.sendPacket(LoginProtocols.SERVERBOUND.codec(),
                new ServerboundHelloPacket(this.playerName, this.playerUuid));
        LOGGER.info("[icbm-source] 虚拟玩家握手已发送: {}（Handshake + Login Start）", this.playerName);
    }

    private int readVarInt() throws IOException {
        int value = 0;
        int bytes = 0;
        int b;
        while (((b = this.in.readByte()) & 0x80) != 0) {
            value |= (b & 0x7F) << (bytes++ * 7);
            if (bytes > 5) {
                throw new IOException("VarInt 过长");
            }
        }
        return value | (b & 0x7F) << (bytes * 7);
    }

    private void writeVarInt(int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            this.out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        this.out.writeByte(value);
    }

    /** 读取一帧并解压为 payload（[packetId VarInt + fields]）。EOF → null。 */
    private byte[] readPayload() throws IOException {
        int frameLength = this.readVarInt();
        if (frameLength < 0 || frameLength > 8 * 1024 * 1024) {
            throw new IOException("帧长度越界: " + frameLength);
        }
        byte[] frame = new byte[frameLength];
        this.in.readFully(frame);
        if (!this.compressionEnabled) {
            return frame;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        int uncompressedLength = buf.readVarInt();
        if (uncompressedLength == 0) {
            byte[] payload = new byte[buf.readableBytes()];
            buf.readBytes(payload);
            return payload;
        }
        byte[] compressed = new byte[buf.readableBytes()];
        buf.readBytes(compressed);
        byte[] payload = new byte[uncompressedLength];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int written = inflater.inflate(payload);
            if (written != uncompressedLength) {
                throw new IOException("解压长度不符: " + written + " != " + uncompressedLength);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("解压失败: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return payload;
    }

    /** 发送 payload（压缩 + VarInt 帧长）。 */
    private void writeFrame(byte[] payload) {
        try {
            byte[] frame;
            if (this.compressionEnabled && payload.length >= this.compressionThreshold) {
                Deflater deflater = new Deflater();
                try {
                    deflater.setInput(payload);
                    deflater.finish();
                    java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    while (!deflater.finished()) {
                        int n = deflater.deflate(buffer);
                        deflated.write(buffer, 0, n);
                    }
                    byte[] compressed = deflated.toByteArray();
                    FriendlyByteBuf frameBuf = new FriendlyByteBuf(Unpooled.buffer());
                    frameBuf.writeVarInt(payload.length);
                    frameBuf.writeBytes(compressed);
                    frame = new byte[frameBuf.readableBytes()];
                    frameBuf.getBytes(0, frame);
                } finally {
                    deflater.end();
                }
            } else if (this.compressionEnabled) {
                FriendlyByteBuf frameBuf = new FriendlyByteBuf(Unpooled.buffer());
                frameBuf.writeVarInt(0);
                frameBuf.writeBytes(payload);
                frame = new byte[frameBuf.readableBytes()];
                frameBuf.getBytes(0, frame);
            } else {
                frame = payload;
            }
            synchronized (this.sendLock) {
                this.writeVarInt(frame.length);
                this.out.write(frame);
                this.out.flush();
            }
        } catch (IOException e) {
            LOGGER.warn("[icbm-source] 虚拟玩家发送失败: {}", e.getMessage());
            this.close();
        }
    }

    /** 按当前阶段编码并发送 Packet 对象。 */
    private void sendPacket(StreamCodec<? super RegistryFriendlyByteBuf, ? extends Packet<?>> codec,
                            Packet<?> packet) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), this.server.registryAccess());
        try {
            ((StreamCodec) codec).encode(buf, packet);
            byte[] payload = new byte[buf.readableBytes()];
            buf.getBytes(0, payload);
            this.writeFrame(payload);
        } catch (Exception e) {
            LOGGER.error("[icbm-source] 虚拟玩家发送编码失败: {}", e.getMessage());
        } finally {
            buf.release();
        }
    }

    /** 收到 clientbound payload：按当前阶段解码 + 状态机处理。 */
    @SuppressWarnings("unchecked")
    private void dispatch(byte[] payload) throws IOException {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(payload), this.server.registryAccess());
        Packet<?> packet;
        try {
            packet = switch (this.phase) {
                case LOGIN -> (Packet<?>) ((StreamCodec) LoginProtocols.CLIENTBOUND.codec()).decode(buf);
                case CONFIGURATION -> (Packet<?>) ((StreamCodec) ConfigurationProtocols.CLIENTBOUND.codec()).decode(buf);
                case PLAY -> (Packet<?>) ((StreamCodec) GameProtocols.CLIENTBOUND_TEMPLATE
                        .bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess())).codec()).decode(buf);
                default -> throw new IOException("未知阶段: " + this.phase);
            };
        } catch (Exception e) {
            throw new IOException("clientbound 解码失败（阶段 " + this.phase + "）: " + e.getMessage(), e);
        } finally {
            buf.release();
        }
        this.onClientbound(packet);
    }

    private void onClientbound(Packet<?> packet) throws IOException {
        if (packet instanceof ClientboundLoginCompressionPacket compression) {
            int threshold;
            try {
                threshold = COMPRESSION_THRESHOLD_FIELD.getInt(compression);
            } catch (IllegalAccessException e) {
                LOGGER.error("[icbm-source] 读取压缩阈值失败（fail closed 关闭）: {}", e.toString());
                this.close();
                return;
            }
            this.compressionEnabled = true;
            this.compressionThreshold = threshold;
            LOGGER.info("[icbm-source] 虚拟玩家登录: 压缩启用 threshold={}", threshold);
            return;
        }
        if (packet instanceof ClientboundLoginFinishedPacket) {
            // offline 登录完成 → 回 LoginAcknowledged + 切配置阶段（发 ClientInformation）
            this.phase = ConnectionProtocol.CONFIGURATION;
            this.sendPacket(LoginProtocols.SERVERBOUND.codec(), ServerboundLoginAcknowledgedPacket.INSTANCE);
            this.sendPacket(ConfigurationProtocols.SERVERBOUND.codec(),
                    new ServerboundClientInformationPacket(DEFAULT_CLIENT_INFO));
            LOGGER.info("[icbm-source] 虚拟玩家登录: LoginFinished → 配置阶段");
            return;
        }
        if (this.phase == ConnectionProtocol.CONFIGURATION) {
            if (packet instanceof net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks) {
                // 回空 knownPacks：服务端发送完整注册表数据（SynchronizeRegistriesTask）
                this.sendPacket(ConfigurationProtocols.SERVERBOUND.codec(),
                        new ServerboundSelectKnownPacks(List.of()));
                return;
            }
            if (packet instanceof net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket) {
                this.phase = ConnectionProtocol.PLAY;
                this.sendPacket(ConfigurationProtocols.SERVERBOUND.codec(),
                        ServerboundFinishConfigurationPacket.INSTANCE);
                LOGGER.info("[icbm-source] 虚拟玩家配置完成 → play 阶段");
                return;
            }
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket ka) {
                // 配置阶段 keepalive 不回 → 服务端踢（e2e 暴露 15s Disconnect）
                this.sendPacket(ConfigurationProtocols.SERVERBOUND.codec(),
                        new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(ka.getId()));
                return;
            }
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundPingPacket ping) {
                this.sendPacket(ConfigurationProtocols.SERVERBOUND.codec(),
                        new net.minecraft.network.protocol.common.ServerboundPongPacket(ping.getId()));
                return;
            }
            // RegistryData / EnabledFeatures / ServerLinks / Brand 等：客户端信息已发，忽略
            return;
        }
        if (this.phase == ConnectionProtocol.PLAY) {
            if (packet instanceof ClientboundLoginPacket) {
                // 26.3 进世界后必须回 ServerboundPlayerLoadedPacket（markClientLoaded，
                // ServerGamePacketListenerImpl.handleAcceptPlayerLoad:563）——否则
                // hasClientLoaded()=false 时 handleUseItemOn 等互动全部跳过（e2e 暴露）
                this.sendPacket(GameProtocols.SERVERBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(this.server.registryAccess()),
                        DEFAULT_GAME_CONTEXT).codec(),
                        new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                this.ready = true;
                LOGGER.info("[icbm-source] 虚拟玩家已就绪: {}（可接收权威会话报文）", this.playerName);
                this.flushPending();
                return;
            }
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket posPacket) {
                LOGGER.info("[icbm-source] 虚拟玩家收到传送确认包 id={}（回 AcceptTeleportation）",
                        posPacket.id());
                // 传送确认：tp 后服务端 awaitingPositionFromClient 非 null 会跳过互动
                // （ServerGamePacketListenerImpl.handleUseItemOn:1361）——必须回
                // ServerboundAcceptTeleportationPacket(id)
                this.sendPacket(GameProtocols.SERVERBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(this.server.registryAccess()),
                        DEFAULT_GAME_CONTEXT).codec(),
                        new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(
                                posPacket.id()));
                return;
            }
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket ka) {
                this.sendPacket(GameProtocols.SERVERBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(this.server.registryAccess()),
                        DEFAULT_GAME_CONTEXT).codec(),
                        new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(ka.getId()));
                return;
            }
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundPingPacket ping) {
                this.sendPacket(GameProtocols.SERVERBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(this.server.registryAccess()),
                        DEFAULT_GAME_CONTEXT).codec(),
                        new net.minecraft.network.protocol.common.ServerboundPongPacket(ping.getId()));
                return;
            }
            if (RELAY_WHITELIST.contains(packet.getClass())) {
                RegistryFriendlyByteBuf outBuf = new RegistryFriendlyByteBuf(
                        Unpooled.buffer(), this.server.registryAccess());
                try {
                    ((StreamCodec) GameProtocols.CLIENTBOUND_TEMPLATE
                            .bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess())).codec())
                            .encode(outBuf, packet);
                    byte[] bodyPayload = new byte[outBuf.readableBytes()];
                    outBuf.getBytes(0, bodyPayload);
                    this.backend.sendMcPassthrough(this.wrapBody(bodyPayload));
                } finally {
                    outBuf.release();
                }
            }
        }
    }

    private void flushPending() {
        byte[] payload;
        while ((payload = this.pollPending()) != null) {
            this.writeFrame(payload);
        }
    }

    private byte[] pollPending() {
        synchronized (this.sendLock) {
            return this.pending.poll();
        }
    }

    /** body v2 = [playerUuid(16)][流序号(8)][长度(4)][payload]（CACHE_PROTOCOL §10 v2）。 */
    private byte[] wrapBody(byte[] payload) {
        byte[] body = new byte[28 + payload.length];
        ByteBuffer.wrap(body)
                .putLong(0, this.playerUuid.getMostSignificantBits())
                .putLong(8, this.playerUuid.getLeastSignificantBits())
                .putLong(16, this.streamSequence.incrementAndGet())
                .putInt(24, payload.length);
        System.arraycopy(payload, 0, body, 28, payload.length);
        return body;
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
        LOGGER.info("[icbm-source] 虚拟玩家会话销毁: {}（源服玩家已踢出，快照管道自然回滚）",
                this.playerName);
    }
}

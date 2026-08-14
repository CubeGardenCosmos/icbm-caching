package cn.cubegarden.icbm.caching.virtual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.PutResult;
import cn.cubegarden.icbm.caching.protocol.store.SnapshotObjectStore;
import cn.cubegarden.icbm.caching.virtual.config.VirtualBackendConfig;
import cn.cubegarden.icbm.caching.virtual.net.ClientConnection;
import cn.cubegarden.icbm.caching.virtual.net.FrameSplitter;
import cn.cubegarden.icbm.caching.virtual.net.GameServer;
import cn.cubegarden.icbm.caching.virtual.net.PacketIds;
import cn.cubegarden.icbm.caching.virtual.net.ProtocolState;
import cn.cubegarden.icbm.caching.virtual.net.SessionHandler;
import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import cn.cubegarden.icbm.caching.virtual.session.HandshakeSession;
import cn.cubegarden.icbm.caching.virtual.world.Nbt;
import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;
import cn.cubegarden.icbm.caching.virtual.world.SnapshotRepository;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

/**
 * 26.3 连接状态机集成测试（嵌入式通道 + 合成客户端）：
 * handshake → login（压缩/LoginFinished）→ configuration（brand/features/known packs/
 * registry data/tags/finish）→ play（Join Game 旁观者 + 能力 + 传送 + 区块窗口）；
 * 非冻结协议版本确定性拒绝；世界互动包拒绝且不产生写包。
 */
class ProtocolFlowTest {

    private static final int PROTOCOL = 1073742154;

    /** 合成客户端：按 26.3 帧/压缩/包 ID 编码发送、解码接收。 */
    private static final class TestClient {
        private final EmbeddedChannel channel;
        private int compressionThreshold = -1;

        TestClient(EmbeddedChannel channel) {
            this.channel = channel;
        }

        void setThreshold(int threshold) {
            this.compressionThreshold = threshold;
        }

        /** 发送客户端包（走帧 + 压缩；压缩协商前无压缩标记）。 */
        void send(int packetId, byte[] payload) {
            PacketBuf packet = new PacketBuf();
            packet.writeVarInt(packetId);
            packet.writeBytes(payload, 0, payload.length);
            byte[] packetBytes = packet.toByteArray();
            ByteBuf frame = Unpooled.buffer();
            if (this.compressionThreshold >= 0) {
                if (packetBytes.length >= this.compressionThreshold) {
                    byte[] compressed = new byte[packetBytes.length + 64];
                    Deflater deflater = new Deflater();
                    deflater.setInput(packetBytes);
                    deflater.finish();
                    int written = 0;
                    while (!deflater.finished()) {
                        written += deflater.deflate(compressed, written, compressed.length - written);
                    }
                    deflater.end();
                    writeVarInt(frame, packetBytes.length);
                    frame.writeBytes(compressed, 0, written);
                } else {
                    writeVarInt(frame, 0);
                    frame.writeBytes(packetBytes);
                }
            } else {
                frame.writeBytes(packetBytes);
            }
            ByteBuf wire = Unpooled.buffer();
            writeVarInt(wire, frame.readableBytes());
            wire.writeBytes(frame);
            this.channel.writeInbound(wire);
        }

        /** 读取下一个服务端包（解帧 + 解压）。 */
        Packet readPacket() {
            Object msg = this.channel.readOutbound();
            assertNotNull(msg, "期望服务端包但通道无输出");
            ByteBuf frame = (ByteBuf) msg;
            try {
                byte[] bytes = new byte[frame.readableBytes()];
                frame.readBytes(bytes);
                PacketBuf raw = PacketBuf.wrap(bytes);
                raw.readVarInt(); // 帧长度
                int uncompressedLength = raw.readVarInt();
                byte[] data = uncompressedLength == 0
                        ? raw.readRemaining()
                        : inflate(raw.readRemaining(), uncompressedLength);
                PacketBuf packet = PacketBuf.wrap(data);
                int packetId = packet.readVarInt();
                return new Packet(packetId, PacketBuf.wrap(packet.readRemaining()));
            } finally {
                frame.release();
            }
        }

        static byte[] inflate(byte[] compressed, int length) {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            byte[] out = new byte[length];
            try {
                int written = inflater.inflate(out);
                assertEquals(length, written);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                inflater.end();
            }
            return out;
        }

        boolean hasOutbound() {
            return this.channel.outboundMessages().size() > 0;
        }

        static void writeVarInt(ByteBuf out, int value) {
            while ((value & ~0x7F) != 0) {
                out.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.writeByte(value);
        }
    }

    /** 服务端包（id + 独立 payload 缓冲）。 */
    private record Packet(int id, PacketBuf payload) {
    }

    private ClientConnection newConnection(EmbeddedChannel channel, GameServer server, VirtualBackendConfig config,
                                          RegistryBundle registry) {
        ClientConnection connection = new ClientConnection(server);
        connection.setupState(ProtocolState.HANDSHAKE,
                new HandshakeSession(connection, config, (state, conn) -> switch (state) {
                    case STATUS -> new cn.cubegarden.icbm.caching.virtual.session.StatusSession(conn, config);
                    case LOGIN -> new cn.cubegarden.icbm.caching.virtual.session.LoginSession(conn,
                            (s, c) -> new cn.cubegarden.icbm.caching.virtual.session.ConfigurationSession(c, registry,
                                    (s2, c2) -> new cn.cubegarden.icbm.caching.virtual.session.PlaySession(c2, config,
                                            registry, repository(), 1)));
                    case CONFIGURATION -> new cn.cubegarden.icbm.caching.virtual.session.ConfigurationSession(conn,
                            registry, (s, c) -> new cn.cubegarden.icbm.caching.virtual.session.PlaySession(c, config,
                                    registry, repository(), 1));
                    case PLAY -> new cn.cubegarden.icbm.caching.virtual.session.PlaySession(conn, config, registry,
                            repository(), 1);
                    default -> throw new IllegalStateException();
                }));
        return connection;
    }

    private SnapshotRepository repository() {
        return this.repository;
    }

    private SnapshotRepository repository;

    private VirtualBackendConfig config() {
        return VirtualBackendConfig.of(Map.of(
                "virtualBackend.gameHost", "127.0.0.1",
                "virtualBackend.gamePort", "25575",
                "virtualBackend.viewDistance", "2",
                "virtualBackend.dimensionNamespace", "minecraft",
                "virtualBackend.dimensionPath", "overworld",
                "fingerprint.minecraftVersion", "26.3-snapshot-8",
                "fingerprint.runtimeProtocol", "1073742154"));
    }

    private RegistryBundle bundle() {
        RegistryBundle bundle = RegistryBundle.minimal();
        bundle.addTestRegistry("minecraft:biome", new RegistryBundle.RegistryEntry("minecraft:plains", new byte[]{10, 0}));
        bundle.setTestWorld("dimensionTypeId", "minecraft:overworld");
        bundle.setTestWorld("seaLevel", 63);
        bundle.setTestWorld("spawnX", 8);
        bundle.setTestWorld("spawnY", 64);
        bundle.setTestWorld("spawnZ", 8);
        return bundle;
    }

    private void commitSpawnChunk(SnapshotObjectStore store) throws Exception {
        DimensionRef dimension = new DimensionRef("minecraft", "overworld");
        store.announceEpoch(dimension, 7);
        ChunkRef key = ChunkRef.of(dimension, 0, 0);
        store.raiseRequired(key, 1); // PUT 要求 revision == required（源提交流程）
        byte[] payload = Nbt.toGzipBytes(Nbt.compound());
        String hash = sha256(payload);
        assertEquals(PutResult.OK, store.put(key, 1, 7, "NBT_CHUNK_V1", "GZIP", 4, hash, payload));
        store.commit(key, 1);
    }

    private String sha256(byte[] data) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    void fullFlowReachesPlayAsSpectator() throws Exception {
        java.nio.file.Path storeRoot = java.nio.file.Files.createTempDirectory("icbm-vb-flow");
        SnapshotObjectStore store = new SnapshotObjectStore(storeRoot);
        store.recover();
        this.commitSpawnChunk(store);
        this.repository = new SnapshotRepository(store, new DimensionRef("minecraft", "overworld"), this.bundle(), -4, 19);
        this.repository.setMasterConnected(true);

        VirtualBackendConfig config = this.config();
        GameServer server = new GameServer(config, null);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new FrameSplitter());
        ClientConnection connection = this.newConnection(channel, server, config, this.bundle());
        channel.pipeline().addLast("connection", connection);
        TestClient client = new TestClient(channel);

        // ---- handshake → login ----
        PacketBuf intention = new PacketBuf();
        intention.writeVarInt(PROTOCOL);
        intention.writeUtf("127.0.0.1");
        intention.writeShort(25575);
        intention.writeVarInt(2); // login
        client.send(PacketIds.SERVERBOUND_INTENTION, intention.toByteArray());

        PacketBuf hello = new PacketBuf();
        hello.writeUtf("Tester");
        hello.writeUUID(UUID.randomUUID());
        client.send(PacketIds.SERVERBOUND_HELLO, hello.toByteArray());

        Packet loginCompression = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_LOGIN_COMPRESSION, loginCompression.id());
        assertEquals(256, loginCompression.payload().readVarInt());
        client.setThreshold(256);

        Packet loginFinished = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_LOGIN_FINISHED, loginFinished.id());
        loginFinished.payload().readUUID(); // profile uuid
        assertEquals("Tester", loginFinished.payload().readUtf());
        assertEquals(0, loginFinished.payload().readVarInt()); // 无属性
        loginFinished.payload().readUUID(); // sessionId

        client.send(PacketIds.SERVERBOUND_LOGIN_ACKNOWLEDGED, new byte[0]);

        // ---- configuration ----
        Packet brand = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_CONFIG_CUSTOM_PAYLOAD, brand.id());
        assertEquals("minecraft:brand", brand.payload().readUtf());
        brand.payload().readUtf();

        Packet features = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_UPDATE_ENABLED_FEATURES, features.id());
        int featureCount = features.payload().readVarInt();
        for (int i = 0; i < featureCount; i++) {
            features.payload().readUtf();
        }

        Packet knownPacks = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_SELECT_KNOWN_PACKS, knownPacks.id());
        assertEquals(0, knownPacks.payload().readVarInt());

        PacketBuf knownPacksReply = new PacketBuf();
        knownPacksReply.writeVarInt(0);
        client.send(PacketIds.SERVERBOUND_SELECT_KNOWN_PACKS, knownPacksReply.toByteArray());

        Packet registryData = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_REGISTRY_DATA, registryData.id());
        assertEquals("minecraft:biome", registryData.payload().readUtf());
        assertEquals(1, registryData.payload().readVarInt());
        assertEquals("minecraft:plains", registryData.payload().readUtf());
        assertEquals(1, registryData.payload().readByte());
        assertEquals(10, registryData.payload().readByte()); // NBT compound 类型字节
        assertEquals(0, registryData.payload().readByte()); // END

        Packet updateTags = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_CONFIG_UPDATE_TAGS, updateTags.id());
        assertEquals(0, updateTags.payload().readVarInt());

        Packet finishConfig = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_FINISH_CONFIGURATION, finishConfig.id());
        assertEquals(0, finishConfig.payload().readableBytes());

        client.send(PacketIds.SERVERBOUND_FINISH_CONFIGURATION, new byte[0]);

        // ---- play ----
        Packet joinGame = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_LOGIN, joinGame.id());
        PacketBuf login = joinGame.payload();
        login.readVarInt(); // playerId
        assertFalse(login.readBoolean()); // hardcore
        int levels = login.readVarInt();
        assertEquals(1, levels);
        assertEquals("minecraft:dimension", login.readUtf()); // registry key
        assertEquals("minecraft:overworld", login.readUtf());
        login.readVarInt(); // maxPlayers
        login.readVarInt(); // chunkRadius
        login.readVarInt(); // simulationDistance
        login.readBoolean();
        login.readBoolean();
        login.readBoolean();
        login.readVarInt(); // dimensionType holder id
        login.readUtf(); // dimension key
        login.readLong(); // seed
        assertEquals(3, login.readVarInt()); // GameType.SPECTATOR
        assertEquals(0, login.readVarInt()); // previousGameType 空

        assertEquals(PacketIds.CLIENTBOUND_CHANGE_DIFFICULTY, client.readPacket().id());
        Packet abilities = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_PLAYER_ABILITIES, abilities.id());
        assertEquals(7, abilities.payload().readByte()); // invulnerable|flying|canFly
        assertEquals(PacketIds.CLIENTBOUND_SET_EXPERIENCE, client.readPacket().id());
        assertEquals(PacketIds.CLIENTBOUND_SET_HEALTH, client.readPacket().id());
        assertEquals(PacketIds.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION, client.readPacket().id());
        assertEquals(PacketIds.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS, client.readPacket().id());
        assertEquals(PacketIds.CLIENTBOUND_SET_SIMULATION_DISTANCE, client.readPacket().id());
        assertEquals(PacketIds.CLIENTBOUND_SET_TIME, client.readPacket().id());

        Packet teleport = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_PLAYER_POSITION, teleport.id());
        teleport.payload().readVarInt(); // teleport id
        teleport.payload().readDouble();
        teleport.payload().readDouble();
        teleport.payload().readDouble();
        teleport.payload().readDouble(); // deltaMovement
        teleport.payload().readDouble();
        teleport.payload().readDouble();
        teleport.payload().readFloat(); // yRot
        teleport.payload().readFloat(); // xRot
        teleport.payload().readInt(); // relatives 空位掩码

        Packet center = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_SET_CHUNK_CACHE_CENTER, center.id());
        assertEquals(0, center.payload().readVarInt());
        assertEquals(0, center.payload().readVarInt());

        // 区块 (0,0) 有 committed snapshot → chunk 包（含 light data）
        Packet chunk = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT, chunk.id());
        assertEquals(0, chunk.payload().readInt());
        assertEquals(0, chunk.payload().readInt());
        assertTrue(chunk.payload().readableBytes() > 0, "chunk 包应含高度图/区块数据/光照数据");

        // 视距 2：其余 24 区块无快照 → 不发（fail closed）
        assertFalse(client.hasOutbound(), "缺失区块不应发送任何包");

        // ---- 世界互动拒绝（无写路径） ----
        PacketBuf useItemOn = new PacketBuf();
        useItemOn.writeVarInt(0);
        useItemOn.writeLong(0);
        useItemOn.writeVarInt(0);
        useItemOn.writeByte(0);
        useItemOn.writeFloat(0);
        useItemOn.writeFloat(0);
        useItemOn.writeFloat(0);
        useItemOn.writeBoolean(false);
        useItemOn.writeVarInt(1);
        useItemOn.writeVarInt(0);
        client.send(PacketIds.SERVERBOUND_USE_ITEM_ON, useItemOn.toByteArray());
        assertFalse(client.hasOutbound(), "世界互动不得产生任何写包");
        assertTrue(channel.isActive(), "互动拒绝后连接应保持");

        // ---- 移动跨区块驱动窗口 ----
        PacketBuf move = new PacketBuf();
        move.writeDouble(3 * 16 + 1); // chunk (3,0)
        move.writeDouble(64);
        move.writeDouble(1);
        move.writeByte(0); // onGround
        client.send(PacketIds.SERVERBOUND_MOVE_PLAYER_POS, move.toByteArray());

        Packet newCenter = client.readPacket();
        assertEquals(PacketIds.CLIENTBOUND_SET_CHUNK_CACHE_CENTER, newCenter.id());
        assertEquals(3, newCenter.payload().readVarInt());
        assertEquals(0, newCenter.payload().readVarInt());

        // (3,0) 无快照 → 不发送 chunk；旧窗口外区块应卸载
        boolean sawForget = false;
        boolean sawChunk = false;
        while (client.hasOutbound()) {
            Packet packet = client.readPacket();
            if (packet.id() == PacketIds.CLIENTBOUND_FORGET_LEVEL_CHUNK) {
                sawForget = true;
            }
            if (packet.id() == PacketIds.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT) {
                sawChunk = true;
            }
        }
        assertTrue(sawForget, "窗口外旧区块应被卸载");
        assertFalse(sawChunk, "无快照的新区块不应发送（fail closed）");

        channel.finishAndReleaseAll();
        store.close();
    }

    @Test
    void rejectsNonFrozenProtocolVersion() {
        VirtualBackendConfig config = this.config();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new FrameSplitter());
        ClientConnection connection = this.newConnection(channel, new GameServer(config, null), config, this.bundle());
        channel.pipeline().addLast("connection", connection);

        PacketBuf intention = new PacketBuf();
        intention.writeVarInt(1234567); // 非冻结版本
        intention.writeUtf("127.0.0.1");
        intention.writeShort(25575);
        intention.writeVarInt(2);
        TestClient client = new TestClient(channel);
        client.send(PacketIds.SERVERBOUND_INTENTION, intention.toByteArray());

        assertFalse(channel.isActive(), "非冻结协议版本必须确定性断连");
        channel.finishAndReleaseAll();
    }
}

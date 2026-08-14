package cn.cubegarden.icbm.caching.virtual.session;

import cn.cubegarden.icbm.caching.virtual.config.VirtualBackendConfig;
import cn.cubegarden.icbm.caching.virtual.net.ClientConnection;
import cn.cubegarden.icbm.caching.virtual.net.PacketIds;
import cn.cubegarden.icbm.caching.virtual.net.SessionHandler;
import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;
import cn.cubegarden.icbm.caching.virtual.world.SnapshotRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PLAY 旁观者会话（net/minecraft/server/network/ServerGamePacketListenerImpl.java +
 * ServerCommonPacketListenerImpl.java 对照，最小可用子集）：
 *
 * <ul>
 *   <li>Join Game（gameType=SPECTATOR）+ 能力（无敌/飞行）+ 难度 + 经验/生命 +
 *       重生点 + 传送（需客户端确认）+ 区块窗口（Set Center Chunk / Chunk Data and Update
 *       Light / Unload Chunk）；</li>
 *   <li>keepalive 每 15s（未应答 → 断连，ServerCommonPacketListenerImpl.keepConnectionAlive
 *       对照）；</li>
 *   <li>移动包更新位置并驱动区块窗口（传送确认 + 视距移动不建立源服会话）；</li>
 *   <li>只读旁观：全部世界互动包（放置/使用/攻击/玩家动作/交互/切换物品…）拒绝且不应用
 *       （fail closed：虚拟后端无任何写路径）；</li>
 *   <li>区块装载走 {@link SnapshotRepository}（SERVABLE 才发送；未命中/落后/epoch 不匹配
 *       → 不展示旧区块）。</li>
 * </ul>
 */
public final class PlaySession implements SessionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaySession.class);

    private final ClientConnection connection;
    private final VirtualBackendConfig config;
    private final RegistryBundle registry;
    private final SnapshotRepository repository;

    private final int playerId;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private int chunkX;
    private int chunkZ;
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private final Set<Long> sentChunks = new HashSet<>();
    private volatile boolean accepted = true;

    private long pendingKeepAliveId = -1;
    private java.util.concurrent.ScheduledFuture<?> keepAliveTask;

    public PlaySession(
            ClientConnection connection,
            VirtualBackendConfig config,
            RegistryBundle registry,
            SnapshotRepository repository,
            int playerId
    ) {
        this.connection = connection;
        this.config = config;
        this.registry = registry;
        this.repository = repository;
        this.playerId = playerId;
    }

    @Override
    public void onActivated() {
        this.sendJoinGame();
        this.sendPlayInitialPackets();
        this.teleportToSpawn();
        this.scheduleKeepAlive();
    }

    // ---- 出站 ----

    private void sendJoinGame() {
        PacketBuf login = new PacketBuf();
        login.writeVarInt(this.playerId);
        login.writeBoolean(false); // hardcore
        // levels（Set<ResourceKey<Level>>：registry key + value 两个 Identifier）
        login.writeVarInt(this.registry.levelIds().size());
        for (String level : this.registry.levelIds()) {
            login.writeIdentifier("minecraft", "dimension");
            login.writeUtf(level); // 完整 "namespace:path"
        }
        login.writeVarInt(20); // maxPlayers
        login.writeVarInt(this.config.viewDistance()); // chunkRadius
        login.writeVarInt(this.config.viewDistance()); // simulationDistance
        login.writeBoolean(false); // reducedDebugInfo
        login.writeBoolean(true); // showDeathScreen
        login.writeBoolean(false); // doLimitedCrafting
        this.writeCommonPlayerSpawnInfo(login);
        login.writeBoolean(false); // onlineMode
        login.writeBoolean(false); // enforcesSecureChat
        this.connection.send(PacketIds.CLIENTBOUND_LOGIN, login);
    }

    private void writeCommonPlayerSpawnInfo(PacketBuf buf) {
        String dimensionTypeId = this.registry.worldString("dimensionTypeId", "minecraft:overworld");
        int typeIndex = this.registry.dimensionTypeIds().indexOf(dimensionTypeId);
        if (typeIndex < 0) {
            throw new IllegalStateException("维度类型不在转储表: " + dimensionTypeId);
        }
        buf.writeVarInt(typeIndex + 1); // Holder<DimensionType> 引用（id+1）
        buf.writeIdentifier(this.config.dimensionNamespace(), this.config.dimensionPath()); // dimension
        buf.writeLong(this.registry.worldLong("seed", 0)); // seed
        buf.writeVarInt(3); // GameType.SPECTATOR
        buf.writeVarInt(0); // previousGameType 空（OPTIONAL_VAR_INT）
        buf.writeBoolean(false); // isDebug
        buf.writeBoolean(false); // isFlat
        buf.writeByte(0); // lastDeathLocation 空
        buf.writeVarInt(0); // portalCooldown
        buf.writeVarInt((int) this.registry.worldLong("seaLevel", 63)); // seaLevel
    }

    private void sendPlayInitialPackets() {
        // 难度
        PacketBuf difficulty = new PacketBuf();
        difficulty.writeByte(2); // normal
        difficulty.writeBoolean(false); // locked
        this.connection.send(PacketIds.CLIENTBOUND_CHANGE_DIFFICULTY, difficulty);

        // 旁观者能力：无敌 + 飞行 + 可飞行
        PacketBuf abilities = new PacketBuf();
        abilities.writeByte(1 | 2 | 4);
        abilities.writeFloat(0.05f); // flyingSpeed
        abilities.writeFloat(0.1f); // walkingSpeed
        this.connection.send(PacketIds.CLIENTBOUND_PLAYER_ABILITIES, abilities);

        // 经验/生命
        PacketBuf experience = new PacketBuf();
        experience.writeFloat(0f);
        experience.writeVarInt(0); // level
        experience.writeVarInt(0); // total
        this.connection.send(PacketIds.CLIENTBOUND_SET_EXPERIENCE, experience);

        PacketBuf health = new PacketBuf();
        health.writeFloat(20f);
        health.writeVarInt(20); // food
        health.writeFloat(5f); // saturation
        this.connection.send(PacketIds.CLIENTBOUND_SET_HEALTH, health);

        // 默认重生点（RespawnData: GlobalPos + yaw + pitch）
        PacketBuf spawn = new PacketBuf();
        spawn.writeIdentifier("minecraft", "overworld");
        spawn.writeLong(0L); // BlockPos.ZERO
        spawn.writeFloat(0f); // yaw
        spawn.writeFloat(0f); // pitch
        this.connection.send(PacketIds.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION, spawn);

        // 区块视距
        PacketBuf radius = new PacketBuf();
        radius.writeVarInt(this.config.viewDistance());
        this.connection.send(PacketIds.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS, radius);

        PacketBuf simulation = new PacketBuf();
        simulation.writeVarInt(this.config.viewDistance());
        this.connection.send(PacketIds.CLIENTBOUND_SET_SIMULATION_DISTANCE, simulation);

        // 世界时间
        PacketBuf time = new PacketBuf();
        time.writeLong(0); // gameTime
        time.writeVarInt(0); // clockUpdates 空
        this.connection.send(PacketIds.CLIENTBOUND_SET_TIME, time);
    }

    private void teleportToSpawn() {
        double spawnX = (double) this.registry.worldLong("spawnX", 0);
        double spawnY = (double) this.registry.worldLong("spawnY", 64);
        double spawnZ = (double) this.registry.worldLong("spawnZ", 0);
        float spawnYaw = (float) (double) this.registry.worldLong("spawnYaw", 0);
        float spawnPitch = (float) (double) this.registry.worldLong("spawnPitch", 0);
        this.x = spawnX;
        this.y = spawnY;
        this.z = spawnZ;
        this.yaw = spawnYaw;
        this.pitch = spawnPitch;
        this.chunkX = floorDiv((int) this.x, 16);
        this.chunkZ = floorDiv((int) this.z, 16);

        PacketBuf position = new PacketBuf();
        position.writeVarInt(0); // teleport id
        position.writeDouble(this.x);
        position.writeDouble(this.y);
        position.writeDouble(this.z);
        position.writeDouble(0); // deltaMovement x
        position.writeDouble(0); // deltaMovement y
        position.writeDouble(0); // deltaMovement z
        position.writeFloat(this.yaw);
        position.writeFloat(this.pitch);
        position.writeInt(0); // relatives 空位掩码
        this.connection.send(PacketIds.CLIENTBOUND_PLAYER_POSITION, position);

        this.updateChunkWindow();
    }

    // ---- 区块窗口 ----

    private void updateChunkWindow() {
        if (this.centerX == this.chunkX && this.centerZ == this.chunkZ) {
            return;
        }
        int view = this.config.viewDistance();
        // 旧的已发送集合（用于卸载判定）
        Set<Long> previous = new HashSet<>(this.sentChunks);
        this.sentChunks.clear();

        PacketBuf center = new PacketBuf();
        center.writeVarInt(this.chunkX);
        center.writeVarInt(this.chunkZ);
        this.connection.send(PacketIds.CLIENTBOUND_SET_CHUNK_CACHE_CENTER, center);
        this.centerX = this.chunkX;
        this.centerZ = this.chunkZ;

        // 自中心向外逐圈发送（vanilla 顺序）
        for (int radius = 0; radius <= view; radius++) {
            int minX = this.chunkX - radius;
            int maxX = this.chunkX + radius;
            int minZ = this.chunkZ - radius;
            int maxZ = this.chunkZ + radius;
            for (int x = minX; x <= maxX; x++) {
                this.sendOrDropChunk(x, minZ, previous);
                this.sendOrDropChunk(x, maxZ, previous);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                this.sendOrDropChunk(minX, z, previous);
                this.sendOrDropChunk(maxX, z, previous);
            }
        }
        // 卸载窗口外已发送区块
        for (long key : previous) {
            int cx = (int) (key >> 32);
            int cz = (int) (long) key;
            PacketBuf forget = new PacketBuf();
            forget.writeLong(packChunkPos(cx, cz));
            this.connection.send(PacketIds.CLIENTBOUND_FORGET_LEVEL_CHUNK, forget);
        }
    }

    private void sendOrDropChunk(int cx, int cz, Set<Long> previous) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (this.sentChunks.contains(key)) {
            return;
        }
        this.sentChunks.add(key);
        previous.remove(key);
        SnapshotRepository.EncodedChunk encoded = this.repository.resolveAndEncode(cx, cz);
        if (encoded == null) {
            // fail closed：未命中/落后/epoch 不匹配 → 不发送（客户端保持空视图）
            return;
        }
        // 26.3：light data 与 chunk data 同包（ClientboundLevelChunkWithLightPacket）
        PacketBuf chunk = new PacketBuf();
        chunk.writeInt(cx);
        chunk.writeInt(cz);
        chunk.writeBytes(encoded.chunkDataPayload(), 0, encoded.chunkDataPayload().length);
        chunk.writeBytes(encoded.lightDataPayload(), 0, encoded.lightDataPayload().length);
        this.connection.send(PacketIds.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT, chunk);
    }

    // ---- keepalive ----

    private void scheduleKeepAlive() {
        long interval = this.config.keepAliveIntervalMillis();
        this.keepAliveTask = this.connection.ctx().executor().scheduleAtFixedRate(() -> {
            if (!this.connection.isConnected()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (this.pendingKeepAliveId >= 0) {
                this.connection.disconnect("keepalive 超时");
                return;
            }
            this.pendingKeepAliveId = now;
            PacketBuf keepAlive = new PacketBuf();
            keepAlive.writeLong(now);
            this.connection.send(PacketIds.CLIENTBOUND_KEEP_ALIVE, keepAlive);
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    // ---- 入站 ----

    @Override
    public boolean handle(int packetId, PacketBuf body) {
        switch (packetId) {
            case PacketIds.SERVERBOUND_KEEP_ALIVE -> {
                long id = body.readLong();
                if (id == this.pendingKeepAliveId) {
                    this.pendingKeepAliveId = -1;
                }
                return true;
            }
            case PacketIds.SERVERBOUND_ACCEPT_TELEPORTATION -> {
                body.readVarInt();
                this.accepted = true;
                return true;
            }
            case PacketIds.SERVERBOUND_MOVE_PLAYER_POS, PacketIds.SERVERBOUND_MOVE_PLAYER_POS_ROT -> {
                this.x = body.readDouble();
                this.y = body.readDouble();
                this.z = body.readDouble();
                if (packetId == PacketIds.SERVERBOUND_MOVE_PLAYER_POS_ROT) {
                    this.yaw = body.readFloat();
                    this.pitch = body.readFloat();
                }
                body.readByte(); // flags
                this.onMove();
                return true;
            }
            case PacketIds.SERVERBOUND_MOVE_PLAYER_ROT -> {
                this.yaw = body.readFloat();
                this.pitch = body.readFloat();
                body.readByte();
                return true;
            }
            case PacketIds.SERVERBOUND_MOVE_PLAYER_STATUS_ONLY -> {
                body.readByte();
                return true;
            }
            case PacketIds.SERVERBOUND_PLAYER_LOADED, PacketIds.SERVERBOUND_PONG,
                    PacketIds.SERVERBOUND_CLIENT_INFORMATION, PacketIds.SERVERBOUND_PADDLE_BOAT,
                    PacketIds.SERVERBOUND_PLAYER_ABILITIES, PacketIds.SERVERBOUND_SET_CARRIED_ITEM,
                    PacketIds.SERVERBOUND_CHAT, PacketIds.SERVERBOUND_CHAT_COMMAND -> {
                // 旁观者支持的非互动包：接受并忽略
                return true;
            }
            case PacketIds.SERVERBOUND_USE_ITEM, PacketIds.SERVERBOUND_USE_ITEM_ON,
                    PacketIds.SERVERBOUND_INTERACT, PacketIds.SERVERBOUND_PLAYER_ACTION,
                    PacketIds.SERVERBOUND_PLAYER_COMMAND, PacketIds.SERVERBOUND_PLAYER_INPUT,
                    PacketIds.SERVERBOUND_SPECTATOR_ACTION, PacketIds.SERVERBOUND_TELEPORT_TO_ENTITY,
                    PacketIds.SERVERBOUND_PUNCH -> {
                // 世界互动：拒绝且不应用（旁观者模式；fail closed，无写路径）
                LOGGER.warn("[vb] 拒绝世界互动包 id={}（玩家 {}）", packetId, this.playerId);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void onMove() {
        int newChunkX = floorDiv((int) this.x, 16);
        int newChunkZ = floorDiv((int) this.z, 16);
        if (newChunkX != this.chunkX || newChunkZ != this.chunkZ) {
            this.chunkX = newChunkX;
            this.chunkZ = newChunkZ;
            this.updateChunkWindow();
        }
    }

    @Override
    public void onDisconnect(String reason) {
        if (this.keepAliveTask != null) {
            this.keepAliveTask.cancel(false);
        }
        LOGGER.info("[vb] 会话结束: player={} reason={}", this.playerId, reason);
    }

    // ---- 工具 ----

    private static int floorDiv(int a, int b) {
        return Math.floorDiv(a, b);
    }

    private static long packChunkPos(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    /** 便于测试：当前中心区块。 */
    public int centerChunkX() {
        return this.centerX;
    }

    public int centerChunkZ() {
        return this.centerZ;
    }

    public int sentChunkCount() {
        return this.sentChunks.size();
    }
}

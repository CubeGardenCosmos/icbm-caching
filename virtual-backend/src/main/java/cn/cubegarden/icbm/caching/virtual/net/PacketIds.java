package cn.cubegarden.icbm.caching.virtual.net;

/**
 * 26.3-snapshot-8 线上包 ID 常量表。
 *
 * <p>来源：反编译源码各 Protocol 类的 {@code addPacket} 注册顺序（
 * {@code ProtocolCodecBuilder}→{@code IdDispatchCodec}：id = 注册序号，clientbound 与
 * serverbound 独立编号）。行号对应：
 * <ul>
 *   <li>LoginProtocols.java（login）；</li>
 *   <li>ConfigurationProtocols.java（configuration）；</li>
 *   <li>GameProtocols.java（play，142 clientbound / 68 serverbound）；</li>
 *   <li>HandshakeProtocols.java / StatusProtocols.java。</li>
 * </ul>
 * 冻结版本唯一，禁止从 1.21.x/26.2 记忆值替代（AGENTS.md 26.3 常量约束）。
 */
public final class PacketIds {

    private PacketIds() {
    }

    // ---- HANDSHAKE ----
    public static final int SERVERBOUND_INTENTION = 0; // HandshakeProtocols

    // ---- STATUS ----
    public static final int SERVERBOUND_STATUS_REQUEST = 0; // StatusProtocols
    public static final int SERVERBOUND_PING_REQUEST = 1;   // StatusProtocols
    public static final int CLIENTBOUND_STATUS_RESPONSE = 0; // StatusProtocols
    public static final int CLIENTBOUND_PONG_RESPONSE = 1;   // StatusProtocols

    // ---- LOGIN clientbound（LoginProtocols）----
    public static final int CLIENTBOUND_LOGIN_DISCONNECT = 0;
    public static final int CLIENTBOUND_HELLO = 1;
    public static final int CLIENTBOUND_LOGIN_FINISHED = 2;
    public static final int CLIENTBOUND_LOGIN_COMPRESSION = 3;
    public static final int CLIENTBOUND_CUSTOM_QUERY = 4;
    public static final int CLIENTBOUND_COOKIE_REQUEST = 5;

    // ---- LOGIN serverbound（LoginProtocols）----
    public static final int SERVERBOUND_HELLO = 0;
    public static final int SERVERBOUND_KEY = 1;
    public static final int SERVERBOUND_CUSTOM_QUERY_ANSWER = 2;
    public static final int SERVERBOUND_LOGIN_ACKNOWLEDGED = 3;
    public static final int SERVERBOUND_COOKIE_RESPONSE = 4;

    // ---- CONFIGURATION clientbound（ConfigurationProtocols）----
    public static final int CLIENTBOUND_CONFIG_COOKIE_REQUEST = 0;
    public static final int CLIENTBOUND_CONFIG_CUSTOM_PAYLOAD = 1;
    public static final int CLIENTBOUND_CONFIG_DISCONNECT = 2;
    public static final int CLIENTBOUND_FINISH_CONFIGURATION = 3;
    public static final int CLIENTBOUND_CONFIG_KEEP_ALIVE = 4;
    public static final int CLIENTBOUND_CONFIG_PING = 5;
    public static final int CLIENTBOUND_RESET_CHAT = 6;
    public static final int CLIENTBOUND_REGISTRY_DATA = 7;
    public static final int CLIENTBOUND_CONFIG_RESOURCE_PACK_POP = 8;
    public static final int CLIENTBOUND_CONFIG_RESOURCE_PACK_PUSH = 9;
    public static final int CLIENTBOUND_POST_EFFECTS = 10;
    public static final int CLIENTBOUND_CONFIG_STORE_COOKIE = 11;
    public static final int CLIENTBOUND_CONFIG_TRANSFER = 12;
    public static final int CLIENTBOUND_UPDATE_ENABLED_FEATURES = 13;
    public static final int CLIENTBOUND_CONFIG_UPDATE_TAGS = 14;
    public static final int CLIENTBOUND_SELECT_KNOWN_PACKS = 15;
    public static final int CLIENTBOUND_CONFIG_CUSTOM_REPORT_DETAILS = 16;
    public static final int CLIENTBOUND_CONFIG_SERVER_LINKS = 17;
    public static final int CLIENTBOUND_CLEAR_DIALOG = 18;
    public static final int CLIENTBOUND_SHOW_DIALOG = 19;
    public static final int CLIENTBOUND_CODE_OF_CONDUCT = 20;

    // ---- CONFIGURATION serverbound（ConfigurationProtocols）----
    public static final int SERVERBOUND_CONFIG_CLIENT_INFORMATION = 0;
    public static final int SERVERBOUND_CONFIG_COOKIE_RESPONSE = 1;
    public static final int SERVERBOUND_CONFIG_CUSTOM_PAYLOAD = 2;
    public static final int SERVERBOUND_FINISH_CONFIGURATION = 3;
    public static final int SERVERBOUND_CONFIG_KEEP_ALIVE = 4;
    public static final int SERVERBOUND_CONFIG_PONG = 5;
    public static final int SERVERBOUND_CONFIG_RESOURCE_PACK = 6;
    public static final int SERVERBOUND_SELECT_KNOWN_PACKS = 7;
    public static final int SERVERBOUND_CONFIG_CUSTOM_CLICK_ACTION = 8;
    public static final int SERVERBOUND_ACCEPT_CODE_OF_CONDUCT = 9;

    // ---- PLAY clientbound（GameProtocols，仅虚拟后端使用子集列出，其余见完整注册顺序）----
    public static final int CLIENTBOUND_CHANGE_DIFFICULTY = 9;
    public static final int CLIENTBOUND_CUSTOM_PAYLOAD = 23;
    public static final int CLIENTBOUND_DISCONNECT = 31;
    public static final int CLIENTBOUND_FORGET_LEVEL_CHUNK = 36;
    public static final int CLIENTBOUND_KEEP_ALIVE = 43;
    public static final int CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT = 44;
    public static final int CLIENTBOUND_LIGHT_UPDATE = 47;
    public static final int CLIENTBOUND_LOGIN = 48;
    public static final int CLIENTBOUND_PLAYER_ABILITIES = 63;
    public static final int CLIENTBOUND_PLAYER_INFO_REMOVE = 68;
    public static final int CLIENTBOUND_PLAYER_INFO_UPDATE = 69;
    public static final int CLIENTBOUND_PLAYER_POSITION = 71;
    public static final int CLIENTBOUND_PLAYER_ROTATION = 72;
    public static final int CLIENTBOUND_RESPAWN = 82;
    public static final int CLIENTBOUND_SET_CAMERA = 93;
    public static final int CLIENTBOUND_SET_CHUNK_CACHE_CENTER = 94;
    public static final int CLIENTBOUND_SET_CHUNK_CACHE_RADIUS = 95;
    public static final int CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION = 97;
    public static final int CLIENTBOUND_SET_EXPERIENCE = 103;
    public static final int CLIENTBOUND_SET_HEALTH = 104;
    public static final int CLIENTBOUND_SET_SIMULATION_DISTANCE = 111;
    public static final int CLIENTBOUND_SET_TIME = 113;
    public static final int CLIENTBOUND_SYSTEM_CHAT = 122;
    public static final int CLIENTBOUND_UPDATE_TAGS = 135;
    public static final int CLIENTBOUND_BUNDLE = 0; // 未使用（bundle 分界包），仅供对照

    // ---- PLAY serverbound（GameProtocols）----
    public static final int SERVERBOUND_ACCEPT_TELEPORTATION = 0;
    public static final int SERVERBOUND_CLIENT_INFORMATION = 14;
    public static final int SERVERBOUND_KEEP_ALIVE = 27;
    public static final int SERVERBOUND_MOVE_PLAYER_POS = 29;
    public static final int SERVERBOUND_MOVE_PLAYER_POS_ROT = 30;
    public static final int SERVERBOUND_MOVE_PLAYER_ROT = 31;
    public static final int SERVERBOUND_MOVE_PLAYER_STATUS_ONLY = 32;
    public static final int SERVERBOUND_PLAYER_ABILITIES = 39;
    public static final int SERVERBOUND_PLAYER_ACTION = 40;
    public static final int SERVERBOUND_PLAYER_COMMAND = 41;
    public static final int SERVERBOUND_PLAYER_LOADED = 43;
    public static final int SERVERBOUND_PONG = 44;
    public static final int SERVERBOUND_CHAT = 9;
    public static final int SERVERBOUND_CHAT_COMMAND = 7;
    public static final int SERVERBOUND_USE_ITEM = 67;
    public static final int SERVERBOUND_USE_ITEM_ON = 66;
    public static final int SERVERBOUND_INTERACT = 25;
    public static final int SERVERBOUND_PLAYER_INPUT = 42;
    public static final int SERVERBOUND_PADDLE_BOAT = 34;
    public static final int SERVERBOUND_SET_CARRIED_ITEM = 53;
    public static final int SERVERBOUND_SPECTATOR_ACTION = 62;
    public static final int SERVERBOUND_TELEPORT_TO_ENTITY = 63;
    public static final int SERVERBOUND_PUNCH = 45;
}

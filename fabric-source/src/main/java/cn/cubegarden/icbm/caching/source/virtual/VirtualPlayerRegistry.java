package cn.cubegarden.icbm.caching.source.virtual;

import cn.cubegarden.icbm.caching.source.backend.BackendControlSession;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 虚拟玩家会话注册表（阶段 9.4）：MC_PASSTHROUGH body v2
 * （[playerUuid(16)][流序号(8)][长度(4)][payload]）按 playerUuid 路由到
 * {@link VirtualPlayerSession}——首个报文（uuid 首次见）创建虚拟玩家，后续注入。
 *
 * <p>生命周期：slave 断开/backend 断线 → {@link #closeAll()} 销毁全部虚拟会话（源服玩家被
 * 踢出，快照管道自然回滚）；重连后 uuid 首次报文物化新会话（虚拟玩家重新登录）。
 */
public final class VirtualPlayerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualPlayerRegistry.class);

    private final MinecraftServer server;
    private final Map<String, VirtualPlayerSession> byUuidHex = new ConcurrentHashMap<>();

    public VirtualPlayerRegistry(MinecraftServer server) {
        this.server = server;
    }

    /** backend onMcPassthrough（backend reader 线程）：解析 body v2 → 路由虚拟会话注入。 */
    public void onMcPassthrough(BackendControlSession backend, byte[] body) {
        if (body.length < 28) {
            LOGGER.error("[icbm-source] MC_PASSTHROUGH body 过短（{} 字节，fail closed 丢弃）",
                    body.length);
            return;
        }
        UUID playerUuid = new UUID(
                ByteBuffer.wrap(body, 0, 16).getLong(),
                ByteBuffer.wrap(body, 8, 8).getLong());
        int payloadLength = ByteBuffer.wrap(body, 24, 4).getInt();
        if (payloadLength < 0 || 28 + payloadLength > body.length) {
            LOGGER.error("[icbm-source] MC_PASSTHROUGH 长度越界（{}，fail closed 丢弃）",
                    payloadLength);
            return;
        }
        byte[] payload = Arrays.copyOfRange(body, 28, 28 + payloadLength);
        String key = playerUuid.toString();
        VirtualPlayerSession session = this.byUuidHex.computeIfAbsent(key,
                k -> VirtualPlayerSession.open(this.server, playerUuid, backend));
        session.inject(payload);
        LOGGER.info("[icbm-source] MC_PASSTHROUGH 上行注入虚拟玩家: {} {}字节 ready={}",
                key, payload.length, session.isReady());
    }

    /** backend 断线/会话销毁：全部虚拟玩家踢出（fail closed：不保留悬挂会话）。 */
    public void closeAll() {
        for (VirtualPlayerSession session : this.byUuidHex.values()) {
            session.close();
        }
        this.byUuidHex.clear();
    }
}

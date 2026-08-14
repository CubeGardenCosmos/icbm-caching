package cn.cubegarden.icbm.caching.virtual.session;

import cn.cubegarden.icbm.caching.virtual.net.ClientConnection;
import cn.cubegarden.icbm.caching.virtual.net.PacketIds;
import cn.cubegarden.icbm.caching.virtual.net.ProtocolState;
import cn.cubegarden.icbm.caching.virtual.net.SessionHandler;
import cn.cubegarden.icbm.caching.virtual.registry.RegistryBundle;
import cn.cubegarden.icbm.caching.virtual.world.Nbt;
import cn.cubegarden.icbm.caching.virtual.world.PacketBuf;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CONFIGURATION 会话（net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java
 * + SynchronizeRegistriesTask.java 对照）：
 * Brand → EnabledFeatures → SelectKnownPacks(空表) → （客户端回选择）→ 每注册表
 * RegistryData → UpdateTags → FinishConfiguration → （客户端确认）→ PLAY。
 *
 * <p>已知包协商：空表（无资源包/已知包），客户端回空表 → 服务端发送完整注册表数据
 * （SynchronizeRegistriesTask.handleResponse 空协商集路径）。
 */
public final class ConfigurationSession implements SessionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationSession.class);

    private final ClientConnection connection;
    private final RegistryBundle registry;
    private final SessionFactory factory;

    public interface SessionFactory {
        SessionHandler newSession(ProtocolState state, ClientConnection connection);
    }

    public ConfigurationSession(ClientConnection connection, RegistryBundle registry, SessionFactory factory) {
        this.connection = connection;
        this.registry = registry;
        this.factory = factory;
    }

    @Override
    public void onActivated() {
        // 1) Brand
        PacketBuf brand = new PacketBuf();
        brand.writeUtf("minecraft:brand");
        brand.writeUtf("icbm-virtual-backend");
        this.connection.send(PacketIds.CLIENTBOUND_CONFIG_CUSTOM_PAYLOAD, brand);

        // 2) EnabledFeatures
        PacketBuf features = new PacketBuf();
        List<String> featureList = this.registry.features();
        features.writeVarInt(featureList.size());
        for (String feature : featureList) {
            features.writeUtf(feature);
        }
        this.connection.send(PacketIds.CLIENTBOUND_UPDATE_ENABLED_FEATURES, features);

        // 3) SelectKnownPacks（空表）
        PacketBuf knownPacks = new PacketBuf();
        knownPacks.writeVarInt(0);
        this.connection.send(PacketIds.CLIENTBOUND_SELECT_KNOWN_PACKS, knownPacks);
    }

    @Override
    public boolean handle(int packetId, PacketBuf body) {
        switch (packetId) {
            case PacketIds.SERVERBOUND_SELECT_KNOWN_PACKS -> {
                int count = body.readVarInt();
                for (int i = 0; i < count; i++) {
                    body.readUtf(); // namespace
                    body.readUtf(); // id
                    body.readUtf(); // version
                }
                this.sendRegistriesAndTags();
                return true;
            }
            case PacketIds.SERVERBOUND_FINISH_CONFIGURATION -> {
                this.connection.setupState(ProtocolState.PLAY, this.factory.newSession(ProtocolState.PLAY, this.connection));
                return true;
            }
            case PacketIds.SERVERBOUND_CONFIG_CLIENT_INFORMATION -> {
                // 解析并忽略（视距等由我们按配置控制）
                body.readUtf(); // language
                body.readByte(); // view distance
                body.readByte(); // chat visibility
                body.readBoolean(); // chat colors
                body.readByte(); // model customisation
                body.readByte(); // main hand
                body.readBoolean(); // text filtering
                body.readBoolean(); // allows listing
                body.readByte(); // particle status
                return true;
            }
            case PacketIds.SERVERBOUND_CONFIG_KEEP_ALIVE, PacketIds.SERVERBOUND_CONFIG_PONG -> {
                body.readLong();
                return true;
            }
            default -> {
                LOGGER.debug("[vb] 配置阶段忽略包 id={}", packetId);
                return true;
            }
        }
    }

    private void sendRegistriesAndTags() {
        for (Map.Entry<String, List<RegistryBundle.RegistryEntry>> registryEntry
                : this.registry.registries().entrySet()) {
            PacketBuf data = new PacketBuf();
            data.writeUtf(registryEntry.getKey());
            List<RegistryBundle.RegistryEntry> entries = registryEntry.getValue();
            data.writeVarInt(entries.size());
            for (RegistryBundle.RegistryEntry entry : entries) {
                data.writeUtf(entry.id());
                if (entry.tag() == null) {
                    data.writeByte(0);
                } else {
                    data.writeByte(1);
                    data.writeBytes(entry.tag(), 0, entry.tag().length);
                }
            }
            this.connection.send(PacketIds.CLIENTBOUND_REGISTRY_DATA, data);
        }

        // UpdateTags
        PacketBuf tags = new PacketBuf();
        Map<String, Map<String, int[]>> allTags = this.registry.tags();
        tags.writeVarInt(allTags.size());
        for (Map.Entry<String, Map<String, int[]>> registryTags : allTags.entrySet()) {
            tags.writeUtf(registryTags.getKey());
            tags.writeVarInt(registryTags.getValue().size());
            for (Map.Entry<String, int[]> tag : registryTags.getValue().entrySet()) {
                tags.writeUtf(tag.getKey());
                int[] ids = tag.getValue();
                tags.writeVarInt(ids.length);
                for (int id : ids) {
                    tags.writeVarInt(id);
                }
            }
        }
        this.connection.send(PacketIds.CLIENTBOUND_CONFIG_UPDATE_TAGS, tags);

        // FinishConfiguration
        this.connection.send(PacketIds.CLIENTBOUND_FINISH_CONFIGURATION, new PacketBuf());
        LOGGER.info("[vb] 配置同步完成: registries={} tags={}", registry.registries().size(), allTags.size());
    }
}

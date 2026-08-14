package cn.cubegarden.icbm.caching.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 构建期注册表转储（草稿 phase3-virtual-backend §2「静态数据」）：
 * 服务端启动后把以下确定性数据写到 {@code -Dicbm.dump.dir=...} 指向的目录：
 *
 * <ul>
 *   <li>blockstates.json：Block.BLOCK_STATE_REGISTRY 全局 ID 表（name+props/air/fluid）；</li>
 *   <li>registries.json：SYNCHRONIZED_REGISTRIES 每注册表条目（id + NBT，base64）；</li>
 *   <li>tags.json：TagNetworkSerialization 网络 payload（注册表 id 列表）；</li>
 *   <li>features.json：启用的 FeatureFlags；</li>
 *   <li>world.json：维度类型顺序/level 列表/seed/seaLevel/min-max section Y/spawn/难度；</li>
 *   <li>blockentitytypes.json / biomes.json：类型与 biome 注册表 ID 顺序；</li>
 *   <li>manifest.json：版本 + 指纹（排除本转储模组自身，与运行时部署一致）。</li>
 * </ul>
 *
 * <p>该模组不注册任何内容，不改变注册表/数据包；仅构建期使用，不随运行时部署。
 */
public final class RegistryDump implements net.fabricmc.api.ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryDump.class);
    private static final String DUMP_MOD_ID = "icbm_registry_dump";

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::dump);
    }

    private void dump(MinecraftServer server) {
        String dir = System.getProperty("icbm.dump.dir");
        if (dir == null) {
            dir = System.getenv("ICBM_DUMP_DIR");
        }
        if (dir == null) {
            LOGGER.info("[icbm-dump] 未设置 icbm.dump.dir，跳过转储");
            return;
        }
        try {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            this.write(out, "manifest.json", this.manifest(server));
            this.write(out, "blockstates.json", this.blockStates());
            this.write(out, "registries.json", this.registries(server));
            this.write(out, "tags.json", this.tags(server));
            this.write(out, "features.json", this.features(server));
            this.write(out, "world.json", this.world(server));
            this.write(out, "blockentitytypes.json", this.blockEntityTypes());
            this.write(out, "biomes.json", this.biomes(server));
            Files.writeString(out.resolve("DONE"), "ok\n", StandardCharsets.UTF_8);
            LOGGER.info("[icbm-dump] 转储完成: {}", out);
        } catch (Exception e) {
            LOGGER.error("[icbm-dump] 转储失败", e);
        }
    }

    private void write(Path dir, String name, JsonObject json) throws IOException {
        Files.writeString(dir.resolve(name), this.gson.toJson(json) + "\n", StandardCharsets.UTF_8);
        LOGGER.info("[icbm-dump] 写出 {}", name);
    }

    // ---- manifest ----

    private JsonObject manifest(MinecraftServer server) throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
        json.addProperty("runtimeProtocol", SharedConstants.getProtocolVersion());
        json.addProperty("dataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        json.addProperty("modpackFingerprint", this.modpackFingerprint(server));
        json.addProperty("registryAndDatapackFingerprint", this.registryAndDatapackFingerprint(server));
        json.addProperty("generatedAt", System.currentTimeMillis());
        return json;
    }

    /** 与 icbm_source.ModpackFingerprint.compute 同构，但排除本转储模组自身。 */
    private String modpackFingerprint(MinecraftServer server) {
        List<String> parts = new ArrayList<>();
        parts.add(SharedConstants.getCurrentVersion().name());
        FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString())
                .filter(s -> !s.startsWith(DUMP_MOD_ID + "@"))
                .sorted()
                .forEach(parts::add);
        server.getPackRepository().getSelectedIds().stream()
                .sorted()
                .forEach(parts::add);
        return sha256Hex(String.join("|", parts));
    }

    /** 与 icbm_source.ModpackFingerprint.registryAndDatapackFingerprint 同构。 */
    private String registryAndDatapackFingerprint(MinecraftServer server) throws Exception {
        List<String> parts = new ArrayList<>();
        RegistryAccess registryAccess = server.registryAccess();
        registryAccess.registries().forEach(entry -> {
            List<String> ids = new ArrayList<>();
            entry.value().keySet().stream().map(Identifier::toString).sorted().forEach(ids::add);
            parts.add(entry.key().identifier().toString() + "=" + String.join(",", ids));
        });
        List<String> packFiles = new ArrayList<>();
        server.getResourceManager().listResources("", resourceId -> true).entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .forEach(e -> {
                    try (var in = e.getValue().open()) {
                        packFiles.add(e.getKey() + "@" + sha256HexBytes(in.readAllBytes()));
                    } catch (Exception ex) {
                        throw new IllegalStateException("数据包资源读取失败: " + e.getKey(), ex);
                    }
                });
        parts.addAll(packFiles);
        return sha256Hex(String.join("|", parts));
    }

    // ---- blockstates ----

    private JsonObject blockStates() {
        JsonArray states = new JsonArray();
        int count = Block.BLOCK_STATE_REGISTRY.size();
        for (int id = 0; id < count; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            JsonObject props = new JsonObject();
            state.getValues().forEach(p -> props.addProperty(p.property().getName(), valueName(p.property(), p.value())));
            entry.add("props", props);
            entry.addProperty("air", state.isAir());
            entry.addProperty("fluid", !state.getFluidState().isEmpty());
            states.add(entry);
        }
        // 每方块默认状态 id（快照中纯字符串状态 = 默认状态，见 ChunkDataEncoder.stateId）
        JsonObject defaults = new JsonObject();
        BuiltInRegistries.BLOCK.entrySet().forEach(entry -> {
            Block block = entry.getValue();
            int id = Block.BLOCK_STATE_REGISTRY.getId(block.defaultBlockState());
            defaults.addProperty(entry.getKey().identifier().toString(), id);
        });
        JsonObject json = new JsonObject();
        json.add("states", states);
        json.add("defaults", defaults);
        return json;
    }

    // ---- registries ----

    private JsonObject registries(MinecraftServer server) {
        JsonArray registries = new JsonArray();
        RegistrySynchronization.packRegistries(
                // 与 ServerConfigurationPacketListenerImpl 一致：composite 上下文（含 static 层），
                // 否则 sulfur_cube_archetype 等 codec 无法解析 minecraft:item
                server.registries().compositeAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                server.registryAccess(),
                Set.of(),
                (key, entries) -> {
                    JsonObject registry = new JsonObject();
                    registry.addProperty("key", key.identifier().toString());
                    JsonArray list = new JsonArray();
                    for (RegistrySynchronization.PackedRegistryEntry entry : entries) {
                        JsonObject item = new JsonObject();
                        item.addProperty("id", entry.id().toString());
                        if (entry.data().isPresent()) {
                            item.addProperty("tag", base64Tag(entry.data().get()));
                        }
                        list.add(item);
                    }
                    registry.add("entries", list);
                    registries.add(registry);
                });
        JsonObject json = new JsonObject();
        json.add("registries", registries);
        return json;
    }

    private static String base64Tag(Tag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            NbtIo.writeAnyTag(tag, out);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("NBT 序列化失败", e);
        }
    }

    // ---- tags ----

    private JsonObject tags(MinecraftServer server) {
        JsonArray registries = new JsonArray();
        TagNetworkSerialization.serializeTagsToNetwork(server.registries())
                .forEach((key, payload) -> {
                    JsonObject registry = new JsonObject();
                    registry.addProperty("key", key.identifier().toString());
                    JsonObject tags = new JsonObject();
                    payload.tags().forEach((name, ids) -> {
                        JsonArray idArray = new JsonArray();
                        ids.intStream().forEach(idArray::add);
                        tags.add(name.toString(), idArray);
                    });
                    registry.add("tags", tags);
                    registries.add(registry);
                });
        JsonObject json = new JsonObject();
        json.add("registries", registries);
        return json;
    }

    // ---- features ----

    private JsonObject features(MinecraftServer server) {
        JsonArray features = new JsonArray();
        net.minecraft.world.flag.FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures())
                .stream().map(Identifier::toString).forEach(features::add);
        JsonObject json = new JsonObject();
        json.add("features", features);
        return json;
    }

    // ---- world ----

    private JsonObject world(MinecraftServer server) {
        WorldData worldData = server.getWorldData();
        RegistryAccess registries = server.registryAccess();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("overworld 未加载");
        }

        Registry<DimensionType> dimensionTypes = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        JsonArray dimensionTypeIds = new JsonArray();
        dimensionTypes.listElements().forEach(e -> dimensionTypeIds.add(e.key().identifier().toString()));
        Registry<Level> levels = registries.lookupOrThrow(Registries.DIMENSION);
        JsonArray levelIds = new JsonArray();
        levels.listElements().forEach(e -> levelIds.add(e.key().identifier().toString()));

        net.minecraft.world.level.storage.LevelData.RespawnData respawn = overworld.getRespawnData();

        JsonObject json = new JsonObject();
        json.add("dimensionTypeIds", dimensionTypeIds);
        json.add("levels", levelIds);
        json.addProperty("seed", server.getWorldGenSettings().options().seed());
        json.addProperty("seaLevel", overworld.getSeaLevel());
        json.addProperty("minSectionY", overworld.dimensionType().minY() / 16);
        json.addProperty("maxSectionY", (overworld.dimensionType().minY() + overworld.dimensionType().height()) / 16 - 1);
        json.addProperty("dimensionTypeId", overworld.dimensionTypeRegistration().unwrapKey().get().identifier().toString());
        json.addProperty("difficulty", worldData.getDifficulty().getSerializedName());
        json.addProperty("spawnDimension", respawn.globalPos().dimension().identifier().toString());
        json.addProperty("spawnX", respawn.globalPos().pos().getX());
        json.addProperty("spawnY", respawn.globalPos().pos().getY());
        json.addProperty("spawnZ", respawn.globalPos().pos().getZ());
        json.addProperty("spawnYaw", respawn.yaw());
        json.addProperty("spawnPitch", respawn.pitch());
        return json;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(Property property, Comparable value) {
        return property.getName(value);
    }

    // ---- block entity types ----

    private JsonObject blockEntityTypes() {
        JsonObject types = new JsonObject();
        for (Map.Entry<ResourceKey<BlockEntityType<?>>, BlockEntityType<?>> entry
                : BuiltInRegistries.BLOCK_ENTITY_TYPE.entrySet()) {
            types.addProperty(entry.getKey().identifier().toString(), BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(entry.getValue()));
        }
        JsonObject json = new JsonObject();
        json.add("types", types);
        return json;
    }

    // ---- biomes ----

    private JsonObject biomes(MinecraftServer server) {
        Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        JsonArray list = new JsonArray();
        biomes.listElements().forEach(e -> list.add(e.key().identifier().toString()));
        JsonObject json = new JsonObject();
        json.add("biomes", list);
        return json;
    }

    // ---- utils ----

    private static String sha256Hex(String input) {
        return sha256HexBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256HexBytes(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

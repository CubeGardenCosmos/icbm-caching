package cn.cubegarden.icbm.caching.virtual.registry;

import cn.cubegarden.icbm.caching.virtual.config.VirtualBackendConfig;
import cn.cubegarden.icbm.caching.virtual.world.Nbt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 26.3 静态数据包（构建期从源服转储，草稿 phase3-virtual-backend §2/§3）：
 *
 * <ul>
 *   <li>block state 全局 ID 表（name+props → id，含 air/fluid 标志）；</li>
 *   <li>SYNCHRONIZED_REGISTRIES 条目（线上 registry data 包的 NBT 字节）；</li>
 *   <li>tags/features/world/block entity 类型/biome 顺序；</li>
 *   <li>manifest 指纹（启动校验，不一致 → fail closed）。</li>
 * </ul>
 *
 * <p>资源路径约定：类路径 {@code icbm/registry/<name>.json.gz}（转储为未压缩 JSON，
 * 打包时压缩）。
 */
public final class RegistryBundle {

    public static final String REGISTRY_RESOURCE_ROOT = "icbm/registry/";

    /** 方块状态（按全局 id 索引）。 */
    public record BlockStateEntry(int id, String name, Map<String, String> props, boolean air, boolean fluid) {
    }

    /** 同步注册表条目（线上 registry data 包元素：id + 可选 NBT 字节）。 */
    public record RegistryEntry(String id, byte[] tag) {
    }

    public record RegistryData(String key, List<RegistryEntry> entries) {
    }

    public record TagData(String key, Map<String, int[]> tags) {
    }

    private final Map<String, BlockStateEntry> blockStatesByName = new LinkedHashMap<>();
    private final List<BlockStateEntry> blockStatesById = new ArrayList<>();
    private final Map<String, Integer> blockStateDefaults = new HashMap<>();
    private final Map<String, List<RegistryEntry>> registries = new LinkedHashMap<>();
    private final Map<String, Map<String, int[]>> tags = new LinkedHashMap<>();
    private final List<String> features = new ArrayList<>();
    private final List<String> dimensionTypeIds = new ArrayList<>();
    private final List<String> levelIds = new ArrayList<>();
    private final Map<String, Integer> blockEntityTypeIds = new HashMap<>();
    private final List<String> biomeIds = new ArrayList<>();
    private final Map<String, String> manifest = new LinkedHashMap<>();
    private final Map<String, Object> world = new LinkedHashMap<>();

    private RegistryBundle() {
    }

    public static RegistryBundle load(ClassLoader classLoader) throws IOException {
        RegistryBundle bundle = new RegistryBundle();
        bundle.parse(classLoader);
        return bundle;
    }

    public static RegistryBundle load(ClassLoader classLoader, java.nio.file.Path overrideDir) throws IOException {
        RegistryBundle bundle = new RegistryBundle();
        bundle.parseDir(overrideDir);
        return bundle;
    }

    private void parse(ClassLoader classLoader) throws IOException {
        this.parseDir(null);
    }

    private void parseDir(java.nio.file.Path overrideDir) throws IOException {
        this.loadFile(overrideDir, "manifest.json");
        this.loadFile(overrideDir, "blockstates.json");
        this.loadFile(overrideDir, "registries.json");
        this.loadFile(overrideDir, "tags.json");
        this.loadFile(overrideDir, "features.json");
        this.loadFile(overrideDir, "world.json");
        this.loadFile(overrideDir, "blockentitytypes.json");
        this.loadFile(overrideDir, "biomes.json");
    }

    private void loadFile(java.nio.file.Path overrideDir, String name) throws IOException {
        Json.Value value;
        if (overrideDir != null && java.nio.file.Files.isRegularFile(overrideDir.resolve(name))) {
            try (InputStream in = java.nio.file.Files.newInputStream(overrideDir.resolve(name))) {
                value = Json.parseGzip(in);
            }
        } else {
            try (InputStream in = this.getClass().getClassLoader().getResourceAsStream(REGISTRY_RESOURCE_ROOT + name + ".gz")) {
                if (in == null) {
                    throw new IOException("registry 资源缺失: " + name + ".gz");
                }
                value = Json.parseGzip(in);
            }
        }
        this.parseFile(name, value);
    }

    private void parseFile(String name, Json.Value value) {
        switch (name) {
            case "manifest.json" -> this.parseManifest(value.asObject());
            case "blockstates.json" -> this.parseBlockStates(value.asObject());
            case "registries.json" -> this.parseRegistries(value.asObject());
            case "tags.json" -> this.parseTags(value.asObject());
            case "features.json" -> this.parseFeatures(value.asObject());
            case "world.json" -> this.parseWorld(value.asObject());
            case "blockentitytypes.json" -> this.parseBlockEntityTypes(value.asObject());
            case "biomes.json" -> this.parseBiomes(value.asObject());
            default -> throw new IllegalArgumentException("unknown registry file: " + name);
        }
    }

    private void parseManifest(Map<String, Json.Value> json) {
        for (Map.Entry<String, Json.Value> e : json.entrySet()) {
            Json.Value v = e.getValue();
            this.manifest.put(e.getKey(), switch (v.kind()) {
                case STRING -> v.asString();
                case LONG -> Long.toString(v.asLong());
                case DOUBLE -> Double.toString(v.asLong());
                case BOOLEAN -> Boolean.toString(v.asBoolean());
                case NULL -> "";
                default -> throw new IllegalArgumentException("manifest 不支持的类型: " + v.kind());
            });
        }
    }

    private void parseBlockStates(Map<String, Json.Value> json) {
        for (Json.Value item : json.get("states").asArray()) {
            Map<String, Json.Value> entry = item.asObject();
            int id = (int) entry.get("id").asLong();
            String name = entry.get("name").asString();
            Map<String, String> props = new LinkedHashMap<>();
            for (Map.Entry<String, Json.Value> p : entry.get("props").asObject().entrySet()) {
                props.put(p.getKey(), p.getValue().asString());
            }
            boolean air = entry.get("air").asBoolean();
            boolean fluid = entry.get("fluid").asBoolean();
            BlockStateEntry state = new BlockStateEntry(id, name, props, air, fluid);
            this.blockStatesByName.put(stateKey(name, props), state);
            this.blockStatesById.add(state);
        }
        Json.Value defaults = json.get("defaults");
        if (defaults != null) {
            for (Map.Entry<String, Json.Value> e : defaults.asObject().entrySet()) {
                this.blockStateDefaults.put(e.getKey(), (int) e.getValue().asLong());
            }
        }
    }

    private void parseRegistries(Map<String, Json.Value> json) {
        for (Json.Value item : json.get("registries").asArray()) {
            Map<String, Json.Value> registry = item.asObject();
            String key = registry.get("key").asString();
            List<RegistryEntry> entries = new ArrayList<>();
            for (Json.Value entryValue : registry.get("entries").asArray()) {
                Map<String, Json.Value> entry = entryValue.asObject();
                String id = entry.get("id").asString();
                Json.Value tag = entry.get("tag");
                byte[] tagBytes = tag == null ? null : Base64.getDecoder().decode(tag.asString());
                entries.add(new RegistryEntry(id, tagBytes));
            }
            this.registries.put(key, entries);
        }
    }

    private void parseTags(Map<String, Json.Value> json) {
        for (Json.Value item : json.get("registries").asArray()) {
            Map<String, Json.Value> registry = item.asObject();
            String key = registry.get("key").asString();
            Map<String, int[]> tagMap = new LinkedHashMap<>();
            for (Map.Entry<String, Json.Value> tag : registry.get("tags").asObject().entrySet()) {
                List<Json.Value> ids = tag.getValue().asArray();
                int[] idArray = new int[ids.size()];
                for (int i = 0; i < ids.size(); i++) {
                    idArray[i] = (int) ids.get(i).asLong();
                }
                tagMap.put(tag.getKey(), idArray);
            }
            this.tags.put(key, tagMap);
        }
    }

    private void parseFeatures(Map<String, Json.Value> json) {
        for (Json.Value feature : json.get("features").asArray()) {
            this.features.add(feature.asString());
        }
    }

    private void parseWorld(Map<String, Json.Value> json) {
        for (Map.Entry<String, Json.Value> e : json.entrySet()) {
            this.world.put(e.getKey(), e.getValue());
        }
        for (Json.Value type : json.get("dimensionTypeIds").asArray()) {
            this.dimensionTypeIds.add(type.asString());
        }
        for (Json.Value level : json.get("levels").asArray()) {
            this.levelIds.add(level.asString());
        }
    }

    private void parseBlockEntityTypes(Map<String, Json.Value> json) {
        for (Map.Entry<String, Json.Value> e : json.get("types").asObject().entrySet()) {
            this.blockEntityTypeIds.put(e.getKey(), (int) e.getValue().asLong());
        }
    }

    private void parseBiomes(Map<String, Json.Value> json) {
        for (Json.Value biome : json.get("biomes").asArray()) {
            this.biomeIds.add(biome.asString());
        }
    }

    private static String stateKey(String name, Map<String, String> props) {
        if (props.isEmpty()) {
            return name;
        }
        List<String> parts = new ArrayList<>();
        props.forEach((k, v) -> parts.add(k + "=" + v));
        java.util.Collections.sort(parts);
        return name + "[" + String.join(",", parts) + "]";
    }

    // ---- 查询 ----

    public BlockStateEntry blockState(String name, Map<String, String> props) {
        BlockStateEntry exact = this.blockStatesByName.get(stateKey(name, props));
        if (exact != null) {
            return exact;
        }
        if (props.isEmpty()) {
            // 纯字符串状态 = 默认状态（可能带属性，如 grass_block[snowy=false]）
            Integer defaultId = this.blockStateDefaults.get(name);
            if (defaultId != null && defaultId >= 0 && defaultId < this.blockStatesById.size()) {
                return this.blockStatesById.get(defaultId);
            }
        }
        return null;
    }

    public List<BlockStateEntry> blockStates() {
        return this.blockStatesById;
    }

    public int blockStateCount() {
        return this.blockStatesById.size();
    }

    /** 同步注册表条目（缺失返回 null）。 */
    public List<RegistryEntry> registry(String key) {
        return this.registries.get(key);
    }

    /** 全部同步注册表（顺序 = 转储顺序，配置阶段逐包发送）。 */
    public Map<String, List<RegistryEntry>> registries() {
        return this.registries;
    }

    public Map<String, Map<String, int[]>> tags() {
        return this.tags;
    }

    public List<String> features() {
        return this.features;
    }

    public List<String> dimensionTypeIds() {
        return this.dimensionTypeIds;
    }

    public List<String> levelIds() {
        return this.levelIds;
    }

    public int blockEntityTypeId(String name) {
        Integer id = this.blockEntityTypeIds.get(name);
        return id == null ? -1 : id;
    }

    public List<String> biomeIds() {
        return this.biomeIds;
    }

    public int biomeId(String identifier) {
        int index = this.biomeIds.indexOf(identifier);
        if (index < 0) {
            throw new IllegalArgumentException("unknown biome: " + identifier);
        }
        return index;
    }

    public String manifestValue(String key) {
        return this.manifest.getOrDefault(key, "");
    }

    public long worldLong(String key, long defaultValue) {
        Json.Value value = (Json.Value) this.world.get(key);
        return value == null ? defaultValue : value.asLong();
    }

    public String worldString(String key, String defaultValue) {
        Json.Value value = (Json.Value) this.world.get(key);
        return value == null ? defaultValue : value.asString();
    }

    /** 启动指纹校验：配置指纹必须与转储 manifest 一致（不一致/缺失 → fail closed）。 */
    public String verifyFingerprint(VirtualBackendConfig config) {
        String manifestRegistry = this.manifestValue("registryAndDatapackFingerprint");
        if (config.registryAndDatapackFingerprint().isEmpty()) {
            return "未配置 registryAndDatapackFingerprint（须与转储 manifest 一致）";
        }
        if (!config.registryAndDatapackFingerprint().equals(manifestRegistry)) {
            return "registryAndDatapackFingerprint 与转储不一致（配置="
                    + config.registryAndDatapackFingerprint() + " 转储=" + manifestRegistry + "）";
        }
        if (!config.minecraftVersion().equals(this.manifestValue("minecraftVersion"))) {
            return "minecraftVersion 不一致（配置=" + config.minecraftVersion()
                    + " 转储=" + this.manifestValue("minecraftVersion") + "）";
        }
        return null;
    }

    /** 生成可测试的最小 bundle（unit test 用）：单 air 状态 + 空注册表。 */
    public static RegistryBundle minimal() {
        RegistryBundle bundle = new RegistryBundle();
        bundle.blockStatesByName.put("minecraft:air",
                new BlockStateEntry(0, "minecraft:air", Map.of(), true, false));
        bundle.blockStatesById.add(bundle.blockStatesByName.get("minecraft:air"));
        bundle.biomeIds.add("minecraft:plains");
        bundle.features.add("minecraft:vanilla");
        bundle.dimensionTypeIds.add("minecraft:overworld");
        bundle.levelIds.add("minecraft:overworld");
        bundle.blockEntityTypeIds.put("minecraft:chest", 0);
        bundle.manifest.put("minecraftVersion", "26.3-snapshot-8");
        return bundle;
    }

    /** 测试注入：单个同步注册表条目。 */
    public void addTestRegistry(String key, RegistryEntry entry) {
        this.registries.put(key, List.of(entry));
    }

    /** 测试注入：world 字段（直接以 Json.Value 存储，解析路径同资源）。 */
    public void setTestWorld(String key, cn.cubegarden.icbm.caching.virtual.registry.Json.Value value) {
        this.world.put(key, value);
    }

    public void setTestWorld(String key, long value) {
        this.world.put(key, cn.cubegarden.icbm.caching.virtual.registry.Json.parse(String.valueOf(value)));
    }

    public void setTestWorld(String key, String value) {
        this.world.put(key, cn.cubegarden.icbm.caching.virtual.registry.Json.parse("\"" + value + "\""));
    }
}

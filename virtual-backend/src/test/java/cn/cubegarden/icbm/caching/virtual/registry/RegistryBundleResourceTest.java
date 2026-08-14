package cn.cubegarden.icbm.caching.virtual.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 真实转储资源解析测试：源服（26.3-snapshot-8 + icbm_source，测试服务器）构建期转储的
 * blockstates/registries/tags/features/world/biomes 资源可被运行时完整解析，
 * 且关键查询（air 全局 id、biome id、方块实体类型、指纹）符合 26.3 预期。
 */
class RegistryBundleResourceTest {

    @Test
    void realDumpLoadsAndServesLookups() throws Exception {
        RegistryBundle bundle = RegistryBundle.load(RegistryBundleResourceTest.class.getClassLoader());

        // 26.3 方块状态表：air 必须是 id 0；总量 > 10000（vanilla 全状态）
        RegistryBundle.BlockStateEntry air = bundle.blockState("minecraft:air", Map.of());
        assertNotNull(air, "air 必须存在");
        assertEquals(0, air.id());
        assertTrue(air.air(), "air.air() 必须为 true");
        assertTrue(bundle.blockStateCount() > 10000, "方块状态表过小: " + bundle.blockStateCount());

        // 抽查常见方块（flat 世界生成相关）：grass_block、dirt、water
        assertNotNull(bundle.blockState("minecraft:grass_block", Map.of("snowy", "false")));
        assertNotNull(bundle.blockState("minecraft:dirt", Map.of()));
        assertNotNull(bundle.blockState("minecraft:water", Map.of("level", "0")));

        // biome：plains 在列表中（默认 biome）
        assertTrue(bundle.biomeIds().contains("minecraft:plains"));
        assertEquals(bundle.biomeIds().indexOf("minecraft:plains"), bundle.biomeId("minecraft:plains"));

        // 方块实体类型 id
        assertTrue(bundle.blockEntityTypeId("minecraft:chest") >= 0);
        assertTrue(bundle.blockEntityTypeId("minecraft:sign") >= 0);

        // 同步注册表（26.3：biome 键为 minecraft:worldgen/biome）与 tags
        assertNotNull(bundle.registry("minecraft:worldgen/biome"));
        assertNotNull(bundle.registry("minecraft:dimension_type"));
        assertTrue(bundle.tags().containsKey("minecraft:block"));
        assertTrue(bundle.features().contains("minecraft:vanilla"));

        // 世界信息
        assertEquals("minecraft:overworld", bundle.worldString("dimensionTypeId", ""));
        assertTrue(bundle.worldLong("seaLevel", 63) > -100 && bundle.worldLong("seaLevel", 63) < 400,
                "seaLevel 应来自转储（世界配置值，测试服务器 flat 为负值）");
        assertEquals(-4, bundle.worldLong("minSectionY", 99));
        assertEquals(19, bundle.worldLong("maxSectionY", -99));

        // manifest 指纹非空（启动校验依赖）
        assertTrue(!bundle.manifestValue("registryAndDatapackFingerprint").isEmpty());
        assertTrue(!bundle.manifestValue("modpackFingerprint").isEmpty());
    }
}

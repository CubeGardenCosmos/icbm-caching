package cn.cubegarden.icbm.caching.slave.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.slave.config.SlaveConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TranslationService 纯逻辑测试（草稿 llm-translation §4）：toggle 状态机（默认开启）、
 * 冷却判定、输入截断。聊天钩子/告示牌/书本读取依赖 Minecraft 运行时，不做单元测试
 * （真机/e2e 覆盖）。
 */
class TranslationServiceTest {

    @Test
    void toggleFlipsPerPlayerState() {
        TranslationService service = new TranslationService(config(), null);
        UUID player = UUID.randomUUID();
        assertTrue(service.isEnabled(player), "默认开启");
        assertFalse(service.toggle(player));
        assertFalse(service.isEnabled(player));
        assertTrue(service.toggle(player));
        assertTrue(service.isEnabled(player));
    }

    @Test
    void toggleIsPerPlayerNotGlobal() {
        TranslationService service = new TranslationService(config(), null);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        service.toggle(a);
        assertFalse(service.isEnabled(a));
        assertTrue(service.isEnabled(b), "其他玩家不受影响");
    }

    @Test
    void cooldownBlocksRepeatedTranslation() {
        TranslationService service = new TranslationService(config(), null);
        UUID player = UUID.randomUUID();
        long now = 1_000_000L;
        assertFalse(service.withinCooldown(player, now));
        service.markTranslation(player, now); // 等价于提交后记录时间戳
        assertTrue(service.withinCooldown(player, now + 4_000L));
        assertFalse(service.withinCooldown(player, now + 5_000L));
    }

    @Test
    void truncateKeepsShortTextAndCapsLongText() {
        assertEquals("", TranslationService.truncate(null, 10));
        assertEquals("hello", TranslationService.truncate("hello", 10));
        assertEquals("hello…", TranslationService.truncate("hello world", 5));
        assertEquals("你好世界…", TranslationService.truncate("你好世界abcdef", 4));
    }

    private SlaveConfig config() {
        return new SlaveConfig.Builder()
                .masterHost("127.0.0.1").masterPort(11420)
                .psk("psk").uuidHex("1112131415161718191a1b1c1d1e1f20")
                .serverName("lobby-1").build();
    }
}

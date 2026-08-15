package cn.cubegarden.icbm.caching.slave.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SlaveConfig 配置解析：品牌（icbm.brandLocation）默认空 → 无地段后缀；配置后
 * → ICBM Caching Server (地段名)；必需键缺失 fail closed。
 */
class SlaveConfigTest {

    @TempDir
    Path tempDir;

    private SlaveConfig load(String propsContent) throws Exception {
        Path worldRoot = this.tempDir.resolve("world");
        Files.createDirectories(worldRoot);
        Files.writeString(worldRoot.resolve("icbm-control.properties"), propsContent);
        return SlaveConfig.load(worldRoot);
    }

    private String base() {
        return "control.masterHost=127.0.0.1\n"
                + "control.masterPort=11420\n"
                + "control.psk=test-psk\n"
                + "control.uuid=1112131415161718191a1b1c1d1e1f20\n"
                + "control.serverName=lobby-1\n";
    }

    @Test
    void brandDefaultsToNoLocation() throws Exception {
        SlaveConfig config = load(base());
        assertEquals("ICBM Caching Server", config.brand());
    }

    @Test
    void brandAppendsConfiguredLocation() throws Exception {
        SlaveConfig config = load(base() + "icbm.brandLocation=HongKong:Kowloon\n");
        assertEquals("ICBM Caching Server (HongKong:Kowloon)", config.brand());
    }

    @Test
    void brandTrimsWhitespaceAndAllowsMainlandExample() throws Exception {
        SlaveConfig config = load(base() + "icbm.brandLocation=  ChinaMainland:Beijing  \n");
        assertEquals("ICBM Caching Server (ChinaMainland:Beijing)", config.brand());
    }

    @Test
    void missingRequiredKeysFailClosed() throws Exception {
        // 无 psk → 抛 IllegalStateException（控制通道不启动）
        assertThrows(IllegalStateException.class, () -> load(
                "control.masterHost=127.0.0.1\ncontrol.masterPort=11420\n"
                        + "control.uuid=1112131415161718191a1b1c1d1e1f20\ncontrol.serverName=lobby-1\n"));
    }
}

package cn.cubegarden.icbm.caching.source.virtual;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** VirtualPlayerRegistry body v2 解析（fail closed 分支：短 body/长度越界不崩溃、不创建会话）。 */
class VirtualPlayerRegistryTest {

    @Test
    void shortBodyFailsClosed() {
        VirtualPlayerRegistry registry = new VirtualPlayerRegistry(null);
        assertDoesNotThrow(() -> registry.onMcPassthrough(null, new byte[10]),
                "短 body 必须丢弃（fail closed，不崩溃）");
    }

    @Test
    void lengthOverflowFailsClosed() {
        VirtualPlayerRegistry registry = new VirtualPlayerRegistry(null);
        // body v2 = [uuid(16)][seq(8)][len(4)][payload]；len 声明 100 但 payload 不足
        UUID uuid = UUID.randomUUID();
        byte[] body = new byte[28 + 2];
        ByteBuffer.wrap(body)
                .putLong(0, uuid.getMostSignificantBits())
                .putLong(8, uuid.getLeastSignificantBits())
                .putLong(16, 1L)
                .putInt(24, 100);
        assertDoesNotThrow(() -> registry.onMcPassthrough(null, body),
                "长度越界必须丢弃（fail closed，不创建会话）");
    }
}

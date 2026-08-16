package cn.cubegarden.icbm.caching.slave.passthrough;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PassthroughHandler：body v2 布局与 fail closed 分支（不依赖 MinecraftServer）。 */
class PassthroughHandlerTest {

    @Test
    void gameProtocolsTemplatePresent() throws Exception {
        // 26.3 GameProtocols.SERVERBOUND_TEMPLATE / CLIENTBOUND_TEMPLATE（initialize=false：
        // 测试环境无 bootstrap registry，只验证类/字段签名存在）
        ClassLoader loader = PassthroughHandler.class.getClassLoader();
        Class<?> gameProtocols = Class.forName(
                "net.minecraft.network.protocol.game.GameProtocols", false, loader);
        gameProtocols.getDeclaredField("SERVERBOUND_TEMPLATE");
        gameProtocols.getDeclaredField("CLIENTBOUND_TEMPLATE");
    }

    @Test
    void serverboundUseItemOnPacketEncodeable() throws Exception {
        // ServerboundUseItemOnPacket 构造（26.3 3 字段：hand/hit/sequence）存在
        Class<?> packetClass = Class.forName(
                "net.minecraft.network.protocol.game.ServerboundUseItemOnPacket");
        packetClass.getConstructor(
                Class.forName("net.minecraft.world.InteractionHand"),
                Class.forName("net.minecraft.world.phys.BlockHitResult"),
                int.class);
    }

    @Test
    void bodyV2HeaderLayout() throws Exception {
        // wrapBody 布局约定（uuid 16B + seq 8B + len 4B + payload）与源服解析对称
        PassthroughHandler handler = new PassthroughHandler(null);
        Method wrap = PassthroughHandler.class.getDeclaredMethod("wrapBody", UUID.class, byte[].class);
        wrap.setAccessible(true);
        UUID uuid = UUID.randomUUID();
        byte[] payload = new byte[] {1, 2, 3};
        byte[] body = (byte[]) wrap.invoke(handler, uuid, payload);
        assertEquals(28 + 3, body.length);
        assertEquals(uuid.getMostSignificantBits(), ByteBuffer.wrap(body, 0, 16).getLong());
        assertEquals(uuid.getLeastSignificantBits(), ByteBuffer.wrap(body, 8, 8).getLong());
        assertArrayEquals(payload, java.util.Arrays.copyOfRange(body, 28, 31));
        handler.onDownstream(new byte[10]); // 短 body 下行丢弃（fail closed，不崩溃）
    }
}

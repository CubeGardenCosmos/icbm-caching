package cn.cubegarden.icbm.caching.virtual.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 26.3 FriendlyByteBuf 原语 golden 测试（锁定线上字节布局）。 */
class PacketBufTest {

    @Test
    void varIntGoldenValues() {
        assertArrayEquals(new byte[]{0x00}, varInt(0));
        assertArrayEquals(new byte[]{0x01}, varInt(1));
        assertArrayEquals(new byte[]{0x7F}, varInt(127));
        assertArrayEquals(new byte[]{(byte) 0x80, 0x01}, varInt(128));
        assertArrayEquals(new byte[]{(byte) 0xFF, 0x01}, varInt(255));
        assertArrayEquals(new byte[]{(byte) 0xAC, 0x02}, varInt(300));
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}, varInt(-1));
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07}, varInt(Integer.MAX_VALUE));
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x08}, varInt(Integer.MIN_VALUE));
    }

    private byte[] varInt(int value) {
        PacketBuf buf = new PacketBuf();
        buf.writeVarInt(value);
        return buf.toByteArray();
    }

    @Test
    void varIntRoundTrip() {
        int[] values = {0, 1, 127, 128, 255, 300, 16384, 2097151, 1073742154, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int value : values) {
            PacketBuf buf = new PacketBuf();
            buf.writeVarInt(value);
            buf.readVarInt();
            assertEquals(value, PacketBuf.wrap(buf.toByteArray()).readVarInt(), "value=" + value);
        }
    }

    @Test
    void uuidAndStringRoundTrip() {
        UUID uuid = UUID.randomUUID();
        PacketBuf buf = new PacketBuf();
        buf.writeUUID(uuid);
        buf.writeUtf("minecraft:overworld");
        buf.writeByteArray(new byte[]{1, 2, 3});
        PacketBuf read = PacketBuf.wrap(buf.toByteArray());
        assertEquals(uuid, read.readUUID());
        assertEquals("minecraft:overworld", read.readUtf());
        assertArrayEquals(new byte[]{1, 2, 3}, read.readByteArray());
    }

    @Test
    void identifierEncoding() {
        PacketBuf buf = new PacketBuf();
        buf.writeIdentifier("minecraft", "brand");
        assertArrayEquals(new byte[]{0x0F, 'm', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':', 'b', 'r', 'a', 'n', 'd'},
                buf.toByteArray());
    }

    @Test
    void fixedSizeLongArray() {
        PacketBuf buf = new PacketBuf();
        buf.writeFixedSizeLongArray(new long[]{1L, 0x0102030405060708L});
        assertArrayEquals(new long[]{1L, 0x0102030405060708L},
                PacketBuf.wrap(buf.toByteArray()).readFixedSizeLongArray(2));
    }
}

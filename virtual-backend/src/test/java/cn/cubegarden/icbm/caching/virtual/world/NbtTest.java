package cn.cubegarden.icbm.caching.virtual.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 最小 NBT 编解码测试（26.3 NbtIo.writeAnyTag/readAnyTag 格式）。 */
class NbtTest {

    @Test
    void roundTrip() {
        Nbt.Tag compound = Nbt.compound();
        Map<String, Nbt.Tag> fields = compound.asCompound();
        fields.put("name", Nbt.string("minecraft:overworld"));
        fields.put("count", Nbt.intTag(5010));
        fields.put("flag", Nbt.byteTag((byte) 1));
        fields.put("longs", Nbt.longArray(new long[]{1, 2, 3}));
        Nbt.Tag list = Nbt.compound();
        list.asCompound().put("sections", Nbt.list(List.of(Nbt.intTag(7), Nbt.intTag(8))));
        fields.put("nested", list);

        byte[] bytes = Nbt.toBytes(compound);
        Nbt.Tag parsed = Nbt.fromBytes(bytes);
        Map<String, Nbt.Tag> out = parsed.asCompound();
        assertEquals("minecraft:overworld", out.get("name").asString());
        assertEquals(5010, out.get("count").asInt());
        assertEquals(1, out.get("flag").asByte());
        assertArrayEquals(new long[]{1, 2, 3}, out.get("longs").asLongArray());
        assertEquals(7, out.get("nested").asCompound().get("sections").asList().get(0).asInt());
        assertEquals(8, out.get("nested").asCompound().get("sections").asList().get(1).asInt());
    }

    @Test
    void gzipRoundTrip() {
        Nbt.Tag compound = Nbt.compound();
        compound.asCompound().put("xPos", Nbt.intTag(1));
        byte[] gzipped = Nbt.toGzipBytes(compound);
        Nbt.Tag parsed = Nbt.fromGzip(gzipped);
        assertEquals(1, parsed.asCompound().get("xPos").asInt());
    }

    @Test
    void emptyCompoundWritesEndTag() {
        byte[] bytes = Nbt.toBytes(Nbt.compound());
        // 根：类型字节(10) + 空 map + END(0)
        assertArrayEquals(new byte[]{10, 0}, bytes);
    }

    @Test
    void malformedInputFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> Nbt.fromBytes(new byte[]{99}));
    }

    @Test
    void compoundFromPreservesOrder() {
        java.util.LinkedHashMap<String, Nbt.Tag> map = new java.util.LinkedHashMap<>();
        map.put("b", Nbt.intTag(1));
        map.put("a", Nbt.intTag(2));
        Nbt.Tag tag = Nbt.compoundFrom(map);
        assertEquals(java.util.List.of("b", "a"), new java.util.ArrayList<>(tag.asCompound().keySet()));
        assertNotNull(tag);
    }
}

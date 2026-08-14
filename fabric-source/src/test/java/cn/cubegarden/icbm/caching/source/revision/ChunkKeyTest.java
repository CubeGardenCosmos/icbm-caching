package cn.cubegarden.icbm.caching.source.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ChunkKeyTest {

    @Test
    void keyStringRoundTrip() {
        ChunkKey key = ChunkKey.of(Identifier.fromNamespaceAndPath("minecraft", "overworld"), -3, 12);
        String serialized = key.keyString();
        assertEquals("-3,12", serialized);
        assertEquals(key, ChunkKey.fromKeyString(Identifier.fromNamespaceAndPath("minecraft", "overworld"), serialized));
    }

    @Test
    void rejectsMalformedKeyString() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkKey.fromKeyString(Identifier.fromNamespaceAndPath("minecraft", "overworld"), "abc"));
        assertThrows(NumberFormatException.class,
                () -> ChunkKey.fromKeyString(Identifier.fromNamespaceAndPath("minecraft", "overworld"), "1,x"));
    }
}

package cn.cubegarden.icbm.caching.source.dirty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.cubegarden.icbm.caching.source.revision.ChunkKey;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** 合并语义：同一区块多次标记只发布一次；不同维度/区块互不干扰。 */
class DirtyChunkSetTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final Identifier NETHER = Identifier.fromNamespaceAndPath("minecraft", "the_nether");

    @Test
    void repeatedMarkCoalescesToSingleDrain() {
        DirtyChunkSet set = new DirtyChunkSet();
        ChunkKey key = ChunkKey.of(DIM, 1, 2);
        set.mark(key);
        set.mark(key);
        set.mark(key);
        assertEquals(1, set.size());
        Set<ChunkKey> drained = set.drain();
        assertEquals(1, drained.size());
        assertTrue(set.isEmpty());
    }

    @Test
    void dimensionsAreIsolated() {
        DirtyChunkSet set = new DirtyChunkSet();
        set.mark(ChunkKey.of(DIM, 1, 2));
        set.mark(ChunkKey.of(NETHER, 1, 2));
        assertEquals(2, set.drain().size());
    }

    @Test
    void markAfterDrainIsTrackedAgain() {
        DirtyChunkSet set = new DirtyChunkSet();
        ChunkKey key = ChunkKey.of(DIM, 5, 5);
        set.mark(key);
        set.drain();
        set.mark(key);
        assertEquals(1, set.drain().size());
    }
}

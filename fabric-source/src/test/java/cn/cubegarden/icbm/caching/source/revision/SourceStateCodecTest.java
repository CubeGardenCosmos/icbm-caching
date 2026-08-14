package cn.cubegarden.icbm.caching.source.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** 状态 codec 的 round-trip 测试（golden：结构字段与语义保持一致）。 */
class SourceStateCodecTest {

    @Test
    void encodeDecodeRoundTrip() {
        SourceState state = new SourceState();
        Identifier dim = Identifier.fromNamespaceAndPath("minecraft", "overworld");
        state.epochs().put(dim, 3L);
        state.revisions().computeIfAbsent(dim, ignored -> new java.util.HashMap<>()).put("1,2", 42L);
        state.setNextEpochId(9L);
        state.setModpackFingerprint("deadbeef");
        state.recordEpochTransition(dim, new SourceState.EpochTransition(3L, ActivationReason.START, 123456L));

        DataResult<Tag> encoded = SourceState.CODEC.encodeStart(NbtOps.INSTANCE, state);
        Tag tag = encoded.getOrThrow();
        SourceState decoded = SourceState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

        assertEquals(3L, decoded.epochs().get(dim));
        assertEquals(42L, decoded.revisions().get(dim).get("1,2"));
        assertEquals(9L, decoded.nextEpochId());
        assertEquals("deadbeef", decoded.modpackFingerprint());
        assertNotNull(decoded.epochTransition(dim));
        assertEquals(ActivationReason.START, decoded.epochTransition(dim).reason());
        assertEquals(123456L, decoded.epochTransition(dim).timestampMillis());
    }
}

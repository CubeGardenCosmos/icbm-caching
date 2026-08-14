package cn.cubegarden.icbm.caching.source.revision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DataVersion;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.WorldVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 持久化语义测试（SavedDataStorage.java 对照）：
 * 重启后 revision 不倒退（状态正常恢复）；无法证明时产生新 epoch。
 */
class SourceStateServiceTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final ChunkKey KEY = ChunkKey.of(DIM, 1, 2);

    @BeforeAll
    static void setGameVersion() {
        // 与 docs/VERSION_FREEZE.md 冻结事实一致：SharedConstants 需要版本才能构造 DataFixers
        SharedConstants.setVersion(new WorldVersion.Simple(
                "26.3-snapshot-8", "26.3 Snapshot 8",
                new DataVersion(5010, "main"), 1073742154,
                new PackFormat(96, 0), new PackFormat(116, 0),
                new Date(), false));
    }

    @TempDir
    Path worldDir;

    private SavedDataStorage newStorage() {
        return new SavedDataStorage(
                worldDir.resolve("data"), DataFixers.getDataFixer(), RegistryAccess.EMPTY);
    }

    @Test
    void restartPreservesRevisionsAndEpochs() {
        // 第一次启动：新世界（existingWorld=false）
        long firstEpoch;
        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp1", false);
            firstEpoch = service.epochFor(DIM);
            assertEquals(1L, service.nextRevision(KEY));
            assertEquals(2L, service.nextRevision(KEY));
            assertEquals(2L, service.revisionOf(KEY));
            storage.scheduleSave().join();
        }

        // 重启：状态正常恢复 → revision 与 epoch 保持不变（单调不倒退）
        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp1", true);
            assertEquals(firstEpoch, service.epochFor(DIM));
            assertEquals(2L, service.revisionOf(KEY), "重启后 revision 不得倒退");
            assertEquals(3L, service.nextRevision(KEY));
        }
    }

    @Test
    void freshWorldStartsWithStartReason() {
        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp1", false);
            long epoch = service.epochFor(DIM);
            assertEquals(ActivationReason.START, service.state().epochTransition(DIM).reason());
            assertTrue(epoch > 0);
        }
    }

    @Test
    void missingStateWithExistingWorldUsesRollbackReason() {
        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp1", true);
            service.epochFor(DIM);
            assertEquals(ActivationReason.ROLLBACK, service.state().epochTransition(DIM).reason());
        }
    }

    @Test
    void fingerprintChangeCreatesNewEpochAndKeepsRevisions() {
        long oldEpoch;
        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp1", false);
            oldEpoch = service.epochFor(DIM);
            service.nextRevision(KEY);
            storage.scheduleSave().join();
        }

        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp2", true);
            long newEpoch = service.epochFor(DIM);
            assertNotEquals(oldEpoch, newEpoch, "指纹变化必须产生新 epoch");
            assertEquals(ActivationReason.MODPACK_CHANGE, service.state().epochTransition(DIM).reason());
            assertEquals(1L, service.revisionOf(KEY), "epoch 变化使旧快照失效，revision 继续单调");
        }
    }

    @Test
    void adminCommandCreatesNewEpoch() {
        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp1", false);
            long old = service.epochFor(DIM);
            long next = service.newEpoch(DIM, ActivationReason.ADMIN);
            assertEquals(old + 1, next);
            assertEquals(ActivationReason.ADMIN, service.state().epochTransition(DIM).reason());
        }
    }

    @Test
    void corruptStateCannotProveMonotonicitySoNewEpoch() throws Exception {
        Path dataDir = worldDir.resolve("data");
        Files.createDirectories(dataDir.resolve("icbm_caching"));
        Files.writeString(dataDir.resolve("icbm_caching/source_state.dat"), "not-a-nbt");
        try (SavedDataStorage storage = newStorage()) {
            SourceStateService service = SourceStateService.load(storage, "fp1", true);
            service.epochFor(DIM);
            assertEquals(ActivationReason.ROLLBACK, service.state().epochTransition(DIM).reason());
            assertEquals(0L, service.revisionOf(KEY));
        }
    }
}

package cn.cubegarden.icbm.caching.source;

import cn.cubegarden.icbm.caching.source.dirty.DirtyTracker;
import cn.cubegarden.icbm.caching.source.revision.SourceStateService;
import cn.cubegarden.icbm.caching.source.snapshot.SnapshotPublisher;
import cn.cubegarden.icbm.caching.source.tick.ChunkSnapshotTicker;
import org.jspecify.annotations.Nullable;

/**
 * 源模组运行时装配（组合根产物）：由 {@link IcbmSource} 在服务器维度加载时安装，
 * 服务器停止时清除。Mixin、命令与 tick 驱动经 {@link #current()} 访问。
 *
 * <p>这是唯一允许的进程级装配点（AGENTS.md「无全局可变状态」的显式例外：
 * volatile 引用 + 明确生命周期，不使用静态可变容器）。
 */
public record SourceRuntime(
        CacheGate gate,
        SourceStateService service,
        DirtyTracker tracker,
        SnapshotPublisher publisher,
        ChunkSnapshotTicker ticker,
        cn.cubegarden.icbm.caching.source.operation.OperationQueue operationQueue,
        cn.cubegarden.icbm.caching.source.materialize.MaterializeService materialize
) {

    private static volatile SourceRuntime INSTANCE;

    public static void install(SourceRuntime runtime) {
        INSTANCE = runtime;
    }

    public static void clear() {
        INSTANCE = null;
    }

    public static @Nullable SourceRuntime current() {
        return INSTANCE;
    }
}

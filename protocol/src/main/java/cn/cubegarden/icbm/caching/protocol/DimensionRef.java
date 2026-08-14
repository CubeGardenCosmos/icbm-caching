package cn.cubegarden.icbm.caching.protocol;

import java.util.Objects;

/**
 * 维度标识（线上格式为字符串；协议模块不依赖 Minecraft 的 Identifier，
 * 由消费方转换：source 侧 Identifier → DimensionRef，slave 侧反解）。
 */
public record DimensionRef(String namespace, String path) {

    public DimensionRef {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.isEmpty() || path.isEmpty()) {
            throw new IllegalArgumentException("empty dimension: " + namespace + ":" + path);
        }
    }

    public static DimensionRef of(String namespace, String path) {
        return new DimensionRef(namespace, path);
    }

    /** 完整 id（"namespace:path"），对应 CACHE_PROTOCOL.md 的 dimensionId 线上表示。 */
    public String id() {
        return this.namespace + ":" + this.path;
    }

    /** 解析完整 id；格式非法抛 IllegalArgumentException（不静默回退）。 */
    public static DimensionRef parse(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            throw new IllegalArgumentException("invalid dimension id: " + id);
        }
        return new DimensionRef(id.substring(0, colon), id.substring(colon + 1));
    }

    @Override
    public String toString() {
        return this.id();
    }
}

package cn.cubegarden.icbm.caching.protocol;

import java.util.Objects;

/**
 * 维度标识（线上格式为字符串；协议模块不依赖 Minecraft 的 Identifier，
 * 由消费方转换：source 侧 Identifier → DimensionRef，slave 侧反解）。
 */
public record DimensionRef(String namespace, String path) {

    /** 26.3 Identifier 允许字符集（Identifier.java 规则；防对象库路径穿越，A1 审计修复）。 */
    private static final java.util.regex.Pattern IDENTIFIER_PART =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+");

    public DimensionRef {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!IDENTIFIER_PART.matcher(namespace).matches() || !IDENTIFIER_PART.matcher(path).matches()) {
            throw new IllegalArgumentException("非法维度标识（必须是 [a-z0-9_.-]+，防路径穿越）: "
                    + namespace + ":" + path);
        }
        if (namespace.equals(".") || namespace.equals("..") || path.equals(".") || path.equals("..")) {
            throw new IllegalArgumentException("维度标识不得为 . / ..: " + namespace + ":" + path);
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

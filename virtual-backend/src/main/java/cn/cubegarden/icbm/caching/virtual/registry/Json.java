package cn.cubegarden.icbm.caching.virtual.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 读取器（转储资源解析用；仅支持我们自身产出的确定性 JSON 子集）。
 *
 * <p>值模型：对象（有序 map）、数组、字符串、long、double、boolean、null。
 * 解析失败抛 {@link IllegalArgumentException}（fail closed，不猜测格式）。
 */
public final class Json {

    private final String input;
    private int pos;

    private Json(String input) {
        this.input = input;
    }

    /** 值节点。 */
    public static final class Value {
        private final Kind kind;
        private final Object data;

        Value(Kind kind, Object data) {
            this.kind = kind;
            this.data = data;
        }

        public Kind kind() {
            return this.kind;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Value> asObject() {
            if (this.kind != Kind.OBJECT) {
                throw new IllegalArgumentException("not an object: " + this.kind);
            }
            return (Map<String, Value>) this.data;
        }

        @SuppressWarnings("unchecked")
        public List<Value> asArray() {
            if (this.kind != Kind.ARRAY) {
                throw new IllegalArgumentException("not an array: " + this.kind);
            }
            return (List<Value>) this.data;
        }

        public String asString() {
            if (this.kind != Kind.STRING) {
                throw new IllegalArgumentException("not a string: " + this.kind);
            }
            return (String) this.data;
        }

        public long asLong() {
            if (this.kind == Kind.LONG) {
                return (Long) this.data;
            }
            if (this.kind == Kind.DOUBLE) {
                return (long) (double) (Double) this.data;
            }
            throw new IllegalArgumentException("not a number: " + this.kind);
        }

        public boolean asBoolean() {
            if (this.kind != Kind.BOOLEAN) {
                throw new IllegalArgumentException("not a boolean: " + this.kind);
            }
            return (Boolean) this.data;
        }

        public boolean isNull() {
            return this.kind == Kind.NULL;
        }

        public boolean isString() {
            return this.kind == Kind.STRING;
        }
    }

    public enum Kind {
        OBJECT, ARRAY, STRING, LONG, DOUBLE, BOOLEAN, NULL,
    }

    public static Value parse(String input) {
        Json parser = new Json(input);
        parser.skipWhitespace();
        Value value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != input.length()) {
            throw new IllegalArgumentException("trailing content at " + parser.pos);
        }
        return value;
    }

    private Value readValue() {
        this.skipWhitespace();
        if (this.pos >= this.input.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = this.input.charAt(this.pos);
        return switch (c) {
            case '{' -> this.readObject();
            case '[' -> this.readArray();
            case '"' -> new Value(Kind.STRING, this.readString());
            case 't' -> {
                this.expect("true");
                yield new Value(Kind.BOOLEAN, Boolean.TRUE);
            }
            case 'f' -> {
                this.expect("false");
                yield new Value(Kind.BOOLEAN, Boolean.FALSE);
            }
            case 'n' -> {
                this.expect("null");
                yield new Value(Kind.NULL, null);
            }
            default -> this.readNumber();
        };
    }

    private Value readObject() {
        this.pos++; // {
        Map<String, Value> map = new LinkedHashMap<>();
        this.skipWhitespace();
        if (this.input.charAt(this.pos) == '}') {
            this.pos++;
            return new Value(Kind.OBJECT, map);
        }
        while (true) {
            this.skipWhitespace();
            String key = this.readString();
            this.skipWhitespace();
            if (this.input.charAt(this.pos) != ':') {
                throw new IllegalArgumentException("expected ':' at " + this.pos);
            }
            this.pos++;
            map.put(key, this.readValue());
            this.skipWhitespace();
            char c = this.input.charAt(this.pos);
            if (c == ',') {
                this.pos++;
            } else if (c == '}') {
                this.pos++;
                return new Value(Kind.OBJECT, map);
            } else {
                throw new IllegalArgumentException("expected ',' or '}' at " + this.pos);
            }
        }
    }

    private Value readArray() {
        this.pos++; // [
        List<Value> list = new ArrayList<>();
        this.skipWhitespace();
        if (this.input.charAt(this.pos) == ']') {
            this.pos++;
            return new Value(Kind.ARRAY, list);
        }
        while (true) {
            list.add(this.readValue());
            this.skipWhitespace();
            char c = this.input.charAt(this.pos);
            if (c == ',') {
                this.pos++;
            } else if (c == ']') {
                this.pos++;
                return new Value(Kind.ARRAY, list);
            } else {
                throw new IllegalArgumentException("expected ',' or ']' at " + this.pos);
            }
        }
    }

    private String readString() {
        if (this.input.charAt(this.pos) != '"') {
            throw new IllegalArgumentException("expected '\"' at " + this.pos);
        }
        this.pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (this.pos >= this.input.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = this.input.charAt(this.pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char escaped = this.input.charAt(this.pos++);
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = this.input.substring(this.pos, this.pos + 4);
                        this.pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new IllegalArgumentException("bad escape: " + escaped);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Value readNumber() {
        int start = this.pos;
        boolean floating = false;
        while (this.pos < this.input.length()) {
            char c = this.input.charAt(this.pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                if (c == '.' || c == 'e' || c == 'E') {
                    floating = true;
                }
                this.pos++;
            } else {
                break;
            }
        }
        String token = this.input.substring(start, this.pos);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("invalid number at " + start);
        }
        if (floating) {
            return new Value(Kind.DOUBLE, Double.parseDouble(token));
        }
        return new Value(Kind.LONG, Long.parseLong(token));
    }

    private void expect(String literal) {
        if (!this.input.startsWith(literal, this.pos)) {
            throw new IllegalArgumentException("expected " + literal + " at " + this.pos);
        }
        this.pos += literal.length();
    }

    private void skipWhitespace() {
        while (this.pos < this.input.length()) {
            char c = this.input.charAt(this.pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                this.pos++;
            } else {
                break;
            }
        }
    }

    /** 从 gzip 资源读取并解析。 */
    public static Value parseGzip(java.io.InputStream in) throws java.io.IOException {
        try (var gzip = new java.util.zip.GZIPInputStream(in)) {
            return parse(new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}

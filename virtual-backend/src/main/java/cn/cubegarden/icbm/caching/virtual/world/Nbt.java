package cn.cubegarden.icbm.caching.virtual.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 NBT 编解码（26.3 网络与快照共用标准二进制 NBT 格式：
 * {@code NbtIo.writeAnyTag/readAnyTag} 用 DataOutput/DataInput，字符串为 2 字节长度 + UTF-8；
 * 快照 payload 为 GZIP 压缩的同一格式）。
 *
 * <p>只实现虚拟后端需要读取/写出的类型：compound/list/string/byte/int/long/float/double/
 * byte-array/int-array/long-array/short。未知类型抛 {@link IllegalArgumentException}
 * （fail closed，不猜测格式）。
 */
public final class Nbt {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    private Nbt() {
    }

    /** 不可变值标签（含 compound/list 的可变视图）。 */
    public static final class Tag {
        private final int type;
        private final Object value;

        private Tag(int type, Object value) {
            this.type = type;
            this.value = value;
        }

        public int type() {
            return this.type;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Tag> asCompound() {
            if (this.type != TAG_COMPOUND) {
                throw new IllegalArgumentException("not a compound: type=" + this.type);
            }
            return (Map<String, Tag>) this.value;
        }

        @SuppressWarnings("unchecked")
        public List<Tag> asList() {
            if (this.type != TAG_LIST) {
                throw new IllegalArgumentException("not a list: type=" + this.type);
            }
            return (List<Tag>) this.value;
        }

        public String asString() {
            if (this.type != TAG_STRING) {
                throw new IllegalArgumentException("not a string: type=" + this.type);
            }
            return (String) this.value;
        }

        public byte asByte() {
            if (this.type != TAG_BYTE) {
                throw new IllegalArgumentException("not a byte: type=" + this.type);
            }
            return (Byte) this.value;
        }

        public short asShort() {
            if (this.type != TAG_SHORT) {
                throw new IllegalArgumentException("not a short: type=" + this.type);
            }
            return (Short) this.value;
        }

        public int asInt() {
            if (this.type != TAG_INT) {
                throw new IllegalArgumentException("not an int: type=" + this.type);
            }
            return (Integer) this.value;
        }

        public long asLong() {
            if (this.type != TAG_LONG) {
                throw new IllegalArgumentException("not a long: type=" + this.type);
            }
            return (Long) this.value;
        }

        public float asFloat() {
            if (this.type != TAG_FLOAT) {
                throw new IllegalArgumentException("not a float: type=" + this.type);
            }
            return (Float) this.value;
        }

        public double asDouble() {
            if (this.type != TAG_DOUBLE) {
                throw new IllegalArgumentException("not a double: type=" + this.type);
            }
            return (Double) this.value;
        }

        public byte[] asByteArray() {
            if (this.type != TAG_BYTE_ARRAY) {
                throw new IllegalArgumentException("not a byte array: type=" + this.type);
            }
            return (byte[]) this.value;
        }

        public int[] asIntArray() {
            if (this.type != TAG_INT_ARRAY) {
                throw new IllegalArgumentException("not an int array: type=" + this.type);
            }
            return (int[]) this.value;
        }

        public long[] asLongArray() {
            if (this.type != TAG_LONG_ARRAY) {
                throw new IllegalArgumentException("not a long array: type=" + this.type);
            }
            return (long[]) this.value;
        }
    }

    public static Tag string(String value) {
        return new Tag(TAG_STRING, value);
    }

    public static Tag byteTag(byte value) {
        return new Tag(TAG_BYTE, value);
    }

    public static Tag byteArray(byte[] value) {
        return new Tag(TAG_BYTE_ARRAY, value);
    }

    public static Tag intArray(int[] value) {
        return new Tag(TAG_INT_ARRAY, value);
    }

    public static Tag longArray(long[] value) {
        return new Tag(TAG_LONG_ARRAY, value);
    }

    public static Tag intTag(int value) {
        return new Tag(TAG_INT, value);
    }

    public static Tag longTag(long value) {
        return new Tag(TAG_LONG, value);
    }

    public static Tag compound() {
        return new Tag(TAG_COMPOUND, new LinkedHashMap<>());
    }

    /** 由既有 map 构造 compound（含可变视图共享；仅内部转换用）。 */
    public static Tag compoundFrom(Map<String, Tag> fields) {
        return new Tag(TAG_COMPOUND, fields);
    }

    public static Tag list(List<Tag> elements) {
        return new Tag(TAG_LIST, elements);
    }

    // ---- 读取 ----

    /** 读取一个"匿名"标签（无名称前缀）：先读类型字节，非 END 则读负载。 */
    public static Tag readAnyTag(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == TAG_END) {
            return null;
        }
        return readTagPayload(type, in);
    }

    /** 读取完整根标签（NBT 文件格式：类型字节 + 根名称 + 负载）。 */
    public static Tag readRootTag(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == TAG_END) {
            throw new IOException("empty NBT root");
        }
        readUtf(in); // 根名称（文件格式；网络 writeAnyTag 无名称）
        return readTagPayload(type, in);
    }

    private static Tag readTagPayload(int type, DataInputStream in) throws IOException {
        return switch (type) {
            case TAG_BYTE -> new Tag(TAG_BYTE, in.readByte());
            case TAG_SHORT -> new Tag(TAG_SHORT, in.readShort());
            case TAG_INT -> new Tag(TAG_INT, in.readInt());
            case TAG_LONG -> new Tag(TAG_LONG, in.readLong());
            case TAG_FLOAT -> new Tag(TAG_FLOAT, in.readFloat());
            case TAG_DOUBLE -> new Tag(TAG_DOUBLE, in.readDouble());
            case TAG_BYTE_ARRAY -> {
                int length = in.readInt();
                if (length < 0 || length > 16 * 1024 * 1024) {
                    throw new IOException("invalid byte array length: " + length);
                }
                byte[] data = new byte[length];
                in.readFully(data);
                yield new Tag(TAG_BYTE_ARRAY, data);
            }
            case TAG_STRING -> new Tag(TAG_STRING, readUtf(in));
            case TAG_LIST -> {
                int elementType = in.readUnsignedByte();
                int length = in.readInt();
                if (length < 0 || length > 1_000_000) {
                    throw new IOException("invalid list length: " + length);
                }
                List<Tag> elements = new ArrayList<>(Math.min(length, 1024));
                for (int i = 0; i < length; i++) {
                    if (elementType == TAG_END) {
                        elements.add(null);
                    } else {
                        elements.add(readTagPayload(elementType, in));
                    }
                }
                yield new Tag(TAG_LIST, elements);
            }
            case TAG_COMPOUND -> {
                Map<String, Tag> map = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == TAG_END) {
                        break;
                    }
                    String name = readUtf(in);
                    map.put(name, readTagPayload(childType, in));
                }
                yield new Tag(TAG_COMPOUND, map);
            }
            case TAG_INT_ARRAY -> {
                int length = in.readInt();
                if (length < 0 || length > 4 * 1024 * 1024) {
                    throw new IOException("invalid int array length: " + length);
                }
                int[] data = new int[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readInt();
                }
                yield new Tag(TAG_INT_ARRAY, data);
            }
            case TAG_LONG_ARRAY -> {
                int length = in.readInt();
                if (length < 0 || length > 2 * 1024 * 1024) {
                    throw new IOException("invalid long array length: " + length);
                }
                long[] data = new long[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readLong();
                }
                yield new Tag(TAG_LONG_ARRAY, data);
            }
            default -> throw new IOException("unknown NBT type: " + type);
        };
    }

    /** 标准 NBT UTF 字符串（2 字节大端长度 + UTF-8）。 */
    public static String readUtf(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** GZIP 压缩的 NBT 根标签（快照 payload 格式）。 */
    public static Tag readGzip(InputStream in) throws IOException {
        try (var gzip = new java.util.zip.GZIPInputStream(in)) {
            return readRootTag(new DataInputStream(gzip));
        }
    }

    // ---- 写出 ----

    /** 写出匿名标签：类型字节 + 负载（NbtIo.writeAnyTag 同构）。 */
    public static void writeAnyTag(DataOutputStream out, Tag tag) throws IOException {
        out.writeByte(tag.type());
        if (tag.type() != TAG_END) {
            writeTagPayload(out, tag);
        }
    }

    private static void writeTagPayload(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type()) {
            case TAG_BYTE -> out.writeByte(tag.asByte());
            case TAG_SHORT -> out.writeShort(tag.asShort());
            case TAG_INT -> out.writeInt(tag.asInt());
            case TAG_LONG -> out.writeLong(tag.asLong());
            case TAG_FLOAT -> out.writeFloat(tag.asFloat());
            case TAG_DOUBLE -> out.writeDouble(tag.asDouble());
            case TAG_BYTE_ARRAY -> {
                byte[] data = tag.asByteArray();
                out.writeInt(data.length);
                out.write(data);
            }
            case TAG_STRING -> writeUtf(out, tag.asString());
            case TAG_LIST -> {
                List<Tag> elements = tag.asList();
                int elementType = elements.isEmpty() ? TAG_END : elements.getFirst().type();
                for (Tag element : elements) {
                    if (element != null && element.type() != elementType) {
                        throw new IOException("NBT list 类型不一致: " + elementType + " vs " + element.type());
                    }
                }
                out.writeByte(elementType);
                out.writeInt(elements.size());
                for (Tag element : elements) {
                    writeTagPayload(out, element);
                }
            }
            case TAG_COMPOUND -> {
                for (Map.Entry<String, Tag> entry : tag.asCompound().entrySet()) {
                    Tag child = entry.getValue();
                    out.writeByte(child.type());
                    writeUtf(out, entry.getKey());
                    writeTagPayload(out, child);
                }
                out.writeByte(TAG_END);
            }
            case TAG_INT_ARRAY -> {
                int[] data = tag.asIntArray();
                out.writeInt(data.length);
                for (int value : data) {
                    out.writeInt(value);
                }
            }
            case TAG_LONG_ARRAY -> {
                long[] data = tag.asLongArray();
                out.writeInt(data.length);
                for (long value : data) {
                    out.writeLong(value);
                }
            }
            default -> throw new IOException("cannot write NBT type: " + tag.type());
        }
    }

    public static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("NBT string too long: " + bytes.length);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /** 写出任意标签（registry data 用，NbtIo.writeAnyTag 同构：无根名称）。 */
    public static byte[] toBytes(Tag tag) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writeAnyTag(out, tag);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("NBT write failed", e);
        }
    }

    /** 写出压缩快照（NbtIo.writeCompressed 同构：根标签含空名称）。 */
    public static byte[] toGzipBytes(Tag tag) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
                try (DataOutputStream out = new DataOutputStream(gzip)) {
                    out.writeByte(tag.type());
                    writeUtf(out, ""); // 根名称（文件格式）
                    writeTagPayload(out, tag);
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("NBT gzip write failed", e);
        }
    }

    /** 网络格式（writeAnyTag 同构）读取。 */
    public static Tag fromBytes(byte[] data) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
            Tag tag = readAnyTag(in);
            if (tag == null) {
                throw new IllegalArgumentException("empty NBT");
            }
            return tag;
        } catch (IOException e) {
            throw new IllegalArgumentException("NBT read failed", e);
        }
    }

    /** 从压缩快照 payload 读取（GZIP NBT，文件格式含根名称）。 */
    public static Tag fromGzip(byte[] data) {
        try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return readRootTag(new DataInputStream(gzip));
        } catch (IOException e) {
            throw new IllegalArgumentException("compressed NBT read failed", e);
        }
    }

    // ---- 便捷访问 ----

    /** 读取 compound 子标签；缺失返回 null。 */
    public static Tag get(Map<String, Tag> compound, String key) {
        return compound.get(key);
    }

    /** 读取 compound 子标签并断言为字符串；缺失返回 null。 */
    public static String getString(Map<String, Tag> compound, String key) {
        Tag tag = compound.get(key);
        return tag == null ? null : tag.asString();
    }

    /** 读取 compound 子标签并断言为 int；缺失返回默认值。 */
    public static int getInt(Map<String, Tag> compound, String key, int defaultValue) {
        Tag tag = compound.get(key);
        return tag == null ? defaultValue : tag.asInt();
    }
}

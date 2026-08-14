package cn.cubegarden.icbm.caching.protocol;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 消息体二进制编解码（DataOutput/DataInput；golden 测试锁定线上布局，草稿
 * phase2-control-protocol §3 body 编码取舍）。
 *
 * <p>字符串：2 字节长度前缀 + UTF-8；整数为大端。未知 messageType 由
 * {@link #decode} 返回 null，调用方必须 fail closed（不猜测兼容格式）。
 */
public final class MessageBodyCodec {

    private MessageBodyCodec() {
    }

    public interface Codec<T> {
        void encode(DataOutput out, T value) throws IOException;

        T decode(DataInput in) throws IOException;
    }

    private static final Map<MessageType, Codec<?>> CODECS = new HashMap<>();

    private static <T> void register(MessageType type, Codec<T> codec) {
        if (CODECS.put(type, codec) != null) {
            throw new IllegalStateException("duplicate codec for " + type);
        }
    }

    static {
        register(MessageType.HELLO, new Codec<Messages.Hello>() {
            @Override
            public void encode(DataOutput out, Messages.Hello value) throws IOException {
                out.writeByte(value.role().id());
                writeString(out, value.minecraftVersion());
                out.writeInt(value.runtimeProtocol());
                writeString(out, value.fabricLoaderVersion());
                writeString(out, value.fabricApiVersion());
                writeString(out, value.modpackFingerprint());
                writeString(out, value.registryAndDatapackFingerprint());
                out.writeByte(value.capabilities());
            }

            @Override
            public Messages.Hello decode(DataInput in) throws IOException {
                Role role = Role.fromId(in.readByte());
                if (role == null) {
                    throw new IOException("unknown role");
                }
                String mcVersion = readString(in);
                int protocol = in.readInt();
                String loader = readString(in);
                String api = readString(in);
                String modpack = readString(in);
                String registry = readString(in);
                byte capabilities = in.readByte();
                return new Messages.Hello(role, mcVersion, protocol, loader, api, modpack, registry, capabilities);
            }
        });
        register(MessageType.HELLO_ACK, new Codec<Messages.HelloAck>() {
            @Override
            public void encode(DataOutput out, Messages.HelloAck value) throws IOException {
                out.writeBoolean(value.accepted());
                writeString(out, value.reason());
                writeString(out, value.serverId());
            }

            @Override
            public Messages.HelloAck decode(DataInput in) throws IOException {
                return new Messages.HelloAck(in.readBoolean(), readString(in), readString(in));
            }
        });
        register(MessageType.WORLD_EPOCH, new Codec<Messages.WorldEpoch>() {
            @Override
            public void encode(DataOutput out, Messages.WorldEpoch value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeLong(value.worldEpoch());
                out.writeByte(value.reason().id());
                writeString(out, value.registryAndDatapackFingerprint());
            }

            @Override
            public Messages.WorldEpoch decode(DataInput in) throws IOException {
                DimensionRef dimension = readDimension(in);
                long epoch = in.readLong();
                EpochReason reason = EpochReason.fromId(in.readByte());
                if (reason == null) {
                    throw new IOException("unknown epoch reason");
                }
                return new Messages.WorldEpoch(dimension, epoch, reason, readString(in));
            }
        });
        register(MessageType.INVALIDATE, new Codec<Messages.Invalidate>() {
            @Override
            public void encode(DataOutput out, Messages.Invalidate value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeInt(value.chunkX());
                out.writeInt(value.chunkZ());
                out.writeLong(value.revision());
                out.writeByte(value.reason().id());
            }

            @Override
            public Messages.Invalidate decode(DataInput in) throws IOException {
                DimensionRef dimension = readDimension(in);
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                long revision = in.readLong();
                InvalidateReason reason = InvalidateReason.fromId(in.readByte());
                if (reason == null) {
                    throw new IOException("unknown invalidate reason");
                }
                return new Messages.Invalidate(dimension, chunkX, chunkZ, revision, reason);
            }
        });
        register(MessageType.PUT_SNAPSHOT, snapshotCodec());
        register(MessageType.PUT_SNAPSHOT_ACK, new Codec<Messages.PutSnapshotAck>() {
            @Override
            public void encode(DataOutput out, Messages.PutSnapshotAck value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeInt(value.chunkX());
                out.writeInt(value.chunkZ());
                out.writeLong(value.revision());
                out.writeByte(value.result().id());
            }

            @Override
            public Messages.PutSnapshotAck decode(DataInput in) throws IOException {
                DimensionRef dimension = readDimension(in);
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                long revision = in.readLong();
                PutResult result = PutResult.fromId(in.readByte());
                if (result == null) {
                    throw new IOException("unknown put result");
                }
                return new Messages.PutSnapshotAck(dimension, chunkX, chunkZ, revision, result);
            }
        });
        register(MessageType.RESOLVE_CHUNK_REQ, new Codec<Messages.ResolveChunkReq>() {
            @Override
            public void encode(DataOutput out, Messages.ResolveChunkReq value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeInt(value.chunkX());
                out.writeInt(value.chunkZ());
                out.writeInt(value.expectedProtocol());
                writeString(out, value.registryAndDatapackFingerprint());
                out.writeLong(value.minRevision());
            }

            @Override
            public Messages.ResolveChunkReq decode(DataInput in) throws IOException {
                return new Messages.ResolveChunkReq(
                        readDimension(in), in.readInt(), in.readInt(), in.readInt(),
                        readString(in), in.readLong()
                );
            }
        });
        register(MessageType.RESOLVE_CHUNK_RES, new Codec<Messages.ResolveChunkRes>() {
            @Override
            public void encode(DataOutput out, Messages.ResolveChunkRes value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeInt(value.chunkX());
                out.writeInt(value.chunkZ());
                out.writeByte(value.status().id());
                out.writeLong(value.committedRevision());
                writeString(out, value.objectId());
                writeString(out, value.objectHash());
            }

            @Override
            public Messages.ResolveChunkRes decode(DataInput in) throws IOException {
                DimensionRef dimension = readDimension(in);
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                ResolveStatus status = ResolveStatus.fromId(in.readByte());
                if (status == null) {
                    throw new IOException("unknown resolve status");
                }
                return new Messages.ResolveChunkRes(
                        dimension, chunkX, chunkZ, status,
                        in.readLong(), readString(in), readString(in)
                );
            }
        });
        register(MessageType.SUBSCRIBE, new Codec<Messages.Subscribe>() {
            @Override
            public void encode(DataOutput out, Messages.Subscribe value) throws IOException {
                out.writeShort(value.dimensions().length);
                for (DimensionRef dimension : value.dimensions()) {
                    writeDimension(out, dimension);
                }
                out.writeBoolean(value.pushEnabled());
                out.writeLong(value.ackedSequence());
            }

            @Override
            public Messages.Subscribe decode(DataInput in) throws IOException {
                int count = in.readUnsignedShort();
                DimensionRef[] dimensions = new DimensionRef[count];
                for (int i = 0; i < count; i++) {
                    dimensions[i] = readDimension(in);
                }
                return new Messages.Subscribe(dimensions, in.readBoolean(), in.readLong());
            }
        });
        register(MessageType.SNAPSHOT_PUSH, new Codec<Messages.SnapshotPush>() {
            @Override
            public void encode(DataOutput out, Messages.SnapshotPush value) throws IOException {
                snapshotCodec().encode(out, value.snapshot());
            }

            @Override
            public Messages.SnapshotPush decode(DataInput in) throws IOException {
                return new Messages.SnapshotPush(snapshotCodec().decode(in));
            }
        });
        register(MessageType.ACK, new Codec<Messages.Ack>() {
            @Override
            public void encode(DataOutput out, Messages.Ack value) throws IOException {
                out.writeLong(value.ackedSequence());
            }

            @Override
            public Messages.Ack decode(DataInput in) throws IOException {
                return new Messages.Ack(in.readLong());
            }
        });
        register(MessageType.RESYNC_REQ, new Codec<Messages.ResyncReq>() {
            @Override
            public void encode(DataOutput out, Messages.ResyncReq value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeLong(value.fromRevision());
            }

            @Override
            public Messages.ResyncReq decode(DataInput in) throws IOException {
                return new Messages.ResyncReq(readDimension(in), in.readLong());
            }
        });
        register(MessageType.RESYNC_RES, new Codec<Messages.ResyncRes>() {
            @Override
            public void encode(DataOutput out, Messages.ResyncRes value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeLong(value.fromRevision());
                out.writeLong(value.lastCommittedRevision());
                out.writeInt(value.replayedCount());
            }

            @Override
            public Messages.ResyncRes decode(DataInput in) throws IOException {
                return new Messages.ResyncRes(readDimension(in), in.readLong(), in.readLong(), in.readInt());
            }
        });
        register(MessageType.HEARTBEAT, new Codec<Messages.Heartbeat>() {
            @Override
            public void encode(DataOutput out, Messages.Heartbeat value) throws IOException {
                out.writeLong(value.sentAt());
                out.writeLong(value.lastSequence());
            }

            @Override
            public Messages.Heartbeat decode(DataInput in) throws IOException {
                return new Messages.Heartbeat(in.readLong(), in.readLong());
            }
        });
        register(MessageType.ADMIN_INVALIDATE, new Codec<Messages.AdminInvalidate>() {
            @Override
            public void encode(DataOutput out, Messages.AdminInvalidate value) throws IOException {
                writeDimension(out, value.dimension());
                writeString(out, value.scope());
                out.writeInt(value.chunkX());
                out.writeInt(value.chunkZ());
                out.writeInt(value.regionX());
                out.writeInt(value.regionZ());
                writeString(out, value.reason());
            }

            @Override
            public Messages.AdminInvalidate decode(DataInput in) throws IOException {
                return new Messages.AdminInvalidate(
                        readDimension(in), readString(in), in.readInt(), in.readInt(),
                        in.readInt(), in.readInt(), readString(in)
                );
            }
        });
        register(MessageType.ERROR, new Codec<Messages.ErrorMessage>() {
            @Override
            public void encode(DataOutput out, Messages.ErrorMessage value) throws IOException {
                writeString(out, value.reason());
            }

            @Override
            public Messages.ErrorMessage decode(DataInput in) throws IOException {
                return new Messages.ErrorMessage(readString(in));
            }
        });
    }

    private static Codec<Messages.PutSnapshot> snapshotCodec() {
        return new Codec<Messages.PutSnapshot>() {
            @Override
            public void encode(DataOutput out, Messages.PutSnapshot value) throws IOException {
                writeDimension(out, value.dimension());
                out.writeInt(value.chunkX());
                out.writeInt(value.chunkZ());
                out.writeLong(value.revision());
                writeString(out, value.snapshotFormat());
                writeString(out, value.compression());
                out.writeInt(value.uncompressedLength());
                writeString(out, value.payloadHash());
                out.writeInt(value.payload().length);
                out.write(value.payload());
            }

            @Override
            public Messages.PutSnapshot decode(DataInput in) throws IOException {
                DimensionRef dimension = readDimension(in);
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                long revision = in.readLong();
                String format = readString(in);
                String compression = readString(in);
                int uncompressedLength = in.readInt();
                String hash = readString(in);
                int payloadLength = in.readInt();
                if (payloadLength < 0 || payloadLength > ProtocolConstants.MAX_SNAPSHOT_BYTES) {
                    throw new IOException("snapshot payload length out of range: " + payloadLength);
                }
                byte[] payload = new byte[payloadLength];
                in.readFully(payload);
                return new Messages.PutSnapshot(
                        dimension, chunkX, chunkZ, revision, format, compression,
                        uncompressedLength, hash, payload
                );
            }
        };
    }

    /** 未知类型返回 null；调用方 fail closed。 */
    @SuppressWarnings("unchecked")
    public static <T> T decode(MessageType type, DataInput in) throws IOException {
        Codec<?> codec = CODECS.get(type);
        if (codec == null) {
            return null;
        }
        return (T) codec.decode(in);
    }

    public static void encode(MessageType type, DataOutput out, Object value) throws IOException {
        Codec<Object> codec = codecFor(type);
        codec.encode(out, value);
    }

    @SuppressWarnings("unchecked")
    private static Codec<Object> codecFor(MessageType type) {
        Codec<?> codec = CODECS.get(type);
        if (codec == null) {
            throw new IllegalArgumentException("no codec for " + type);
        }
        return (Codec<Object>) codec;
    }

    static void writeDimension(DataOutput out, DimensionRef dimension) throws IOException {
        writeString(out, dimension.id());
    }

    static DimensionRef readDimension(DataInput in) throws IOException {
        return DimensionRef.parse(readString(in));
    }

    public static void writeString(DataOutput out, String value) throws IOException {
        byte[] bytes = ProtocolConstants.utf8(value);
        if (bytes.length > ProtocolConstants.MAX_STRING_BYTES) {
            throw new IOException("string too long: " + bytes.length);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return ProtocolConstants.utf8(bytes);
    }

    /** 校验 body 内容是否为空（防止 null body 进入编解码）。 */
    public static boolean hasBody(MessageType type) {
        return CODECS.containsKey(type);
    }

    static {
        // 触发静态初始化顺序：确保所有类型都有 codec
        for (MessageType type : MessageType.values()) {
            if (!CODECS.containsKey(type)) {
                throw new IllegalStateException("missing codec for " + type);
            }
        }
    }
}

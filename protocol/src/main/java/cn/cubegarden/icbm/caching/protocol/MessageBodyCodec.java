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
                writeString(out, value.serverName());
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
                String serverName = readString(in);
                String mcVersion = readString(in);
                int protocol = in.readInt();
                String loader = readString(in);
                String api = readString(in);
                String modpack = readString(in);
                String registry = readString(in);
                byte capabilities = in.readByte();
                return new Messages.Hello(role, serverName, mcVersion, protocol, loader, api, modpack, registry, capabilities);
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
                writeSpawn(out, value.spawn());
            }

            @Override
            public Messages.WorldEpoch decode(DataInput in) throws IOException {
                DimensionRef dimension = readDimension(in);
                long epoch = in.readLong();
                EpochReason reason = EpochReason.fromId(in.readByte());
                if (reason == null) {
                    throw new IOException("unknown epoch reason");
                }
                return new Messages.WorldEpoch(dimension, epoch, reason, readString(in), readSpawn(in));
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
        register(MessageType.LOGIN_VERIFY_REQ, new Codec<Messages.LoginVerifyReq>() {
            @Override
            public void encode(DataOutput out, Messages.LoginVerifyReq value) throws IOException {
                writeString(out, value.playerName());
                writeUuid(out, value.playerUuid());
                writeString(out, value.slaveId());
                writeString(out, value.connectionInfo());
                writeUuid(out, value.nonce());
                out.writeLong(value.sentAt());
            }

            @Override
            public Messages.LoginVerifyReq decode(DataInput in) throws IOException {
                return new Messages.LoginVerifyReq(
                        readString(in), readUuid(in), readString(in), readString(in),
                        readUuid(in), in.readLong()
                );
            }
        });
        register(MessageType.LOGIN_VERIFY_RES, new Codec<Messages.LoginVerifyRes>() {
            @Override
            public void encode(DataOutput out, Messages.LoginVerifyRes value) throws IOException {
                out.writeBoolean(value.ok());
                writeString(out, value.reason());
                if (value.ok()) {
                    writeBytes(out, value.challenge());
                    writeBytes(out, value.publicKeyDer());
                }
            }

            @Override
            public Messages.LoginVerifyRes decode(DataInput in) throws IOException {
                boolean ok = in.readBoolean();
                String reason = readString(in);
                if (!ok) {
                    return Messages.LoginVerifyRes.reject(reason);
                }
                return new Messages.LoginVerifyRes(true, reason, readBytes(in), readBytes(in));
            }
        });
        register(MessageType.LOGIN_KEY_REQ, new Codec<Messages.LoginKeyReq>() {
            @Override
            public void encode(DataOutput out, Messages.LoginKeyReq value) throws IOException {
                writeUuid(out, value.playerUuid());
                writeUuid(out, value.nonce());
                writeBytes(out, value.keybytes());
                writeBytes(out, value.encryptedChallenge());
            }

            @Override
            public Messages.LoginKeyReq decode(DataInput in) throws IOException {
                return new Messages.LoginKeyReq(
                        readUuid(in), readUuid(in), readBytes(in), readBytes(in));
            }
        });
        register(MessageType.LOGIN_KEY_RES, new Codec<Messages.LoginKeyRes>() {
            @Override
            public void encode(DataOutput out, Messages.LoginKeyRes value) throws IOException {
                out.writeBoolean(value.verified());
                writeString(out, value.reason());
                writeBytes(out, value.sharedSecret());
                if (value.verified()) {
                    Messages.MasterVerifiedIdentity id = value.identity();
                    if (id == null) {
                        throw new IOException("VERIFIED 必须携带 identity");
                    }
                    writeString(out, id.issuer());
                    writeString(out, id.keyId());
                    writeString(out, id.audience());
                    writeUuid(out, id.playerUuid());
                    writeString(out, id.sessionId());
                    writeUuid(out, id.nonce());
                    out.writeLong(id.expiresAt());
                    writeBytes(out, id.signature());
                    out.writeShort(id.profileProperties().length);
                    for (Messages.ProfileProperty property : id.profileProperties()) {
                        writeString(out, property.name());
                        writeString(out, property.value());
                        writeString(out, property.signature());
                    }
                }
            }

            @Override
            public Messages.LoginKeyRes decode(DataInput in) throws IOException {
                boolean verified = in.readBoolean();
                String reason = readString(in);
                byte[] sharedSecret = readBytes(in);
                Messages.MasterVerifiedIdentity identity = null;
                if (verified) {
                    identity = new Messages.MasterVerifiedIdentity(
                            readString(in), readString(in), readString(in),
                            readUuid(in), readString(in), readUuid(in), in.readLong(), readBytes(in),
                            null
                    );
                    Messages.ProfileProperty[] properties = new Messages.ProfileProperty[in.readShort() & 0xFFFF];
                    for (int i = 0; i < properties.length; i++) {
                        properties[i] = new Messages.ProfileProperty(
                                readString(in), readString(in), readString(in));
                    }
                    identity = new Messages.MasterVerifiedIdentity(
                            identity.issuer(), identity.keyId(), identity.audience(),
                            identity.playerUuid(), identity.sessionId(), identity.nonce(),
                            identity.expiresAt(), identity.signature(), properties);
                }
                return new Messages.LoginKeyRes(verified, reason, sharedSecret, identity);
            }
        });
        register(MessageType.SESSION_CLOSED, new Codec<Messages.SessionClosed>() {
            @Override
            public void encode(DataOutput out, Messages.SessionClosed value) throws IOException {
                writeUuid(out, value.playerUuid());
                writeString(out, value.sessionId());
                writeString(out, value.reason());
            }

            @Override
            public Messages.SessionClosed decode(DataInput in) throws IOException {
                return new Messages.SessionClosed(readUuid(in), readString(in), readString(in));
            }
        });
        register(MessageType.SUBMIT_OPERATION, new Codec<Messages.SubmitOperation>() {
            @Override
            public void encode(DataOutput out, Messages.SubmitOperation value) throws IOException {
                writeUuid(out, value.operationId());
                writeUuid(out, value.playerId());
                writeString(out, value.virtualSessionId());
                out.writeLong(value.clientInteractionSequence());
                out.writeByte(value.operationType().id());
                writeDimension(out, value.dimension());
                out.writeInt(value.posX());
                out.writeInt(value.posY());
                out.writeInt(value.posZ());
                out.writeLong(value.baseRevision());
                writeString(out, value.preconditions());
                writeString(out, value.placeState());
                out.writeLong(value.sentAt());
            }

            @Override
            public Messages.SubmitOperation decode(DataInput in) throws IOException {
                byte[] operationId = readUuid(in);
                byte[] playerId = readUuid(in);
                String virtualSessionId = readString(in);
                long clientInteractionSequence = in.readLong();
                Messages.OperationType operationType = Messages.OperationType.fromId(in.readUnsignedByte());
                if (operationType == null) {
                    throw new IOException("unknown operationType");
                }
                DimensionRef dimension = readDimension(in);
                int posX = in.readInt();
                int posY = in.readInt();
                int posZ = in.readInt();
                long baseRevision = in.readLong();
                String preconditions = readString(in);
                String placeState = readString(in);
                long sentAt = in.readLong();
                return new Messages.SubmitOperation(
                        operationId, playerId, virtualSessionId, clientInteractionSequence,
                        operationType, dimension, posX, posY, posZ,
                        baseRevision, preconditions, placeState, sentAt);
            }
        });
        register(MessageType.CHUNK_MATERIALIZE_REQ, new Codec<Messages.ChunkMaterializeReq>() {
            @Override
            public void encode(DataOutput out, Messages.ChunkMaterializeReq value) throws IOException {
                out.writeLong(value.requestId());
                writeDimension(out, value.dimension());
                out.writeInt(value.chunkX());
                out.writeInt(value.chunkZ());
                out.writeByte(value.priority().id());
                out.writeByte(value.reason().id());
            }

            @Override
            public Messages.ChunkMaterializeReq decode(DataInput in) throws IOException {
                long requestId = in.readLong();
                DimensionRef dimension = readDimension(in);
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                Messages.MaterializePriority priority = Messages.MaterializePriority.fromId(in.readUnsignedByte());
                if (priority == null) {
                    throw new IOException("unknown materialize priority");
                }
                Messages.MaterializeReason reason = Messages.MaterializeReason.fromId(in.readUnsignedByte());
                if (reason == null) {
                    throw new IOException("unknown materialize reason");
                }
                return new Messages.ChunkMaterializeReq(requestId, dimension, chunkX, chunkZ, priority, reason);
            }
        });
        register(MessageType.CHUNK_MATERIALIZE_RES, new Codec<Messages.ChunkMaterializeRes>() {
            @Override
            public void encode(DataOutput out, Messages.ChunkMaterializeRes value) throws IOException {
                out.writeLong(value.requestId());
                out.writeBoolean(value.accepted());
                writeString(out, value.reason());
            }

            @Override
            public Messages.ChunkMaterializeRes decode(DataInput in) throws IOException {
                return new Messages.ChunkMaterializeRes(in.readLong(), in.readBoolean(), readString(in));
            }
        });
        register(MessageType.CHAT_RELAY, new Codec<Messages.ChatRelay>() {
            @Override
            public void encode(DataOutput out, Messages.ChatRelay value) throws IOException {
                writeUuid(out, value.senderUuid());
                writeString(out, value.senderName());
                writeString(out, value.message());
            }

            @Override
            public Messages.ChatRelay decode(DataInput in) throws IOException {
                return new Messages.ChatRelay(readUuid(in), readString(in), readString(in));
            }
        });
        register(MessageType.CHAT_BROADCAST, new Codec<Messages.ChatBroadcast>() {
            @Override
            public void encode(DataOutput out, Messages.ChatBroadcast value) throws IOException {
                writeUuid(out, value.senderUuid());
                writeString(out, value.senderName());
                writeString(out, value.message());
            }

            @Override
            public Messages.ChatBroadcast decode(DataInput in) throws IOException {
                return new Messages.ChatBroadcast(readUuid(in), readString(in), readString(in));
            }
        });
        register(MessageType.OPERATION_COMMITTED, new Codec<Messages.OperationCommitted>() {
            @Override
            public void encode(DataOutput out, Messages.OperationCommitted value) throws IOException {
                writeUuid(out, value.operationId());
                writeString(out, value.canonicalState());
                out.writeLong(value.chunkRevision());
                out.writeLong(value.epoch());
                out.writeLong(value.committedAt());
            }

            @Override
            public Messages.OperationCommitted decode(DataInput in) throws IOException {
                return new Messages.OperationCommitted(
                        readUuid(in), readString(in), in.readLong(), in.readLong(), in.readLong());
            }
        });
        register(MessageType.OPERATION_REJECTED, new Codec<Messages.OperationRejected>() {
            @Override
            public void encode(DataOutput out, Messages.OperationRejected value) throws IOException {
                writeUuid(out, value.operationId());
                writeString(out, value.reason());
                writeDimension(out, value.dimension());
                out.writeShort(value.resyncChunkX().length);
                for (int i = 0; i < value.resyncChunkX().length; i++) {
                    out.writeInt(value.resyncChunkX()[i]);
                    out.writeInt(value.resyncChunkZ()[i]);
                }
                out.writeLong(value.rejectedAt());
            }

            @Override
            public Messages.OperationRejected decode(DataInput in) throws IOException {
                byte[] operationId = readUuid(in);
                String reason = readString(in);
                DimensionRef dimension = readDimension(in);
                int count = in.readUnsignedShort();
                int[] chunkX = new int[count];
                int[] chunkZ = new int[count];
                for (int i = 0; i < count; i++) {
                    chunkX[i] = in.readInt();
                    chunkZ[i] = in.readInt();
                }
                long rejectedAt = in.readLong();
                return new Messages.OperationRejected(operationId, reason, dimension, chunkX, chunkZ, rejectedAt);
            }
        });
        register(MessageType.GET_OPERATION_STATUS, new Codec<Messages.GetOperationStatus>() {
            @Override
            public void encode(DataOutput out, Messages.GetOperationStatus value) throws IOException {
                writeUuid(out, value.operationId());
            }

            @Override
            public Messages.GetOperationStatus decode(DataInput in) throws IOException {
                return new Messages.GetOperationStatus(readUuid(in));
            }
        });
        register(MessageType.OPERATION_STATUS_RES, new Codec<Messages.OperationStatusRes>() {
            @Override
            public void encode(DataOutput out, Messages.OperationStatusRes value) throws IOException {
                writeUuid(out, value.operationId());
                out.writeByte(value.status().id());
                writeString(out, value.canonicalState());
                out.writeLong(value.chunkRevision());
                writeString(out, value.rejectReason());
                writeDimension(out, value.dimension());
                out.writeShort(value.resyncChunkX().length);
                for (int i = 0; i < value.resyncChunkX().length; i++) {
                    out.writeInt(value.resyncChunkX()[i]);
                    out.writeInt(value.resyncChunkZ()[i]);
                }
            }

            @Override
            public Messages.OperationStatusRes decode(DataInput in) throws IOException {
                byte[] operationId = readUuid(in);
                Messages.OperationStatusRes.OperationStatus status =
                        Messages.OperationStatusRes.OperationStatus.fromId(in.readUnsignedByte());
                if (status == null) {
                    throw new IOException("unknown operation status");
                }
                String canonicalState = readString(in);
                long chunkRevision = in.readLong();
                String rejectReason = readString(in);
                DimensionRef dimension = readDimension(in);
                int count = in.readUnsignedShort();
                int[] chunkX = new int[count];
                int[] chunkZ = new int[count];
                for (int i = 0; i < count; i++) {
                    chunkX[i] = in.readInt();
                    chunkZ[i] = in.readInt();
                }
                return new Messages.OperationStatusRes(
                        operationId, status, canonicalState, chunkRevision, rejectReason,
                        dimension, chunkX, chunkZ);
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

    /** 维度出生点（WorldEpoch 字段；x/y/z double + yaw/pitch float，与 26.3 RespawnData 数值精度一致）。 */
    static void writeSpawn(DataOutput out, Messages.SpawnPoint spawn) throws IOException {
        out.writeDouble(spawn.x());
        out.writeDouble(spawn.y());
        out.writeDouble(spawn.z());
        out.writeFloat(spawn.yaw());
        out.writeFloat(spawn.pitch());
    }

    static Messages.SpawnPoint readSpawn(DataInput in) throws IOException {
        return new Messages.SpawnPoint(in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat());
    }

    /** 16 字节定长 UUID 二进制（与 Java UUID 的字节序约定一致，两字节序均为定长 16 字节）。 */
    public static void writeUuid(DataOutput out, byte[] uuid) throws IOException {
        if (uuid == null || uuid.length != 16) {
            throw new IOException("uuid 必须为 16 字节: " + (uuid == null ? "null" : uuid.length));
        }
        out.write(uuid);
    }

    public static byte[] readUuid(DataInput in) throws IOException {
        byte[] uuid = new byte[16];
        in.readFully(uuid);
        return uuid;
    }

    /** 2 字节长度前缀 + 原始字节（签名等二进制字段）。 */
    public static void writeBytes(DataOutput out, byte[] value) throws IOException {
        if (value == null || value.length > ProtocolConstants.MAX_STRING_BYTES) {
            throw new IOException("bytes 长度越界: " + (value == null ? "null" : value.length));
        }
        out.writeShort(value.length);
        out.write(value);
    }

    public static byte[] readBytes(DataInput in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
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

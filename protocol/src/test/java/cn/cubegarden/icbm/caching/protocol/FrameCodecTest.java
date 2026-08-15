package cn.cubegarden.icbm.caching.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * golden 序列化测试：线上字节布局锁定（AGENTS.md「协议改动：golden 序列化测试」）。
 */
class FrameCodecTest {

    private static byte[] encodeBody(MessageType type, Object body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageBodyCodec.encode(type, new DataOutputStream(bytes), body);
        return bytes.toByteArray();
    }

    private static Object decodeBody(MessageType type, byte[] body) throws IOException {
        return MessageBodyCodec.decode(type, new DataInputStream(new ByteArrayInputStream(body)));
    }

    @Test
    void envelopeGoldenBytes() {
        Envelope envelope = Envelope.of(
                MessageType.HELLO, 7L, "source-a", 42L, 1L, 1_700_000_000_000L,
                new byte[] {1, 2, 3}
        );
        byte[] frame = FrameCodec.encodeFrame(envelope);

        // ICBM magic + schema 1 + type 0x01 + requestId 7 + sourceServerId(8 字节 "source-a")
        byte[] expected = new byte[] {
                0x49, 0x43, 0x42, 0x4D,                 // "ICBM"
                0x01,                                   // schemaVersion
                0x01,                                   // HELLO
                0, 0, 0, 0, 0, 0, 0, 7,                 // requestId=7
                0, 8, 's', 'o', 'u', 'r', 'c', 'e', '-', 'a', // 字符串长度 8 + UTF-8
                0, 0, 0, 0, 0, 0, 0, 42,                // worldEpoch=42
                0, 0, 0, 0, 0, 0, 0, 1,                 // sequence=1
                0, 0, 1, (byte) 0x8B, (byte) 0xCF, (byte) 0xE5, 0x68, 0x00, // sentAt=1700000000000
                0, 0, 0, 3,                             // bodyLength=3
                1, 2, 3                                 // body
        };
        assertArrayEquals(expected, frame);
    }

    @Test
    void helloBodyGoldenBytes() throws IOException {
        Messages.Hello hello = new Messages.Hello(
                Role.SOURCE, "lobby-1", "26.3", 1073742154, "0.19.3", "0.157.1+26.3",
                "modpack-hash", "registry-hash", (byte) 0
        );
        byte[] body = encodeBody(MessageType.HELLO, hello);
        // role SOURCE(0) + serverName "lobby-1"(7) + "26.3" + protocol int + ... + capabilities
        assertEquals(0, body[0]);
        // serverName: 长度 7 + "lobby-1"
        assertEquals(0, body[1]);
        assertEquals(7, body[2]);
        assertEquals('l', body[3]);
        assertEquals('1', body[9]);
        // "26.3": 长度 4 + '2' 在 body[10..11]
        assertEquals(0, body[10]);
        assertEquals(4, body[11]);
        assertEquals('2', body[12]);
        // protocol int (1073742154 = 0x4000014A) 位于 "26.3" 之后
        assertEquals(0x40, body[16] & 0xFF);
        assertEquals(0x00, body[17] & 0xFF);
        assertEquals(0x01, body[18] & 0xFF);
        assertEquals(0x4A, body[19] & 0xFF);

        Messages.Hello decoded = MessageBodyCodec.decode(MessageType.HELLO,
                new DataInputStream(new ByteArrayInputStream(body)));
        assertEquals(hello, decoded);
    }

    @Test
    void roundTripAllMessageTypes() throws IOException {
        DimensionRef dim = DimensionRef.of("minecraft", "overworld");
        byte[] payload = {9, 8, 7};

        Object[] samples = new Object[] {
                new Messages.Hello(Role.SLAVE, "slave-a", "26.3", 1073742154, "0.19.3", "0.157.1+26.3", "m", "r", (byte) 1),
                new Messages.HelloAck(true, "", "master-1"),
                new Messages.WorldEpoch(dim, 3, EpochReason.ROLLBACK, "reg",
                        new Messages.SpawnPoint(0.5, 64.0, -1.25, 90.0F, 0.0F)),
                new Messages.Invalidate(dim, 3, -1, 7, InvalidateReason.BLOCK_STATE),
                new Messages.PutSnapshot(dim, 3, -1, 7, "NBT_CHUNK_V1", "GZIP", 3, "abc", payload),
                new Messages.PutSnapshotAck(dim, 3, -1, 7, PutResult.OK),
                new Messages.ResolveChunkReq(dim, 3, -1, 1073742154, "reg", 5),
                new Messages.ResolveChunkRes(dim, 3, -1, ResolveStatus.HIT, 7, "obj", "hash"),
                new Messages.Subscribe(new DimensionRef[] {dim}, true, 0),
                new Messages.SnapshotPush(new Messages.PutSnapshot(dim, 3, -1, 7, "NBT_CHUNK_V1", "GZIP", 3, "abc", payload)),
                new Messages.Ack(99),
                new Messages.ResyncReq(dim, 5),
                new Messages.ResyncRes(dim, 5, 7, 2),
                new Messages.Heartbeat(1_700_000_000_000L, 10),
                new Messages.AdminInvalidate(dim, "REGION", 0, 0, 1, 1, "admin test"),
                new Messages.ErrorMessage("bad frame"),
                new Messages.LoginVerifyReq("Steve", new byte[16],
                        "slave-a", "127.0.0.1:50000", new byte[16], 1_700_000_000_000L),
                new Messages.LoginVerifyRes(true, "", new byte[] {1, 2, 3, 4},
                        new byte[] {48, 92, 48, 13, 6, 9}),
                Messages.LoginVerifyRes.reject("验证失败"),
                new Messages.LoginKeyReq(new byte[16], new byte[16], new byte[] {5, 6, 7, 8},
                        new byte[] {9, 10, 11, 12}),
                new Messages.LoginKeyRes(true, "", new byte[16], new Messages.MasterVerifiedIdentity(
                        "master", "key-1", "slave-a", new byte[16], "sess-1", new byte[16],
                        1_700_000_100_000L, new byte[] {1, 2, 3},
                        new Messages.ProfileProperty[] {new Messages.ProfileProperty(
                                "textures", "eyJ0ZXh0dXJlcyI6e30=", "sig")})),
                Messages.LoginKeyRes.reject("UNVERIFIED_USERNAME"),
                new Messages.SessionClosed(new byte[16], "sess-1", "disconnect"),
                new Messages.SubmitOperation(new byte[16], new byte[16], "sess-1", 7,
                        Messages.OperationType.PLACE, dim, 1, 64, -3, 5,
                        "sha256-state", "minecraft:diamond_block", 1_700_000_000_000L),
                new Messages.OperationCommitted(new byte[16], "minecraft:diamond_block", 6, 1, 1_700_000_000_100L),
                new Messages.OperationRejected(new byte[16], "precondition-mismatch", dim,
                        new int[] {0}, new int[] {0}, 1_700_000_000_100L),
                new Messages.GetOperationStatus(new byte[16]),
                new Messages.OperationStatusRes(new byte[16],
                        Messages.OperationStatusRes.OperationStatus.COMMITTED,
                        "minecraft:diamond_block", 6, "", dim,
                        new int[0], new int[0]),
                new Messages.ChunkMaterializeReq(77, dim, 3, -1,
                        Messages.MaterializePriority.HIGH, Messages.MaterializeReason.PLAYER_PROXIMITY),
                new Messages.ChunkMaterializeRes(77, true, "")
        };
        MessageType[] types = new MessageType[] {
                MessageType.HELLO, MessageType.HELLO_ACK, MessageType.WORLD_EPOCH,
                MessageType.INVALIDATE, MessageType.PUT_SNAPSHOT, MessageType.PUT_SNAPSHOT_ACK,
                MessageType.RESOLVE_CHUNK_REQ, MessageType.RESOLVE_CHUNK_RES, MessageType.SUBSCRIBE,
                MessageType.SNAPSHOT_PUSH, MessageType.ACK, MessageType.RESYNC_REQ,
                MessageType.RESYNC_RES, MessageType.HEARTBEAT, MessageType.ADMIN_INVALIDATE,
                MessageType.ERROR, MessageType.LOGIN_VERIFY_REQ, MessageType.LOGIN_VERIFY_RES,
                MessageType.LOGIN_VERIFY_RES, MessageType.LOGIN_KEY_REQ, MessageType.LOGIN_KEY_RES,
                MessageType.LOGIN_KEY_RES, MessageType.SESSION_CLOSED,
                MessageType.SUBMIT_OPERATION, MessageType.OPERATION_COMMITTED,
                MessageType.OPERATION_REJECTED, MessageType.GET_OPERATION_STATUS,
                MessageType.OPERATION_STATUS_RES,
                MessageType.CHUNK_MATERIALIZE_REQ, MessageType.CHUNK_MATERIALIZE_RES
        };
        for (int i = 0; i < samples.length; i++) {
            byte[] body = encodeBody(types[i], samples[i]);
            Object decoded = decodeBody(types[i], body);
            assertMessageEquals(types[i], samples[i], decoded);
            // 包络整体 round-trip（body 为字节数组，逐字段比较）
            Envelope envelope = Envelope.of(types[i], 1, "peer", 0, 1, 2, body);
            Envelope back = FrameCodec.decodeFrame(FrameCodec.encodeFrame(envelope));
            assertEquals(envelope.schemaVersion(), back.schemaVersion());
            assertEquals(envelope.messageType(), back.messageType());
            assertEquals(envelope.requestId(), back.requestId());
            assertEquals(envelope.sourceServerId(), back.sourceServerId());
            assertEquals(envelope.worldEpoch(), back.worldEpoch());
            assertEquals(envelope.sequence(), back.sequence());
            assertEquals(envelope.sentAt(), back.sentAt());
            assertArrayEquals(envelope.body(), back.body());
        }
    }

    /** byte[] 字段的 record 用引用相等：对含 payload 的消息逐字段比较。 */
    private static void assertMessageEquals(MessageType type, Object expected, Object actual) {
        if (expected instanceof Messages.PutSnapshot e && actual instanceof Messages.PutSnapshot a) {
            assertEquals(e.dimension(), a.dimension(), "round-trip " + type);
            assertEquals(e.chunkX(), a.chunkX());
            assertEquals(e.chunkZ(), a.chunkZ());
            assertEquals(e.revision(), a.revision());
            assertEquals(e.snapshotFormat(), a.snapshotFormat());
            assertEquals(e.compression(), a.compression());
            assertEquals(e.uncompressedLength(), a.uncompressedLength());
            assertEquals(e.payloadHash(), a.payloadHash());
            assertArrayEquals(e.payload(), a.payload());
            return;
        }
        if (expected instanceof Messages.SnapshotPush e && actual instanceof Messages.SnapshotPush a) {
            assertMessageEquals(type, e.snapshot(), a.snapshot());
            return;
        }
        if (expected instanceof Messages.Subscribe e && actual instanceof Messages.Subscribe a) {
            assertEquals(e.pushEnabled(), a.pushEnabled());
            assertEquals(e.ackedSequence(), a.ackedSequence());
            assertArrayEquals(e.dimensions(), a.dimensions());
            return;
        }
        if (expected instanceof Messages.LoginVerifyReq e && actual instanceof Messages.LoginVerifyReq a) {
            assertEquals(e.playerName(), a.playerName());
            assertArrayEquals(e.playerUuid(), a.playerUuid());
            assertEquals(e.slaveId(), a.slaveId());
            assertEquals(e.connectionInfo(), a.connectionInfo());
            assertArrayEquals(e.nonce(), a.nonce());
            assertEquals(e.sentAt(), a.sentAt());
            return;
        }
        if (expected instanceof Messages.LoginVerifyRes e && actual instanceof Messages.LoginVerifyRes a) {
            assertEquals(e.ok(), a.ok());
            assertEquals(e.reason(), a.reason());
            assertArrayEquals(e.challenge(), a.challenge());
            assertArrayEquals(e.publicKeyDer(), a.publicKeyDer());
            return;
        }
        if (expected instanceof Messages.LoginKeyReq e && actual instanceof Messages.LoginKeyReq a) {
            assertArrayEquals(e.playerUuid(), a.playerUuid());
            assertArrayEquals(e.nonce(), a.nonce());
            assertArrayEquals(e.keybytes(), a.keybytes());
            assertArrayEquals(e.encryptedChallenge(), a.encryptedChallenge());
            return;
        }
        if (expected instanceof Messages.LoginKeyRes e && actual instanceof Messages.LoginKeyRes a) {
            assertEquals(e.verified(), a.verified());
            assertEquals(e.reason(), a.reason());
            assertArrayEquals(e.sharedSecret(), a.sharedSecret());
            if (e.identity() == null) {
                assertTrue(a.identity() == null);
            } else {
                assertTrue(a.identity() != null, "identity 缺失");
                assertEquals(e.identity().issuer(), a.identity().issuer());
                assertEquals(e.identity().keyId(), a.identity().keyId());
                assertEquals(e.identity().audience(), a.identity().audience());
                assertArrayEquals(e.identity().playerUuid(), a.identity().playerUuid());
                assertEquals(e.identity().sessionId(), a.identity().sessionId());
                assertArrayEquals(e.identity().nonce(), a.identity().nonce());
                assertEquals(e.identity().expiresAt(), a.identity().expiresAt());
                assertArrayEquals(e.identity().signature(), a.identity().signature());
            }
            return;
        }
        if (expected instanceof Messages.SessionClosed e && actual instanceof Messages.SessionClosed a) {
            assertArrayEquals(e.playerUuid(), a.playerUuid());
            assertEquals(e.sessionId(), a.sessionId());
            assertEquals(e.reason(), a.reason());
            return;
        }
        if (expected instanceof Messages.SubmitOperation e && actual instanceof Messages.SubmitOperation a) {
            assertArrayEquals(e.operationId(), a.operationId());
            assertArrayEquals(e.playerId(), a.playerId());
            assertEquals(e.virtualSessionId(), a.virtualSessionId());
            assertEquals(e.clientInteractionSequence(), a.clientInteractionSequence());
            assertEquals(e.operationType(), a.operationType());
            assertEquals(e.dimension(), a.dimension());
            assertEquals(e.posX(), a.posX());
            assertEquals(e.posY(), a.posY());
            assertEquals(e.posZ(), a.posZ());
            assertEquals(e.baseRevision(), a.baseRevision());
            assertEquals(e.preconditions(), a.preconditions());
            assertEquals(e.placeState(), a.placeState());
            assertEquals(e.sentAt(), a.sentAt());
            return;
        }
        if (expected instanceof Messages.OperationCommitted e && actual instanceof Messages.OperationCommitted a) {
            assertArrayEquals(e.operationId(), a.operationId());
            assertEquals(e.canonicalState(), a.canonicalState());
            assertEquals(e.chunkRevision(), a.chunkRevision());
            assertEquals(e.epoch(), a.epoch());
            assertEquals(e.committedAt(), a.committedAt());
            return;
        }
        if (expected instanceof Messages.OperationRejected e && actual instanceof Messages.OperationRejected a) {
            assertArrayEquals(e.operationId(), a.operationId());
            assertEquals(e.reason(), a.reason());
            assertEquals(e.dimension(), a.dimension());
            assertArrayEquals(e.resyncChunkX(), a.resyncChunkX());
            assertArrayEquals(e.resyncChunkZ(), a.resyncChunkZ());
            assertEquals(e.rejectedAt(), a.rejectedAt());
            return;
        }
        if (expected instanceof Messages.GetOperationStatus e && actual instanceof Messages.GetOperationStatus a) {
            assertArrayEquals(e.operationId(), a.operationId());
            return;
        }
        if (expected instanceof Messages.OperationStatusRes e && actual instanceof Messages.OperationStatusRes a) {
            assertArrayEquals(e.operationId(), a.operationId());
            assertEquals(e.status(), a.status());
            assertEquals(e.canonicalState(), a.canonicalState());
            assertEquals(e.chunkRevision(), a.chunkRevision());
            assertEquals(e.rejectReason(), a.rejectReason());
            assertEquals(e.dimension(), a.dimension());
            assertArrayEquals(e.resyncChunkX(), a.resyncChunkX());
            assertArrayEquals(e.resyncChunkZ(), a.resyncChunkZ());
            return;
        }
        assertEquals(expected, actual, "round-trip " + type);
    }

    @Test
    void badMagicRejected() {
        byte[] frame = FrameCodec.encodeFrame(Envelope.of(
                MessageType.ACK, 0, "p", 0, 1, 1, new byte[] {0, 0, 0, 0, 0, 0, 0, 0}
        ));
        frame[0] = 'X';
        assertThrows(IOException.class, () -> FrameCodec.decodeFrame(frame));
    }

    @Test
    void unknownMessageTypeRejected() {
        byte[] frame = FrameCodec.encodeFrame(Envelope.of(
                MessageType.ACK, 0, "p", 0, 1, 1, new byte[] {0, 0, 0, 0, 0, 0, 0, 0}
        ));
        frame[5] = 0x7F; // 未知 type
        assertThrows(IOException.class, () -> FrameCodec.decodeFrame(frame));
    }

    /** 单帧硬上限兜底（帧层负责实际大小限制）。 */
    @Test
    void oversizeBodyRejectedAtEnvelopeConstruction() {
        byte[] big = new byte[ProtocolConstants.MAX_FRAME_HARD_LIMIT + 1];
        assertThrows(IllegalArgumentException.class, () -> Envelope.of(
                MessageType.ACK, 0, "p", 0, 1, 1, big
        ));
    }

    @Test
    void snapshotPayloadLengthGuard() throws IOException {
        // 手工构造超长 payload 长度字段：解码必须拒绝
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        MessageBodyCodec.writeDimension(out, DimensionRef.of("minecraft", "overworld"));
        out.writeInt(0);
        out.writeInt(0);
        out.writeLong(1);
        MessageBodyCodec.writeString(out, "NBT_CHUNK_V1");
        MessageBodyCodec.writeString(out, "GZIP");
        out.writeInt(0);
        MessageBodyCodec.writeString(out, "h");
        out.writeInt(ProtocolConstants.MAX_SNAPSHOT_BYTES + 1); // 超限
        byte[] body = bytes.toByteArray();
        assertThrows(IOException.class, () -> MessageBodyCodec.decode(
                MessageType.PUT_SNAPSHOT, new DataInputStream(new ByteArrayInputStream(body))
        ));
    }

    @Test
    void allMessageTypesHaveCodecs() {
        for (MessageType type : MessageType.values()) {
            assertTrue(MessageBodyCodec.hasBody(type), "missing codec for " + type);
        }
    }
}

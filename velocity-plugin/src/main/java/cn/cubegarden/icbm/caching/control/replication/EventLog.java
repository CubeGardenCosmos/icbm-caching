package cn.cubegarden.icbm.caching.control.replication;

import cn.cubegarden.icbm.caching.protocol.ChunkRef;
import cn.cubegarden.icbm.caching.protocol.DimensionRef;
import cn.cubegarden.icbm.caching.protocol.Messages;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * master 事件日志（追加写，草稿 phase2-control-protocol §4.2/4.3）：记录
 * WORLD_EPOCH / INVALIDATE / SNAPSHOT_COMMITTED，供 slave 断线重连后按
 * ackedSequence 重放（补发/范围失效/epoch 切换全部经日志送达）。
 *
 * <p>记录格式：{@code seq(8) + type(1) + payload}。追加写 + 启动全量重读；
 * 损坏尾部容忍截断（最后一条不完整视为写中断），中段损坏则拒绝加载（fail closed）。
 */
public final class EventLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventLog.class);

    public enum EventType {
        WORLD_EPOCH(1),
        INVALIDATE(2),
        SNAPSHOT_COMMITTED(3);

        private final byte id;

        EventType(int id) {
            this.id = (byte) id;
        }
    }

    /** 单条事件（seq 为 master 事件序号，跨连接单调；spawn 仅 WORLD_EPOCH 使用）。 */
    public record Event(long seq, EventType type, DimensionRef dimension,
                        ChunkRef chunk, long revision, String payloadHash, String fingerprint,
                        Messages.SpawnPoint spawn) {
    }

    private final Path file;
    private final long maxEntries;
    private final List<Event> events = new ArrayList<>();
    private long nextSeq = 1;

    public EventLog(Path file, long maxEntries) {
        this.file = file;
        this.maxEntries = maxEntries;
    }

    /** 启动恢复：全量读入内存索引；不完整尾部容忍截断，中段损坏抛 IOException。 */
    public synchronized void recover() throws IOException {
        this.events.clear();
        this.nextSeq = 1;
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file)))) {
            while (true) {
                Event event = readEventOrNull(in);
                if (event == null) {
                    break; // 尾部截断（进程被强杀/磁盘写中断）：追加式日志崩溃恢复标准语义
                }
                this.events.add(event);
                this.nextSeq = event.seq() + 1;
            }
        }
        LOGGER.info("[control] events.log 恢复: {} 条，nextSeq={}", this.events.size(), this.nextSeq);
    }

    /** 读一条事件；EOF/截断（含记录中部）→ null（容忍不完整尾部，不抛）。 */
    private Event readEventOrNull(DataInputStream in) throws IOException {
        try {
            long seq = in.readLong();
            byte typeId = in.readByte();
            EventType type = null;
            for (EventType t : EventType.values()) {
                if (t.id == typeId) {
                    type = t;
                    break;
                }
            }
            if (type == null) {
                throw new IOException("events.log 未知事件类型: " + typeId);
            }
            DimensionRef dimension = null;
            ChunkRef chunk = null;
            long revision = 0;
            String hash = "";
            String fingerprint = "";
            Messages.SpawnPoint spawn = Messages.SpawnPoint.DEFAULT;
            switch (type) {
                case WORLD_EPOCH -> {
                    dimension = readDimension(in);
                    revision = in.readLong();
                    fingerprint = readString(in);
                    spawn = readSpawn(in);
                }
                case INVALIDATE -> {
                    dimension = readDimension(in);
                    chunk = new ChunkRef(dimension, in.readInt(), in.readInt());
                    revision = in.readLong();
                }
                case SNAPSHOT_COMMITTED -> {
                    dimension = readDimension(in);
                    chunk = new ChunkRef(dimension, in.readInt(), in.readInt());
                    revision = in.readLong();
                    hash = readString(in);
                }
            }
            return new Event(seq, type, dimension, chunk, revision, hash, fingerprint, spawn);
        } catch (EOFException e) {
            LOGGER.warn("[control] events.log 尾部截断（容忍，忽略剩余）");
            return null;
        }
    }

    public synchronized long append(EventType type, DimensionRef dimension, ChunkRef chunk,
                                    long revision, String payloadHash, String fingerprint,
                                    Messages.SpawnPoint spawn) {
        long seq = this.nextSeq++;
        Event event = new Event(seq, type, dimension, chunk, revision, payloadHash, fingerprint, spawn);
        this.events.add(event);
        try {
            Files.createDirectories(this.file.getParent());
            // 追加写（单写者，synchronized）；写中断留下的不完整尾部由 recover 容忍截断
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(this.file,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)))) {
                writeEvent(out, event);
            }
        } catch (IOException e) {
            throw new IllegalStateException("events.log 追加失败", e);
        }
        while (this.events.size() > this.maxEntries) {
            this.events.remove(0);
        }
        return seq;
    }

    private static void writeEvent(DataOutputStream out, Event event) throws IOException {
        out.writeLong(event.seq());
        out.writeByte(event.type().id);
        switch (event.type()) {
            case WORLD_EPOCH -> {
                writeDimension(out, event.dimension());
                out.writeLong(event.revision());
                writeString(out, event.fingerprint());
            }
            case INVALIDATE -> {
                writeDimension(out, event.dimension());
                out.writeInt(event.chunk().x());
                out.writeInt(event.chunk().z());
                out.writeLong(event.revision());
            }
            case SNAPSHOT_COMMITTED -> {
                writeDimension(out, event.dimension());
                out.writeInt(event.chunk().x());
                out.writeInt(event.chunk().z());
                out.writeLong(event.revision());
                writeString(out, event.payloadHash());
            }
        }
    }

    /** 重放 seq &gt; afterSeq 的事件（按序；用于 slave 断线重连补发）。 */
    public synchronized List<Event> replayAfter(long afterSeq) {
        List<Event> result = new ArrayList<>();
        for (Event event : this.events) {
            if (event.seq() > afterSeq) {
                result.add(event);
            }
        }
        return result;
    }

    public synchronized long lastSequence() {
        return this.events.isEmpty() ? 0 : this.events.get(this.events.size() - 1).seq();
    }

    private static void writeDimension(DataOutputStream out, DimensionRef dimension) throws IOException {
        writeString(out, dimension.id());
    }

    private static DimensionRef readDimension(DataInputStream in) throws IOException {
        return DimensionRef.parse(readString(in));
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeSpawn(DataOutputStream out, Messages.SpawnPoint spawn) throws IOException {
        out.writeDouble(spawn.x());
        out.writeDouble(spawn.y());
        out.writeDouble(spawn.z());
        out.writeFloat(spawn.yaw());
        out.writeFloat(spawn.pitch());
    }

    private static Messages.SpawnPoint readSpawn(DataInputStream in) throws IOException {
        return new Messages.SpawnPoint(in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat());
    }
}

package cn.cubegarden.icbm.caching.control.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

/**
 * 数据包缓存（权威会话源服下行包；ARCHITECTURE §3.2「源服返回的数据包在 master 侧缓存」）。
 *
 * <p>本里程碑落地为**字节级审计日志**（append-only、有界内存）：记录每个转发的明文 MC
 * 报文（方向/时间戳/长度/SHA-256）。对象库仍是唯一基线（committed+epoch 守卫不变）；
 * 权威会话期间源服区块数据由源服快照管道（ChunkSnapshotTicker）PUT 进对象库——"形成
 * 快照"的落点。字节日志不参与基线判定（不猜测 26.3 包结构，避免复制常量）。
 */
public final class PacketLog {

    /** 缓存条目（方向：SLAVE_TO_BACKEND / BACKEND_TO_SLAVE）。 */
    public record Entry(long timestampMillis, String direction, int length, String hash) {
    }

    private final Deque<Entry> entries;
    private final int capacity;

    public PacketLog(int capacity) {
        this.capacity = capacity;
        this.entries = new ArrayDeque<>();
    }

    /** 记录一条转发报文（有界：超出容量丢弃最旧）。 */
    public void append(String direction, byte[] body) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            hash = "?";
        }
        synchronized (this.entries) {
            this.entries.addLast(new Entry(System.currentTimeMillis(), direction, body.length, hash));
            while (this.entries.size() > this.capacity) {
                this.entries.removeFirst();
            }
        }
    }

    /** 快照（测试/审计读取）。 */
    public List<Entry> snapshot() {
        synchronized (this.entries) {
            return new ArrayList<>(this.entries);
        }
    }

    public int size() {
        synchronized (this.entries) {
            return this.entries.size();
        }
    }
}

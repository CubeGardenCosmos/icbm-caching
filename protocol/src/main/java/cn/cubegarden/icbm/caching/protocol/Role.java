package cn.cubegarden.icbm.caching.protocol;

/**
 * 控制平面角色（CACHE_PROTOCOL.md §1 HELLO.role）。
 *
 * <p>权限规则（阶段1 架构修正后重定义，草稿 encapsulated-channel §3.2）：
 * <ul>
 *   <li>SOURCE：可发送 INVALIDATE / PUT_SNAPSHOT / WORLD_EPOCH / ADMIN_INVALIDATE；</li>
 *   <li>SLAVE：可 SUBSCRIBE / RESOLVE_CHUNK_REQ / RESYNC_REQ / LOGIN_VERIFY_REQ / SESSION_CLOSED；</li>
 *   <li>master：单侧发送 SNAPSHOT_PUSH / HEARTBEAT / RESYNC_RES / LOGIN_VERIFY_RES。</li>
 * </ul>
 *
 * <p>角色 id 为线上字节（golden 测试锁定）：{@code SOURCE=0}、{@code SLAVE=1}。
 * 阶段1 架构修正移除了 VIRTUAL_BACKEND/VELOCITY 两个历史角色（原 id 1/2 不再分配，
 * 旧 id 2 解码返回 null → 接收方 fail closed）。
 */
public enum Role {

    SOURCE(0),
    SLAVE(1);

    private final byte id;

    Role(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return this.id;
    }

    public static Role fromId(byte id) {
        for (Role role : values()) {
            if (role.id == id) {
                return role;
            }
        }
        return null;
    }

    public boolean isSlave() {
        return this == SLAVE;
    }
}

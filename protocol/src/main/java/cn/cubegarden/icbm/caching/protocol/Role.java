package cn.cubegarden.icbm.caching.protocol;

/**
 * 控制平面角色（CACHE_PROTOCOL.md §1 HELLO.role）。
 *
 * <p>权限规则（草稿 phase2-control-protocol §4）：
 * <ul>
 *   <li>SOURCE：可发送 INVALIDATE / PUT_SNAPSHOT / WORLD_EPOCH / ADMIN_INVALIDATE；</li>
 *   <li>VELOCITY / VIRTUAL_BACKEND（slave）：可 SUBSCRIBE / RESOLVE_CHUNK_REQ / RESYNC_REQ；</li>
 *   <li>master（控制服务）：单侧发送 SNAPSHOT_PUSH / HEARTBEAT / RESYNC_RES。</li>
 * </ul>
 */
public enum Role {

    SOURCE(0),
    VIRTUAL_BACKEND(1),
    VELOCITY(2);

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
        return this == VIRTUAL_BACKEND || this == VELOCITY;
    }
}

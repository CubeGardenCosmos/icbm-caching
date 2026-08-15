package cn.cubegarden.icbm.caching.control.net;

import cn.cubegarden.icbm.caching.protocol.Role;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对等方注册表：鉴权绑定（封装通道 AUTH UUID）→ 角色绑定、HELLO 注册
 * （sourceServerId → 会话/角色）、源指纹注册（slave 指纹必须与源一致——CACHE_PROTOCOL
 * 「fingerprint 不一致时拒绝快照复用」）。
 */
public final class PeerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerRegistry.class);

    private final Map<String, String> roleBindings; // auth UUID(hex) -> role 名
    private final ConcurrentMap<String, ControlSession> sessions = new ConcurrentHashMap<>(); // sourceServerId -> session
    private final ConcurrentMap<String, Role> roles = new ConcurrentHashMap<>();           // sourceServerId -> role
    private volatile String sourceModpackFingerprint = "";
    private volatile String sourceRegistryFingerprint = "";

    public PeerRegistry(Map<String, String> roleBindings) {
        this.roleBindings = Map.copyOf(roleBindings);
    }

    /** 鉴权绑定标识（AUTH UUID）绑定角色；未配置绑定 → null（调用方按策略拒绝）。 */
    public Role roleForBinding(String bindingId) {
        String roleName = this.roleBindings.get(bindingId);
        return roleName == null ? null : Role.valueOf(roleName);
    }

    /**
     * HELLO 校验并注册。返回拒绝原因（null 表示接受）。
     * 规则：鉴权绑定角色必须与 HELLO.role 一致；源必须带非空指纹；
     * slave 的指纹必须与已注册源的指纹一致。
     */
    public String register(ControlSession session, Role helloRole, String modpackFingerprint,
                           String registryFingerprint) {
        Role boundRole = this.roleForBinding(session.bindingId());
        if (boundRole != null && boundRole != helloRole) {
            return "鉴权绑定 " + session.bindingId() + " 绑定角色 " + boundRole
                    + " 与 HELLO.role " + helloRole + " 不一致";
        }
        if (helloRole == Role.SOURCE) {
            if (modpackFingerprint.isEmpty() || registryFingerprint.isEmpty()) {
                return "源必须提供 modpack/registry fingerprint";
            }
            if (!this.sourceModpackFingerprint.isEmpty()
                    && !this.sourceModpackFingerprint.equals(modpackFingerprint)) {
                return "modpack fingerprint 与已注册源不一致";
            }
            this.sourceModpackFingerprint = modpackFingerprint;
            this.sourceRegistryFingerprint = registryFingerprint;
        } else {
            // slave：指纹必须等于源（未注册源时拒绝，杜绝先连 slave 后连源）
            if (this.sourceModpackFingerprint.isEmpty()) {
                return "源尚未注册，拒绝 slave 连接";
            }
            if (!this.sourceModpackFingerprint.equals(modpackFingerprint)
                    || !this.sourceRegistryFingerprint.equals(registryFingerprint)) {
                return "slave fingerprint 与源不一致（拒绝快照复用）";
            }
        }
        this.sessions.put(session.sourceServerId(), session);
        this.roles.put(session.sourceServerId(), helloRole);
        LOGGER.info("[control] peer 注册: id={} role={} binding={}", session.sourceServerId(), helloRole, session.bindingId());
        return null;
    }

    public void unregister(String sourceServerId) {
        if (sourceServerId != null) {
            this.sessions.remove(sourceServerId);
            this.roles.remove(sourceServerId);
        }
    }

    public Role roleOf(String sourceServerId) {
        return this.roles.get(sourceServerId);
    }

    public boolean isSource(String sourceServerId) {
        return this.roleOf(sourceServerId) == Role.SOURCE;
    }

    public String sourceRegistryFingerprint() {
        return this.sourceRegistryFingerprint;
    }

    public boolean sourceRegistered() {
        return !this.sourceModpackFingerprint.isEmpty();
    }
}

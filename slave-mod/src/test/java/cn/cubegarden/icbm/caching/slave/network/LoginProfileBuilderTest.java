package cn.cubegarden.icbm.caching.slave.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.cubegarden.icbm.caching.protocol.Messages;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * LoginProfileBuilder：按 master 断言构造 GameProfile。回归 authlib 10.0.76 的坑——
 * {@code GameProfile(UUID,String)} 用不可变 {@code PropertyMap.EMPTY}、{@code PropertyMap}
 * 构造即封装 ImmutableMultimap，直接 put 抛 UnsupportedOperationException → 「建立会话密钥
 * 失败（fail closed，拒登录）」（debug-run 实测暴露）。
 */
class LoginProfileBuilderTest {

    @Test
    void buildsProfileWithPropertiesWithoutUnsupportedOperation() {
        Messages.ProfileProperty[] properties = new Messages.ProfileProperty[] {
                new Messages.ProfileProperty("textures", "eyJ0ZXh0dXJlcyI6e30=", "sig-1"),
                new Messages.ProfileProperty("textures", "eyJza2luIjoiYyJ9", "sig-2"),
        };
        UUID uuid = UUID.randomUUID();
        // 不得抛 UnsupportedOperationException（EMPTY PropertyMap put 的回归）
        GameProfile profile = LoginProfileBuilder.build(uuid, "Steve", properties);
        assertEquals(uuid, profile.id());
        assertEquals("Steve", profile.name());
        assertEquals(2, profile.properties().values().size(), "纹理属性必须随断言进入 profile");
        assertFalse(profile.properties().values().isEmpty());
    }

    @Test
    void emptyPropertiesBuildsProfile() {
        GameProfile profile = LoginProfileBuilder.build(UUID.randomUUID(), "Alex",
                new Messages.ProfileProperty[0]);
        assertEquals("Alex", profile.name());
    }
}

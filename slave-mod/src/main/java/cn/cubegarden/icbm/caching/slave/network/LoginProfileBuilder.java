package cn.cubegarden.icbm.caching.slave.network;

import cn.cubegarden.icbm.caching.protocol.Messages;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.UUID;

/**
 * 按 master 身份断言构造 GameProfile（登录 accept 路径；CACHE_PROTOCOL §9）。
 *
 * <p>authlib 10.0.76 语义：{@code GameProfile(UUID, String)} 使用 {@code PropertyMap.EMPTY}
 * （不可变），{@code PropertyMap} 构造即封装为 guava {@code ImmutableMultimap}（put 抛
 * UnsupportedOperationException）——必须先用 guava 构建再以三参构造组装，否则「建立会话
 * 密钥失败（fail closed，拒登录）」。纹理属性随断言传 slave 供玩家列表皮肤。
 */
public final class LoginProfileBuilder {

    private LoginProfileBuilder() {
    }

    public static GameProfile build(UUID uuid, String name, Messages.ProfileProperty[] properties) {
        com.google.common.collect.ImmutableMultimap.Builder<String, Property> builder =
                com.google.common.collect.ImmutableMultimap.builder();
        for (Messages.ProfileProperty property : properties) {
            builder.put(property.name(),
                    new Property(property.name(), property.value(), property.signature()));
        }
        PropertyMap propertyMap = new PropertyMap(builder.build());
        return new GameProfile(uuid, name, propertyMap);
    }
}

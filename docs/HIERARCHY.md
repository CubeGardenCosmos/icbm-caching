# Master / Slave Velocity 拓扑与认证边界

状态：设计基线  
目标：多地区 slave 节点异步获得 master 缓存数据，并只信任 master 的正版身份断言。

## 1. 角色

    源 Fabric 后端 -> master Velocity / master 缓存目录 -> 多个地区 slave Velocity
                                                           -> 各自虚拟后端会话
    玩家 -> 独立 Auth Gateway -> master Velocity -> 目标 slave

- master 背后连接真实 Fabric 后端，是唯一的快照聚合、revision 目录和身份断言信任根。
- slave 是独立、异步的地区节点。它的插件管理本地缓存、虚拟后端和路由，但不成为源世界的权威。
- 源 Fabric 服务器仍是唯一能提交世界写入和处理冲突的节点。
- Auth Gateway 是唯一执行 Mojang 正版验证的协议终止器；master 收到其结果后签发供 slave 使用的身份断言。

master/slave 之间的快照复制、订阅、身份票据、路由查询和操作请求均为异步控制平面，不绑定 Minecraft tick。唯一绑定源服 tick 的环节是源 Fabric 模组把已到达的世界操作排入主线程并作原子裁决。

## 2. 正版验证的唯一信任链

master 与 slave 的 Velocity 进程均不执行 Mojang 正版验证逻辑。slave 只验证 master 签发的 MasterVerifiedIdentity；master 仅接受 Auth Gateway 的受保护验证结果，再将其签发为面向 slave 的身份断言。MasterVerifiedIdentity 至少包含：

    issuer = master identity
    keyId
    player UUID、名字、profile properties
    verifiedAt
    expiresAt
    sessionId
    nonce
    audience = 目标 slave 或 slave group
    client protocol
    permission/profile revision
    signature

要求：

1. master 用离线保护的私钥签名；slave 仅保留可轮换公钥。
2. 票据过期时间短，并且 nonce/sessionId 在 master 中只能兑换一次。
3. slave 验证 issuer、keyId、签名、受众、有效期、协议、吊销状态和单次兑换。
4. master 到 slave 的控制连接使用 mTLS；网络 ACL 仅允许 master 访问 slave 的受信端口。
5. slave 不接受来自另一个 slave 的身份断言或快照签名。

仅将 slave 设为 offline-mode 而不做上述措施会让任何直连客户端伪造 UUID、名字和 profile，属于不可接受的身份绕过。

## 3. 玩家入口模式

### 3.1 推荐：客户端先进入独立 Auth Gateway

    玩家 -> Auth Gateway -> 内网 master -> 内网 slave -> 虚拟后端

Auth Gateway 完成一次正版验证，并把受保护的验证结果交给 master。master 再通过受保护的内部链路将签名身份断言交给目标 slave。slave 不再次向 Mojang 验证。

此模式对原版客户端最可行，因为原版登录流程没有可供用户预先携带 master 票据的通用字段。Auth Gateway 必须实现 Minecraft 登录协议的正版验证部分，因此它是单独的网络组件，而不是普通 Velocity 插件。

### 3.2 直接连接地区 slave

若玩家直接连接 slave，slave 在接受游戏会话前必须获得并兑换 master 身份票据。对于原版客户端，这需要一个独立认证网关终止 Minecraft 登录协议，或使用客户端模组在登录扩展通道中传递票据。

如果要求“Velocity 不处理正版验证”，则正版验证功能不能消失，必须移至 master 前的专用认证网关。该网关本质上是协议终止器；不能用一个普通 Velocity 插件在 offline-mode 上安全替代。

## 4. Velocity 转发兼容性

Velocity 官方的 modern forwarding 可向兼容的下游后端安全传递身份信息并使用 secret。它不应被假定为“任意层级 Velocity 都能自动接收身份断言”的通用多代理方案。

在采用 master -> slave 的嵌套连接前，必须建立 26.3 与目标 Velocity 版本的集成测试，验证：

- slave 是否能作为 master 的下游并正确接收 master 身份；
- direct client 无法绕过该转发路径；
- profile properties、UUID、真实地址策略和权限不会被篡改；
- 断线、重连、切服和 master 故障会废弃旧票据。

若标准 forwarding 不能满足该测试，使用显式 master 身份票据与受控登录网关，或将虚拟后端作为 slave 的普通后端服务；不要依赖 Velocity 未公开的内部连接实现。

## 5. 缓存复制与操作路径

    源 Fabric -> master: INVALIDATE、PUT_SNAPSHOT、操作终态
    master -> slave: 已提交快照、requiredRevision、身份断言、公钥与吊销流
    slave -> master -> 源 Fabric: SUBMIT_OPERATION
    源 Fabric -> master -> slave: OPERATION_COMMITTED 或 OPERATION_REJECTED

slave 可异步预取和缓存 master 已 committed 的对象，但只要 master 宣布更高 requiredRevision，slave 就停止将旧对象作为新会话基线。slave 落后、断线或无法校验签名时必须 fail closed。

“先到者”仍然只在源 Fabric 主线程定义。多个 slave 同时提交同一操作时，master 只能传递和去重，不能在自身时间戳上先行决定胜者。

## 6. 端口与运行隔离

- Auth Gateway 到 master 的身份通道：仅 gateway 身份可访问，使用独立 mTLS 身份和签名密钥。
- master 控制 API：仅源 Fabric 和 mTLS 注册的 slave 可访问。
- slave 控制 API：仅 master 可访问。
- slave 的游戏监听端口：推荐仅接受 master 内网转发；若公开，则必须有可验证的 master ticket 入口。
- 源 Fabric 游戏端口：只接受 master，不接受 slave 或公网客户端。
- master/slave 的私钥、master 票据签名密钥和缓存对象签名密钥应分离，并支持 keyId 轮换。
- master/slave 若使用 online-mode=false，监听端口必须与上述入口策略匹配，不能把无票据 offline 登录暴露到公网。

## 7. 需要验证的安全性质

- [ ] 直接连接 slave 的 offline-mode 客户端无法获得任意玩家身份。
- [ ] 身份票据不能重放、跨地区转用或在过期后使用。
- [ ] slave A 不能伪造 master 对 slave B 的身份或缓存更新。
- [ ] master 断线或撤销身份后，现有 slave 会话按策略失效。
- [ ] 任意缓存或身份通道故障时，不会错误放行未验证玩家或过期区块。

## 8. 参考

- Velocity player information forwarding  
  https://docs.papermc.io/velocity/player-information-forwarding/
- Velocity security  
  https://docs.papermc.io/velocity/security/
- Velocity plugin messaging  
  https://docs.papermc.io/velocity/dev/plugin-messaging/

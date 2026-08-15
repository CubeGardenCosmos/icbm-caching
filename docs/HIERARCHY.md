# Master / Slave 拓扑与认证边界

状态：设计基线（阶段1 架构修正：master 统筹中心 + 单端口封装通道 + backend/slave 双模组）
目标：玩家只能连接 slave；master 是唯一统筹/决策/信任根（玩家不能直连）；源服是唯一权威并经
      velocity 授信模式接入；slave↔master 使用单端口封装通道（ChaCha20-Poly1305 + 公共密码 + UUID）。

## 1. 角色与拓扑

    玩家客户端
      │  （唯一入口：Minecraft 登录协议，slave 终止握手）
      ▼
    slave（Fabric 模组后端：原生 MC 服务器进程，缓存会话 + 有限预测）
      │  ▲ 封装通道（master 单端口）：magic + 握手（公共密码 + 每 slave UUID）+ ChaCha20-Poly1305
      │  │ 承载：登录验证材料 / 身份断言 / 落点与切换指令 / 快照与失效 / 操作提交 /
      │  │       （权威会话时）明文 Minecraft 报文转发
      ▼  │
    master（统筹中心：Auth Gateway + 全局会话记账 + 落点判定/切换 + 快照聚合；control-service 并入；
           玩家不能直连；对畸形/原版握手 RST）
      │  velocity 授信模式（forwarding secret，内网；master 主动连接源服）
      ▼
    源服（backend Fabric 模组：唯一权威世界；产生消息事件；数据包可被缓存）

- 玩家只能先连接 slave；master 不面向玩家（不作为可直连的 Velocity/游戏服务器）。
- slave↔master 之间不是 Minecraft 原版协议，而是具备封装的专用通道；该端口不再作为 Minecraft
  协议工作（原版/畸形握手立即 RST）。
- 源服务器工作在内网，使用 velocity 授信模式（与 proxy→backend 同构），在 velocity.toml 中标注
  服务器名；服务器名是区分玩家所在世界/维度并分缓存的重要依据。

## 2. 正版验证与全局会话记账（唯一信任链）

Auth Gateway 位于 master（master 的受信组件），是唯一执行 Mojang 正版验证的组件。slave
终止登录握手后把验证材料（server hash、shared secret、Profile、slaveId、nonce 等）经封装通道
递交给 master；master：

1. 调用 Mojang session server 完成验证（开发/测试环境 verify=false 开关直接放行但仍记账）；
2. 在**全局活跃会话表**记账：同一玩家 UUID 同时只允许一个活跃会话（防玩家加入多个 slave）；
   已存在则拒绝新验证请求（或按策略踢旧会话）；
3. 验证通过后签发面向后端的身份断言（MasterVerifiedIdentity，含 audience/short 过期/吊销）。

要求：

1. master 用离线保护的私钥签名；slave 仅保留可轮换公钥。
2. 票据过期时间短，并且 nonce/sessionId 在 master 中只能兑换一次。
3. slave 验证 issuer、keyId、签名、受众、有效期、协议、吊销状态和单次兑换。
4. slave 不接受来自另一个 slave 的身份断言或快照签名。
5. slave 必须在玩家登出/断开时上报会话关闭，master 注销记账（防会话表泄漏）。
6. 竞态窗口（验证通过→玩家真正进入后端之间）用两阶段确认或持有锁，避免双通过。

仅将 slave 设为 offline-mode 而不做上述措施会让任何直连客户端伪造 UUID、名字和 profile，
属于不可接受的身份绕过。

## 3. 玩家入口模式

### 3.1 玩家只能连接 slave

    玩家 -> slave（终止登录）-> 封装通道递交验证材料 -> master（验证 + 记账 + 落点判定）

- slave 终止登录协议（RSA 密钥在 slave），验证请求经受信封装通道交给 master（唯一验证者）。
- 缓存会话：数据面在 slave（本地 committed snapshot 提供区块），玩家流量不经过 master。
- 权威会话（玩家需要源服实时权威数据）：slave 把明文 Minecraft 报文经封装通道交给 master，
  master 以 velocity 授信模式转发到源服；源服返回的数据包在 master 侧缓存（形成快照）。
- 玩家试图加入第二个 slave 时，master 记账拒绝（或按策略踢旧会话）。

### 3.2 直连说明

master 没有任何面向玩家的端口；未经 slave 接入的客户端无法到达 master。

## 4. Velocity 转发兼容性

master 使用 velocity 授信模式（modern forwarding secret）与源服通信；源服是唯一 backend。
需建立 26.3 与目标 Velocity 版本的集成测试，验证：

- 玩家会话经 slave↔master 封装通道到源服的转发链路正确（身份断言随会话传递）；
- direct client 无法绕过 master 的验证与记账；
- profile properties、UUID、真实地址策略和权限不会被篡改；
- 断线、重连、切服和 master 故障会废弃旧票据并正确注销会话记账。

## 5. 缓存复制与操作路径

    源服 -> master: 数据包缓存 + INVALIDATE、PUT_SNAPSHOT、操作终态（velocity 授信通道）
    master -> slave: 已提交快照、requiredRevision、身份断言、公钥与吊销流、落点/切换指令（封装通道）
    slave -> master -> 源服: SUBMIT_OPERATION（封装通道 + 授信通道）
    源服 -> master -> slave: OPERATION_COMMITTED 或 OPERATION_REJECTED

slave 可异步预取和缓存 master 已 committed 的对象，但只要 master 宣布更高 requiredRevision，
slave 就停止将旧对象作为新会话基线。slave 落后、断线或无法校验签名时必须 fail closed。

“先到者”仍然只在源 Fabric 主线程定义。多个 slave 同时提交同一操作时，master 只能传递和去重，
不能在自身时间戳上先行决定胜者。

## 6. 端口与运行隔离

- master：对外只监听**一个端口**（slave 封装通道；可被简单转发）；magic 匹配进入握手，
  畸形或原版握手立即 RST；不再监听 mTLS 控制端口（control-service 并入 master 进程）。
- 源服：内网；只接受 master 的连接（velocity 授信模式）；不暴露给 slave 或公网。
- slave：面向玩家监听游戏端口；封装通道作为客户端连 master 单端口。
- 鉴权：每 slave = 共享公共密码 + 每 slave UUID（明文、可打印日志）；UUID 是身份标签，
  PSK 内网信任边界（运维手册禁止弱公共密码）；不使用 TLS。
- master/slave 的私钥、master 票据签名密钥和缓存对象签名密钥应分离，并支持 keyId 轮换。

## 7. 安全性质（已验证，阶段 1/3 验收 + 端到端复验）

- 玩家只能经 slave 进入；直接连接 master 的任何流量被 RST（封装通道 magic 判别，畸形/原版
  握手 RST，SO_LINGER=0）。
- 不知道公共密码的连接无法完成封装握手；AUTH 失败即断连（EncapsulatedControlServerTest 锁定）。
- 同一玩家无法同时在多个 slave 建立会话（SessionRegistry 全局记账：重复会话拒新/踢旧/断开注销）。
- 身份票据不能重放、跨地区转用或在过期后使用（TicketVault 单次兑换 + nonce 重放拒 + 吊销）。
- slave A 不能伪造 master 对 slave B 的身份或缓存更新（封装通道会话密钥按 UUID+连接 nonce
  派生，PeerRegistry 角色绑定）。
- master 断线或撤销身份后，现有 slave 会话按策略失效并注销记账（SESSION_CLOSED + 连接断开
  批量注销，防会话表泄漏）。
- 任意缓存或身份通道故障时，不会错误放行未验证玩家或过期区块（ServeGate/SnapshotChunkLoader
  fail closed：未命中/落后/epoch 不匹配/master 断开均拒绝服务）。
- 源服数据包缓存不会把未验证/过期数据作为基线（对象库 committed+epoch 单调守卫，PacketLog
  仅审计不参与基线判定）。

## 8. 参考

- Velocity player information forwarding
  https://docs.papermc.io/velocity/player-information-forwarding/
- Velocity security
  https://docs.papermc.io/velocity/security/
- Velocity plugin messaging
  https://docs.papermc.io/velocity/dev/plugin-messaging/
- JDK ChaCha20-Poly1305：`javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")`

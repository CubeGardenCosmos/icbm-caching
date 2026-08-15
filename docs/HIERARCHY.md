# Master / Slave 拓扑与认证边界

状态：设计基线（阶段1 架构修正：master 统筹中心 + slave Fabric 模组化 + Auth Gateway 归位）
目标：master 是唯一的统筹/决策/信任根；slave 是面向玩家的 Fabric 模组后端；
      源服是唯一权威；Auth Gateway 归 master 并承担全局会话记账。

## 1. 角色与拓扑

    玩家客户端
      │  （Minecraft 登录协议，slave 终止握手）
      ▼
    slave（Fabric 模组后端：原生 MC 服务器进程，面向玩家，地区/边缘节点）
      │  ▲ 登录验证材料（slave → master）／身份断言（master → slave）
      │  │ 落点判定 / 多服务器切换 / 健康检查 / 快照复制 / 操作提交
      ▼  │
    master（Velocity 统筹中心 + Auth Gateway + 会话记账 + 快照聚合）
      │  ▲ 快照 / 失效 / 操作终态
      ▼  │
    源服 Fabric（唯一权威世界服务器）

- 玩家经 slave 接入；slave 终止 Minecraft 登录握手，把验证材料递交给 master 的 Auth Gateway
  验证（slave 不做 Mojang 正版验证）。
- master 是统筹中心：通过白名单/fallback 判定玩家落点（源服或某个 slave），实现多服务器切换；
  不面向玩家直接提供游戏会话（master 不作为可直连的普通代理部署）。
- 源服仍是唯一能提交世界写入和处理冲突的节点；slave 从 committed snapshot 提供缓存世界会话，
  不做世界计算（见 ARCHITECTURE.md §3）。

## 2. 正版验证与全局会话记账（唯一信任链）

Auth Gateway 位于 master（master 的受信组件），是唯一执行 Mojang 正版验证的组件。slave
终止登录握手后把验证材料（server hash、shared secret、Profile、slaveId、nonce 等）经受信
控制通道递交给 master；master：

1. 调用 Mojang session server 完成验证；
2. 在**全局活跃会话表**记账：同一玩家 UUID 同时只允许一个活跃会话（防玩家加入多个 slave）；
   已存在则拒绝新验证请求（或按策略踢旧会话）；
3. 验证通过后签发面向后端的身份断言（MasterVerifiedIdentity，含 audience/short 过期/吊销）。

要求：

1. master 用离线保护的私钥签名；slave 仅保留可轮换公钥。
2. 票据过期时间短，并且 nonce/sessionId 在 master 中只能兑换一次。
3. slave 验证 issuer、keyId、签名、受众、有效期、协议、吊销状态和单次兑换。
4. master 到 slave 的控制连接使用 mTLS；网络 ACL 仅允许 master 访问 slave 的受信端口。
5. slave 不接受来自另一个 slave 的身份断言或快照签名。
6. slave 必须在玩家登出/断开时上报会话关闭，master 注销记账（防会话表泄漏）。
7. 竞态窗口（验证通过→玩家真正进入后端之间）用两阶段确认或持有锁，避免双通过。

仅将 slave 设为 offline-mode 而不做上述措施会让任何直连客户端伪造 UUID、名字和 profile，
属于不可接受的身份绕过。

## 3. 玩家入口模式

### 3.1 推荐：玩家经 slave 接入，master 验证并判定落点

    玩家 -> slave（终止登录） -> master Auth Gateway（验证 + 记账 + 断言） -> 落点后端
           （slave 放行进入缓存会话；或 master 判定后引导切换到目标后端）

- slave 终止登录协议（RSA 密钥在 slave），但验证请求经受信通道交给 master（唯一验证者）。
- 玩家数据平面不经过 master；master 只做验证、记账、落点判定与切换（控制/管理平面）。
- 玩家试图加入第二个 slave 时，master 记账拒绝（或按策略踢旧会话）。

### 3.2 直连地区 slave

若玩家直接连接 slave（不经 master 前置），slave 仍必须把验证材料递交给 master 的 Auth
Gateway；未经 master 验证的客户端不能进入游戏会话。

## 4. Velocity 转发兼容性

master 作为统筹中心在内部使用 Velocity 的落点/切换机制（fallback、/server、transferToServer）
时，需建立 26.3 与目标 Velocity 版本的集成测试，验证：

- 玩家会话能在源服与各 slave 后端之间切换且身份断言随会话传递；
- direct client 无法绕过 master 的验证与记账；
- profile properties、UUID、真实地址策略和权限不会被篡改；
- 断线、重连、切服和 master 故障会废弃旧票据并正确注销会话记账。

## 5. 缓存复制与操作路径

    源服 -> master: INVALIDATE、PUT_SNAPSHOT、操作终态、验证无关
    master -> slave: 已提交快照、requiredRevision、身份断言、公钥与吊销流、落点/切换指令
    slave -> master -> 源服: SUBMIT_OPERATION
    源服 -> master -> slave: OPERATION_COMMITTED 或 OPERATION_REJECTED

slave 可异步预取和缓存 master 已 committed 的对象，但只要 master 宣布更高 requiredRevision，
slave 就停止将旧对象作为新会话基线。slave 落后、断线或无法校验签名时必须 fail closed。

“先到者”仍然只在源 Fabric 主线程定义。多个 slave 同时提交同一操作时，master 只能传递和去重，
不能在自身时间戳上先行决定胜者。

## 6. 端口与运行隔离

- Auth Gateway 验证接口：仅在 master 受信面（slave 经 mTLS 控制通道递交验证材料），不对公网暴露。
- master 控制 API：仅源 Fabric、Auth Gateway 内部与 mTLS 注册的 slave 可访问。
- slave 控制 API：仅 master 可访问。
- slave 的游戏监听端口：面向玩家；未经验证的客户端在登录阶段即被拒绝。
- 源 Fabric 游戏端口：只接受 master/受信节点，不接受 slave 或公网客户端。
- master/slave 的私钥、master 票据签名密钥和缓存对象签名密钥应分离，并支持 keyId 轮换。
- 控制平面端口规划：当前为独立 mTLS 受信端口（隔离清晰、审计简单）；“打包进 25565 游戏端口”
  为备选方向，因安全隔离/流量干扰/审计因素尚未定案（见 CACHE_PROTOCOL.md §1 端口待定项）。

## 7. 需要验证的安全性质

- [ ] 直接连接 slave 的 offline-mode 客户端无法获得任意玩家身份（必须经 master 验证）。
- [ ] 同一玩家无法同时在多个 slave 建立会话（全局会话记账）。
- [ ] 身份票据不能重放、跨地区转用或在过期后使用。
- [ ] slave A 不能伪造 master 对 slave B 的身份或缓存更新。
- [ ] master 断线或撤销身份后，现有 slave 会话按策略失效并注销记账。
- [ ] 任意缓存或身份通道故障时，不会错误放行未验证玩家或过期区块。
- [ ] slave 会话关闭后，全局会话表正确注销（无泄漏）。

## 8. 参考

- Velocity player information forwarding
  https://docs.papermc.io/velocity/player-information-forwarding/
- Velocity security
  https://docs.papermc.io/velocity/security/
- Velocity plugin messaging
  https://docs.papermc.io/velocity/dev/plugin-messaging/

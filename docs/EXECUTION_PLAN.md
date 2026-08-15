# 执行计划：阶段1 架构修正落地（master 统筹 + 单端口封装通道 + backend/slave 双模组）

依据：docs/HIERARCHY.md、docs/ARCHITECTURE.md、docs/PHASES.md、docs/CACHE_PROTOCOL.md。
原则：每个里程碑原子提交（VERSION 递增）；新功能先走 `build/workspace/draft/<功能名>` 三步草稿
（AGENTS.md 硬约束）；协议变更必须“文档 + 两侧实现 + golden 测试”同一提交。

## 里程碑 0：决策定案（已完成）

- [x] 玩家只能连 slave；master 不面向玩家（任意直连流量 RST）。
- [x] slave↔master：单端口封装通道（magic → 握手[公共密码 + 每 slave UUID] → ChaCha20-Poly1305；
      不使用 TLS；畸形/原版握手 RST；可被简单转发）。
- [x] PSK 内网信任边界接受（运维手册禁止弱公共密码；UUID 明文仅身份标签/审计）。
- [x] control-service 并入 master（对象库/事件日志/订阅成为 master 内部模块，不再独立监听）。
- [x] 源服：唯一权威；内网 + velocity 授信模式（forwarding secret，master 主动连接）；
      velocity.toml 标注服务器名（serverName，缓存隔离键）；数据包可被缓存。
- [x] Fabric 模组两个：`fabric-source/`（backend：源服 + 消息事件）、`slave-mod/`（slave 后端）。
- [x] virtual-backend 保留为只读变体参考；核心代码迁移入 slave-mod。

## 里程碑 1：传输层与协议重写（protocol + master 封装服务）

- [x] 草稿 `draft/encapsulated-channel/`：参考仓库做法（Velocity 的 Connection/帧/握手）、官方做法
      （minecraft-assets 的帧与包层）、差异取舍（magic/RST/PSK 鉴权/ChaCha20-Poly1305）。
- [x] 封装层（protocol 模块新增，两侧复用）：
      - 帧：magic + 长度 + 载荷；连接首字节非 magic → master RST（SO_LINGER=0）；
      - 握手：AUTH（公共密码证明 + UUID 明文 + nonce/challenge）→ 会话密钥派生（含 UUID，
        HKDF-SHA256）→ ChaCha20-Poly1305（JDK `Cipher`，无外部依赖）；
      - 多路复用：消息类型 + 流 ID（控制流 / 验证流 / 明文 MC 报文流）；
      - golden 序列化测试锁定线上布局（AUTH token RFC 5869/HMAC 向量、帧布局、篡改检测、RST）。
- [x] 角色重定义：SOURCE / SLAVE（迁移 VIRTUAL_BACKEND/VELOCITY）；HELLO 增加 serverName。
- [x] 新消息：LOGIN_VERIFY_REQ/RES、SESSION_CLOSED（CACHE_PROTOCOL §9）+ codec + golden 测试。
- [ ] 缓存键扩展：serverName 进入（对象库目录结构按 serverName 分域）。
- [ ] 验收：旧消息不回归（现有组件测试通过）；golden 字节锁定；畸形帧/非 magic 连接 RST 测试。

## 里程碑 2：control-service 并入 master

- [ ] control-service 从独立 netty 监听改为 master 内部模块（封装通道处理连接；对象库/
      事件日志/订阅逻辑不变）；删除独立 mTLS 监听与证书 CN 角色绑定。
- [ ] PeerRegistry 改为按 UUID+公共密码鉴权结果注册（role 由注册表决定：SLAVE / SOURCE）。
- [ ] 验收：既有对象库/重放/订阅测试迁移后全绿；无独立监听端口。

## 里程碑 3：backend 模组（fabric-source 演化）

- [ ] 连接层：mTLS ControlChannel → velocity 授信模式通道（master 主动连接源服内网端口，
      forwarding secret 鉴权；无玩家在线也可用——独立受信长连接，不依赖玩家会话）。
- [ ] HELLO 携带 serverName；事件集合不变（WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/操作终态）。
- [ ] 权威会话数据包缓存：master 侧缓存源服返回的数据包（形成快照；只有 committed+epoch
      匹配才可作基线，fail closed）。
- [ ] 草稿 `draft/backend-velocity-trust/` 三步流程。
- [ ] 验收：源服→master 快照链路回归；serverName 隔离；无玩家时事件可达。

## 里程碑 4：slave Fabric 模组（新组件 slave-mod/，最大块）

- [ ] 草稿 `draft/slave-fabric-backend/` 三步流程（参考：fabric/velocity；官方：minecraft-assets
      的 ChunkMap/ChunkSerializer/ServerGamePacketListenerImpl；差异：不做世界计算）。
- [ ] 区块来源接管：快照 NBT → LevelChunk（26.3 `SerializableChunkData` 解析注入）；
      生成器禁用/替换；未命中/落后/epoch 不匹配 fail closed（不本地生成“幽灵区块”）。
- [ ] 事件接管：ServerPlayNetworkHandler 钩子拦截互动 → 拒绝（旁观）/预测占位；禁持久化
      （独立世界目录、禁 save、启动自检）。
- [ ] 封装通道客户端：公共密码 + UUID 握手 → ChaCha20-Poly1305；订阅快照/失效；登录验证
      材料递交（LOGIN_VERIFY_REQ，master 返回前不放行）。
- [ ] 256M 约束：磁盘对象库（SnapshotObjectStore）、按需装载、注入即释放、堆占用启动自检
      （复用 fabric-source 的 MixinTargetVerifier 自检模式）。
- [ ] 迁移 virtual-backend 的 Nbt / 对象库 / ServeGate / 复制逻辑（ChunkDataEncoder 等
      vanilla 钩子路线不再需要，区块由 vanilla 网络层编码）。
- [ ] 验收：玩家经 slave 旁观（缓存世界）；移动不触发源服加载；未命中不展示旧区块；
      256M 堆稳定；重复会话被拒。

## 里程碑 5：master 统筹与权威会话桥

- [ ] 落点判定（白名单/fallback）与多服务器切换；健康检查与断路器。
- [ ] 全局会话记账：登记/注销/竞态两阶段确认/重复会话策略（拒新 or 踢旧）。
- [ ] Auth Gateway 验证流程（LOGIN_VERIFY）与身份断言签发/单次兑换/吊销。
- [ ] 权威会话桥：slave 明文 MC 报文 → 封装通道 → master → 源服（velocity 授信）；
      源服返回数据包在 master 侧缓存。
- [ ] 验收：玩家可在缓存会话与权威会话之间切换；票据不重放；后端故障 fallback。

## 里程碑 6：源服复验 + 端到端（原阶段2）

- [ ] 新架构下源服 Mixin 目标、revision 持久化、快照发布复验（无回归）。
- [ ] 全链路：源服 setblock → 快照 → master → slave 订阅 → 玩家经 slave 看到；
      未命中/epoch/fingerprint 不匹配不展示旧区块；重复会话拒绝。

## 里程碑 7：操作与故障（原阶段5-7，顺序不变）

- 单格乐观操作 → 扩大操作类型 → 故障/回归/性能（256M 堆压测）。

## 依赖关系

```
0 定案（完成）→ 1 传输层 → 2 并入 master ─┬→ 3 backend
                                        ├→ 4 slave-mod（草稿先行）
                                        └→ 5 master 统筹/权威桥（依赖 1、2）
→ 6 复验/端到端 → 7 操作/故障
```

## 风险与注意

- 封装通道安全性 = 公共密码强度（PSK 边界）；运维手册与部署配置必须强制强密码与安全分发。
- ChaCha20-Poly1305 会话密钥派生建议包含 UUID 与连接 nonce（防重放、弱化 slave 间互读）。
- 权威会话桥涉及“slave 终止登录 + 明文转发”双重角色，是阶段 5 最大的协议复杂度点；
  里程碑 4 先保证缓存会话闭环，再开权威桥。
- 256M 约束是全链路硬约束：任何“整块驻留”路径（快照、编码、封装缓冲）都要按需流式。

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
- [x] 缓存键扩展：serverName 进入（对象库目录结构按 serverName 分域
      `<root>/<serverName>/<namespace>/<path>/...`，隔离测试锁定）。
- [x] master 封装服务（control-service）：单端口封装通道监听（首字节 magic 判别 → 非 magic
      RST SO_LINGER=0 → AUTH 握手 → HKDF 派生 → ChaCha20-Poly1305 多路复用），控制帧复用
      ControlSessionLogic（与 mTLS 会话同一套 HELLO/序号/权限/分发逻辑）；LOGIN_VERIFY/
      MC_PASSTHROUGH 流留回调（阶段3/5）。
- [x] 验收：旧消息不回归（现有组件测试通过）；golden 字节锁定（AUTH/帧/HKDF 向量）；
      畸形帧/非 magic 连接 RST 测试（协议层 + 服务器层 EncapsulatedControlServerTest）。

## 里程碑 2：control-service 并入 master（已完成）

- [x] control-service 并入 master（velocity-plugin 模块，包 `cn.cubegarden.icbm.caching.control`
      不变）；对象库/事件日志/订阅逻辑不变；独立进程形态保留 `ControlServiceMain`（Velocity
      插件入口于阶段5 接线）。
- [x] 删除独立 mTLS 监听与证书 CN 角色绑定：ControlServer/PeerSession/MessageFramer/
      TlsContextFactory 移除；ServiceConfig 去掉 TLS 字段；`build/main.py certs` 删除。
- [x] 封装通道客户端（protocol `EncapsulatedChannelClient`）：AUTH（公共密码证明 + UUID）→
      HKDF → ChaCha20-Poly1305 → HELLO/序号/重连；两侧复用（virtual-backend 连接层已迁移，
      fabric-source 随里程碑 3 迁移）。
- [x] PeerRegistry 按 UUID+公共密码鉴权结果注册（roleBindings: AUTH UUID → SLAVE/SOURCE）。
- [x] 验收：既有对象库/重放/订阅测试迁移到封装通道后全绿（ControlServiceIntegrationTest）；
      无独立监听端口（只剩封装通道 11420；畸形/原版握手 RST）。

## 里程碑 3：backend 模组（fabric-source 演化，已完成）

- [x] 草稿 `draft/backend-velocity-trust/` 三步流程：参考仓库（velocity modern forwarding：
      HMAC-SHA256(secret, data) 签名前置）、官方（minecraft-assets ServerConnectionListener /
      LoginPluginMessage 往返）、差异取舍（不伪装玩家；复用封装通道传输层，PSK=forwarding
      secret；HELLO 由源服发出，复用 HELLO/HELLO_ACK 不新增消息类型）。
- [x] 连接层：mTLS ControlChannel → **velocity 授信模式通道**（master 主动连接源服 backend
      端口；AUTH 公共密码证明 PSK=forwarding secret；无玩家在线也可用——独立受信长连接）。
      源服侧：BackendControlServer/BackendControlSession（fabric-source）；master 侧：
      SourceConnector（ControlSession 适配 + 注册源指纹 + PUT_SNAPSHOT_ACK）。
- [x] HELLO 携带 serverName；事件集合不变（WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/操作终态）。
- [x] 权威会话数据包缓存基线规则：committed+epoch 匹配才可作基线（对象库既有单调守卫）；
      源服返回数据包缓存实现随里程碑 5 权威桥。
- [x] 验收：源服→master 快照链路回归（SourceConnectorTest：事件入对象库 required/committed/
      epoch + serverName 分域 + slave 订阅收到 backend 推送 + 错误 secret fail closed）；
      RemoteSnapshotSinkTest 迁移（假 master 主动连接）；无玩家时事件可达（master 主动连接）。

## 里程碑 4：slave Fabric 模组（新组件 slave-mod/，已完成）

- [x] 草稿 `draft/slave-fabric-backend/` 三步流程（参考 fabric-source/velocity/virtual-backend；
      官方 ChunkMap.scheduleChunkLoad/SerializableChunkData/ChunkPyramid/ServerLoginPacketListenerImpl/…；
      差异：不 worldgen、不 tick 模拟、不持久化写回、fail closed）。
- [x] 区块来源接管：Mixin ChunkMap.scheduleChunkLoad——对象库命中（master 连接 + committed≥required
      + epoch 匹配）→ SerializableChunkData.parse/read 注入 FULL 快照（生成管道 canLoadWithoutGeneration
      跳过生成）；未命中 → EMPTY（不展示旧区块）。Mixin ChunkMap.applyStep 跳过全部非 FULL 生成步骤。
- [x] 事件接管：PlayerBounds（未命中区块拒绝移动，撞墙语义）；SpectateGate（互动四件套拒绝，旁观）；
      SaveGate 禁持久化（独立世界目录 + ChunkMap 保存拦截，缓存是唯一持久层）。
- [x] 封装通道客户端（SlaveControlClient，ReplicationClient 迁移）：SLAVE HELLO（serverName+指纹）→
      SUBSCRIBE → 事件应用（含 WORLD_EPOCH spawn）→ ACK；断线重连重订阅；LOGIN_VERIFY_REQ 递交
      （LoginVerifyGateMixin 接管 handleKey，master 返回前不放行）。
- [x] 256M 约束：磁盘对象库（SnapshotObjectStore）、注入即释放、MixinTargetVerifier 自检（含堆告警）。
- [x] 协议变更（同一提交）：WORLD_EPOCH 携带 spawn（源服 RespawnData；事件日志/两侧 codec/golden 同步）。
- [x] master 最小 Auth Gateway：SessionRegistry（登记/注销/重复会话拒新）+ AuthGatewayListener
      （LOGIN_VERIFY 流服务，offline 放行仍记账）；SESSION_CLOSED 注销落地。
- [x] 验收：SlaveControlClientTest（假 master：HELLO/SUBSCRIBE/事件入对象库/spawn/ACK/错 PSK 拒）；
      AuthGatewayTest（LOGIN_VERIFY 放行 + 重复会话拒新 + SESSION_CLOSED 注销）；全组件测试绿。
      （真实玩家经 slave 旁观由里程碑 6 端到端验证：测试服务器装配 slave 世界 + 玩家会话。）

## 里程碑 5：master 统筹与权威会话桥（已完成）

- [x] 草稿 `draft/master-orchestration-bridge/` 三步流程（参考：velocity ConnectionManager/
      ServerChannelInitializer/LoginSessionHandler；官方：ServerLoginPacketListenerImpl.handleKey
      26.3 hasJoined 语义；差异：权威桥 v1 = MC_PASSTHROUGH 字节透传闭环，虚拟玩家连接留
      阶段 6/7；数据包缓存 = PacketLog 审计日志 + 源服快照管道进对象库）。
- [x] 落点判定（RoutingPolicy 白名单/fallback）+ 健康检查（BackendHealth：连接 + 活动时间戳）
      + 断路器（CircuitBreaker：连续失败 OPEN → 冷却半开 → 成功恢复）。
- [x] 全局会话记账：SessionRegistry 登记/注销/拒新 + 管理踢旧（kickOld）+ slave 连接断开
      批量注销（SESSION_CLOSED 未送达防泄漏）；验证登记与落点判定原子完成（两阶段确认）。
- [x] Auth Gateway 完整化：Mojang 正版验证（26.3 hasJoined 复刻 + offline 开关 + 服务不可用
      fail closed）+ 断言 HMAC 签名（keyId 轮换）+ 票据单次兑换（nonce 重放拒）+ 吊销
      （SESSION_CLOSED/断开）；MasterVerifiedIdentity 增加 profileProperties（协议变更，
      golden 同步）。
- [x] 权威会话桥（v1）：slave↔master↔源服 双向 MC_PASSTHROUGH 字节透传（Master 侧
      McPassthroughBridge + PacketLog；fabric-source BackendControlSession 流支持）；源服
      故障/断路器 OPEN → 拒绝转发（后端故障 fallback 缓存会话）。
- [x] 验收：RoutingPolicyTest/MojangSessionVerifierTest（本地假 session server）/
      IdentitySignerTest/TicketVaultTest/AuthGatewayTest（完整流程 + 重放拒 + 断开注销）/
      McPassthroughBridgeTest（双向转发 + PacketLog + fallback）；全组件测试绿。
      （Velocity 插件入口延后：velocity-api 离线不可用，插件形态需真实 Velocity 26.3 环境，
      阶段 6/7 端到端装配时接线——独立进程形态 ControlServiceMain 完整承载 master 职责。）

## 里程碑 6：源服复验 + 端到端（原阶段2）（已完成）

- [x] 草稿 `draft/end-to-end-verification/` 三步流程（官方：MinecraftServer.java:482 出生点
      区块装载 / SetBlockCommand / RCON；差异：端到端 = 真实三进程装配 + RCON 玩家视角代理；
      快照到达后重载已装载区块不做（阶段 7 故障场景））。
- [x] 复验无回归：全组件测试绿；MixinTargetVerifier 参数化测试（目标存在/缺失两类 fail closed）；
      revision 持久化 round-trip/重启不倒退/脏合并测试既有覆盖。
- [x] 全链路端到端：`build main.py e2e` 编排源服（fabric-server-launch + icbm-source jar-in-jar）+
      master（ControlServiceMain 独立进程）+ slave（build/workspace/slave-server，256M 堆）；
      源服 forceload+setblock → 「已提交快照」→ master 对象库 → slave 订阅「已装载快照」→
      RCON `execute if block 0 64 0 minecraft:diamond_block` → Test passed（玩家视角命中）。
- [x] 修复（端到端暴露）：ControlServiceMain 主线程阻塞（daemon 线程 JVM 退出）；EventLog
      recover 尾部截断容错；源服 backend SO_TIMEOUT 握手后放宽（master 心跳 20s < 45s）；
      EncapsulatedChannelClient 加心跳（master→源服方向，原缺失导致 45s 断连循环）；
      jar-in-jar（include protocol 依赖）；slave mixins 包独立（mixin 子包）+ ChunkMapAccess
      @Shadow→cast 修复；slave 指纹计算时序（world LOAD 后）；指纹确定性（排除 icbm_* 框架
      模组 + registry 排序）；slave 初始同步闸门 + setChunkForced 强制装载出生点区块。
- [x] 验收：setblock → 玩家经 slave 看到（RCON Test passed）；master 停止 → slave「master
      断开（服务闸门关闭）」（fail closed）；重复会话拒绝由 AuthGatewayTest 既有覆盖。
      （真实客户端登录/旁观注入随阶段 7 端到端装配；本里程碑 RCON 为无头玩家视角代理。）

## 里程碑 7：操作与故障（原阶段5-7，顺序不变）（已完成）

- [x] 草稿 `draft/optimistic-operations/` 三步流程（官方：ServerPlayerGameMode.destroyBlock
      前置条件/ServerLevel.setBlock/ChunkMap.scheduleUnload 卸载路径/ServerChunkCache.getChunkNow
      主线程检查/ServerProperties.pause-when-empty-seconds 空服暂停；差异：v1 操作者 = 模拟玩家、
      master 纯转发不裁决、slave overlay = 内存可丢弃 Map、刷新 = 卸载重载 + 就地应用快照兜底）。
- [x] 阶段 5 单格乐观操作完整闭环：
  - 协议 5 消息（SUBMIT_OPERATION/COMMITTED/REJECTED/GET_OPERATION_STATUS/STATUS_RES，0x14-0x18）
    + codec + golden（同一提交）；
  - slave `OperationOverlay`（预测登记/查询/清除、单 pending 约束、discardAll fail closed）+
    `SlaveControlClient.submitOperation/getOperationStatus` + `/icbmslave operate` 调试命令；
  - 源服 `OperationQueue`（主线程 END_SERVER_TICK 幂等去重、有界终态历史、状态查询）+
    `OperationAdjudicator`（BREAK/PLACE 原子前置条件 + level.setBlock 权威执行 + canonical delta）；
  - master `ControlService` 操作路由（operationId → 发起 slave 有界映射；源服未连接 → fail closed
    拒绝 + resync 范围）；
  - 刷新机制：快照到达 → 已装载区块卸载重载（官方 scheduleUnload 路径）/ 超时就地应用快照
    （ProtoChunk 逐方块 setBlockState，flags=0）——不展示旧区块。
- [x] 验收：
  - OperationQueueTest（重复 opId 幂等不二次执行、终态历史可查询且稳定、竞争同方块只提交一个、
    PENDING/UNKNOWN、历史有界）；
  - OperationOverlayTest（预测/COMMITTED 清除无幽灵/REJECTED 清除+resync 范围/discardAll/
    单 pending/重复 opId 不双记）；
  - OperationRoutingTest（slave→master→TestBackendServer 提交/COMMITTED/REJECTED/STATUS 查询回发/
    源服掉线 fail closed + resync 范围）；
  - e2e：slave RCON `icbmslave operate 0 64 0 break` → 源服裁决 air → 快照管道 → slave 刷新 →
    `execute if block 0 64 0 air` Test passed（玩家经 slave 看到操作效果 = 源服权威结果）；fail closed 复验。
- [x] 修复（e2e 暴露）：master↔slave 心跳缺失（slave 未启用心跳 → master 10s 误断；SlaveConfig 加
  heartbeatIntervalMillis=8000 < master 读超时 10s）；快照刷新需 Server thread（getChunkNow 主线程
  检查）；操作裁决先于快照序列化（ticker/operationQueue 顺序）；空服暂停（pause-when-empty-seconds=0
  缓存服务器必须持续 tick）；卸载卡住（saveSync 依赖）→ 就地应用快照兜底。
- [ ] 阶段 6 扩大操作类型：USE_BLOCK/库存/桶/实体互动/红石/活塞/爆炸/模组互动全部保持拒绝
      （slave 对不支持类型直接拒绝，不发提交）；各类型前置条件/受影响状态/回滚计划延后（真实玩家
      会话接线，阶段 8）。
- [ ] 阶段 7 剩余：256M 堆压测、真实客户端故障模拟（网络分区/缓存损坏/回档）延后（阶段 8 端到端）。

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
- **第三方协议库评估（Protocolize，Exceptionflug/protocolize）：不引入。** MIT 许可且仍在
  维护，但 `ProtocolVersions.MINECRAFT_LATEST = 1_21_5`（协议 770），不支持 26.3
  （协议 1073742154）；包 ID/字段/data components/tracked data 布局不同，引入即违反
  VERSION_FREEZE「26.3 常量必须来自 minecraft-assets」硬约束。借鉴点：其 Velocity pipeline
  注入（捕获后端连接原始包字节）思路，里程碑 5 权威会话桥用 Velocity 原生 API 实现。

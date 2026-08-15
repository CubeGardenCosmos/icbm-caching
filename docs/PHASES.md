# 阶段性任务与验收条件

状态：阶段 8（游戏内功能完善：真实玩家操作 + 快照覆盖扩容）为当前规划；
      阶段 1-7 与修正后基线的成果作为已完成记录保留（下方压缩为交付摘要，不再逐条 checkbox）。
原则：先证明源服权威性和回滚正确性，再扩大可预测操作和优化性能；任何新功能先走
      `build/workspace/draft/<功能名>` 三步草稿（参考仓库 → 官方源码 → 差异取舍，AGENTS.md 硬约束）。

## 已完成记录（阶段 0-7，交付摘要）

- **原阶段 0 版本冻结与指纹**：26.3-snapshot-8（协议 1073742154、DataVersion 5010、Java 25、
  Fabric Loader 0.19.3、Fabric API 0.157.1+26.3）；modpackFingerprint 与 registryAndDatapackFingerprint
  计算与记录（docs/VERSION_FREEZE.md）。
- **原阶段 1 源服权威基线**：SourceState 持久化（worldEpoch / chunk revision）、markUnsaved 权威
  路径 Mixin（LevelChunk/光照/方块实体）、tick 末合并、required/committed 提交流程、重启不倒退、
  未修改邻居不递增、真实落盘验证。
- **原阶段 2 控制协议与对象库**：包络/消息编解码、HELLO 指纹、WORLD_EPOCH/INVALIDATE/
  PUT_SNAPSHOT/RESOLVE_CHUNK、master→多 slave 订阅/流控/滞后/重拉、SnapshotObjectStore 磁盘
  对象库（required/committed 单调、损坏 fail closed）。
- **原阶段 3 旁观会话技术**：独立 netty 后端状态机、区块/方块实体/光照编码、注册表转储 golden、
  ServeGate fail closed（作为 slave-mod 的技术基础，virtual-backend 保留只读变体参考）。
- **阶段 1 架构修正**：拓扑重定位（玩家只能→slave→master→源服）、单端口封装通道
  （ChaCha20-Poly1305 + 公共密码 + 每 slave UUID；畸形/原版握手 RST）、control-service 并入 master、
  backend 模组 velocity 授信模式（forwarding secret，master 主动连接）、slave Fabric 模组化
  （原生 MC 服务器进程，不做世界计算，256M 约束）、Auth Gateway 归 master（Mojang 正版验证 +
  断言签名 + 票据单次兑换 + 会话记账 + 登录握手移交 master）。
- **阶段 3 master 统筹**：落点判定（RoutingPolicy 白名单/fallback）、健康检查/断路器、全局会话
  记账（重复会话拒新/踢旧/断开注销）、权威会话桥 v1（MC_PASSTHROUGH 字节透传 + PacketLog +
  源服故障 fallback）、源服数据包缓存管道（快照进对象库）。
- **阶段 4 slave 会话（Fabric 模组化）**：26.3 状态机 + committed snapshot 装载（迁移原阶段 3
  技术）、世界计算禁用（生成器禁用、tick 模拟裁剪、禁持久化写回、启动自检）、256M 约束
  （磁盘对象库按需装载、注入即释放、MixinTargetVerifier 自检）、初始同步闸门 +
  setChunkForced 装载出生点快照（里程碑 6 真实装配端到端验证）。
- **阶段 5 单格乐观操作**：协议 5 消息（SUBMIT_OPERATION/COMMITTED/REJECTED/GET_OPERATION_STATUS/
  STATUS_RES）+ codec + golden；slave OperationOverlay（可丢弃预测、单 pending、discardAll fail
  closed）；源服 OperationQueue（主线程幂等去重、有界终态历史）+ OperationAdjudicator（BREAK/PLACE
  原子前置条件 + setBlock 权威执行 + canonical delta）；master 操作路由（operationId → 发起 slave；
  源服未连接 fail closed）；快照到达刷新已装载区块（卸载重载/就地应用兜底）。
- **阶段 6 操作类型白名单**：USE_BLOCK/库存/容器/桶/实体互动/红石/活塞/爆炸/模组互动全部保持
  拒绝（slave 对不支持类型直接拒绝，不发提交；各类型前置条件/受影响状态/回滚计划文档化延后）。
- **阶段 7 故障/回归/性能**：golden snapshot、协议状态机与 Mixin 启动自检（MixinTargetVerifier）、
  消息重放/乱序（对象库单调守卫 + OperationQueue 幂等）、源服/控制服务重启容错、slave 断连
  fail closed、操作超时查询（GET_OPERATION_STATUS）、快照到达刷新（修复里程碑 6 限制）。
  256M 堆压测与真实客户端故障模拟延后（需真实玩家负载，阶段 8 端到端吸收）。

## 阶段 8：游戏内功能完善（真机测试驱动的下一阶段）

背景（真机连入测试，debug-run + 真实客户端，发现两个阻塞游戏内功能的缺陷）：

1. **方块不可破坏**：SpectateGate（SpectateGateMixin）拦截全部互动包（handleUseItemOn /
   handleUseItem / handleInteract / handlePlayerAction，ServerGamePacketListenerImpl.java:1267/1337/1407/1834
   对照），真实玩家的挖掘（START/ABORT/STOP_DESTROY_BLOCK）与放置意图被直接丢弃；里程碑 7 的
   乐观操作管道仅经 `/icbmslave operate` 无头命令（RCON 模拟玩家）可达，未接真实玩家网络层。
2. **只能加载 1 个区块**：slave 只服务 committed 快照区块（PlayerBounds 撞墙，未命中 EMPTY 不
   生成），而源服无玩家时只装载出生点焦点区块并发布少量快照；master 重放推送缺陷
   （Replicator.java:202 以**当前 committed payload + 旧事件 payloadHash** 组包）导致 slave 大量
   REJECTED_HASH_MISMATCH（e2e-slave.log 实测），多数区块 required/committed 无法 bootstrap →
   玩家被困在单个快照区块内。

### 8.1 真实玩家操作接线（修复"方块不可破坏"）

- [ ] 草稿 `draft/real-player-operations/` 三步流程：参考仓库（fabric-source/velocity 互动包处理与
      既有操作管道）、官方（ServerGamePacketListenerImpl.handlePlayerAction/handleUseItemOn、
      ServerPlayerGameMode.handleBlockBreakAction/destroyBlock、BlockItem.place；26.3 字段/动作名
      必须来自 minecraft-assets）、差异取舍（slave 不做世界计算 → 只取客户端意图 + 预测，
      源服主线程裁决核心不变）。
- [ ] SpectateGate 改造：handlePlayerAction 的 START/STOP_DESTROY_BLOCK（创造模式 instant break）
      与 handleUseItemOn（PLACE）→ 放行至操作管道；预检验（目标区块可服务、overlay 单 pending、
      类型白名单 BREAK/PLACE、目标非空气/可替换）；其余互动（使用物品/交互实体/丢弃/换手等）
      保持拒绝。
- [ ] 预测可见性：overlay 预测经**已装载 LevelChunk 内存态应用**（setBlockState flags=UPDATE_CLIENTS
      → vanilla 推送 ClientboundBlockUpdatePacket 给玩家视角）；不写对象库/不落盘（SaveGate 已禁
      持久化）；REJECTED/刷新时以 committed 快照重载恢复（可丢弃语义不变）。草稿确认 26.3
      block update 推送路径与 flags 语义（Level.java setBlock 对照）。
- [ ] 真实玩家上下文提交：SUBMIT_OPERATION 携带真实 playerId/virtualSessionId/clientInteractionSequence
      + slave 侧实时 preconditions（committed snapshot + overlay 计算的目标状态哈希）；源服裁决保留
      模拟上下文执行（跨进程无玩家实体，权限按玩家元数据映射，CACHE_PROTOCOL §3 更新）。
- [ ] 拒绝/竞争路径：不可破坏方块（destroyTime<0）、距离外、两人竞争同一方块 → overlay 落回
      committed snapshot + RESYNC 范围（既有终态处理路径接线到玩家）。
- [ ] 生存模式挖掘进度动画（ClientboundBlockDestructionPacket 逐 tick 进度）与掉落/经验语义：
      v1 创造模式即时破坏闭环先行；生存路径在草稿中给出状态 resync plan，未完成前保持拒绝
      （记录 parity matrix）。

### 8.2 快照覆盖扩容（修复"只能加载 1 个区块"）

- [ ] 修复 master 重放推送缺陷（Replicator.java:202）：SNAPSHOT_COMMITTED 重放改为按事件 revision
      取 payload（对象库新增 loadPayload(key, revision)；每 revision payload 已落盘）或按 chunk
      合并为仅推送最终 committed 对象；消除 slave REJECTED_HASH_MISMATCH 风暴，区块 bootstrap
      恢复。附回归测试：多 revision 重放后 slave 对象库 committed 水位与 master 一致。
- [ ] 源服初始区域物化：配置（如 `icbm.materialize.initialRadius`，默认 4-8）→ 源服启动时对
      出生点周边 addTicket 生成并发布快照（玩家首会话即有可探索区域）；生成期脏标记覆盖确认
      （ProtoChunk 生成期 markUnsaved 是否纳入跟踪，ChunkAccessMixin 扩展 + 测试）。
- [ ] 按需物化协议流（协议变更：docs/CACHE_PROTOCOL.md + 两侧 codec + golden 同一提交）：
      CHUNK_MATERIALIZE_REQ/RES —— slave 玩家接近服务边界 → 请求目标 chunk（requestId/
      dimensionId/chunkX/chunkZ/priority/reason=PLAYER_PROXIMITY|PREFETCH）→ master 路由源服
      （源未连接/断路器 OPEN → REJECTED，slave 保持撞墙 fail closed）→ 源服 addTicket 生成 →
      生成完成 markUnsaved → 快照管道 → master 对象库 → SNAPSHOT_PUSH → slave 装载。
- [ ] 物化节流与去重：每 tick 限量、请求去重/冷却、玩家行进方向视距内预取（批量 PREFETCH）；
      防快速移动引发生成风暴（源服主线程热路径不受阻塞）。
- [ ] 装载后清理：源服物化 ticket 在区块生成并快照后释放（不永久驻留）；slave 注入即释放不变
      （256M 约束）。

### 8.3 验证与收尾（吸收原阶段 2/6/7 开放项）

- [ ] 真机闭环验收：debug-run + 真实客户端——出生点周边 ≥N（如 25）区块可进入；破坏/放置经
      操作管道全链路（预测可见 → 源服裁决 → 快照 → slave 刷新 → 视角与源服权威一致）。
- [ ] 原阶段 2 遗留：源服游戏端口仅接受受信节点（master/受信接入）的隔离验证。
- [ ] 原阶段 7 剩余：256M 堆压测（长时间浏览/移动/操作，256M 堆）、真实客户端故障模拟
      （网络分区/缓存损坏/源服回档，全部 fail closed 复验）。
- [ ] parity matrix 更新：生存挖掘动画、掉落、经验、物化边界等有意差异记录。

验收：

- [ ] 真实玩家（创造模式）可破坏/放置简单方块（无容器/实体/流体/红石），操作结果与源服权威
      一致（含拒绝路径落回 committed snapshot，无幽灵 overlay）。
- [ ] 玩家可探索范围突破单区块：初始物化 + 按需物化下连续移动不断档；未命中/落后/epoch
      不匹配仍 fail closed（不展示旧区块）。
- [ ] master 重放缺陷修复后 slave 对象库水位与 master 一致（多 revision 重放回归测试）。
- [ ] 全组件测试绿 + 协议 golden 锁定 + 256M 约束保持（slave 堆自检通过）。

## 阻塞条件

以下任一项未解决前，不应开放相应能力：

- 26.3 mappings 与 Mixin 目标未经实际验证。
- 存在未覆盖的 chunk dirty 或方块实体变更路径。
- slave 与源服的 fingerprint 不一致。
- 缓存对象未达到对应 committedRevision。
- 某个操作类型没有完整 state resync plan，却试图在 slave 执行。
- slave 的 256M 堆自检未通过（内存预算未被证明）。

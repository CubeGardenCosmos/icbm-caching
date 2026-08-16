# 阶段性任务与验收条件

状态：阶段 9（CDN 化改造：边缘会话 + 直传权威面；修复「破坏不同步/聊天不同步」两项已知缺陷）
      为当前规划，规划细节见 docs/PHASE9_PLAN.md；阶段 1-8 的成果作为已完成记录保留（下方压缩为
      交付摘要，不再逐条 checkbox）。
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

### 8.1 真实玩家操作接线（修复"方块不可破坏"）——已完成

- [x] 草稿 `draft/real-player-operations/` 三步流程：参考仓库（既有操作管道）、官方
      （ServerGamePacketListenerImpl.handlePlayerAction/handleUseItemOn、
      ServerPlayerGameMode.handleBlockBreakAction、Level.setBlock/LevelChunk.setBlockState；
      26.3 字段/动作名来自 minecraft-assets）、差异取舍（slave 不做世界计算 → 只取客户端
      意图 + 预测，源服主线程裁决核心不变）。
- [x] SpectateGate 改造：handlePlayerAction 的 START_DESTROY_BLOCK（创造模式 instabuild 即时
      破坏）与 handleUseItemOn（BlockItem PLACE）→ 路由至 PlayerOperationHandler 操作管道；
      ABORT/STOP_DESTROY_BLOCK 与其余互动（使用物品/交互实体/丢弃/换手/冲刺）保持拒绝
      （回显撤销）；主线程守卫（isSameThread，避免 ensureRunningOnSameThread 双触发）。
- [x] 预检验：OperationGate 纯逻辑（可服务性/instabuild/距离/BREAK 目标非 air·非不可破坏·非
      GameMasterBlock/PLACE 目标 air·可替换·非复杂放置/overlay 单 pending），fail closed。
- [x] 预测可见性：overlay 预测经已装载 LevelChunk 内存态应用（setBlock UPDATE_CLIENTS →
      vanilla 广播 ClientboundBlockUpdatePacket 给玩家视角）；不写对象库/不落盘（SaveGate 已禁
      持久化）；REJECTED → RESYNC 就地 refreshLoaded（改为 UPDATE_CLIENTS，修复原 UPDATE_NONE
      不通知客户端）+ 重应用该 chunk 其余 pending 预测；COMMITTED → 快照到达刷新（玩家在
      chunk 内走就地刷新，避免卸载引用不降 0 的 10s 超时）。
- [x] 真实玩家上下文提交：SUBMIT_OPERATION 携带真实 playerId（玩家 UUID 字节）/virtualSessionId
      （"uuid:"+UUID）/clientInteractionSequence（互动包 sequence）+ slave 侧实时 preconditions
      （sha256hex(BlockState.toString)，与源服 OperationAdjudicator.hash 同算法）；源服裁决保留
      模拟上下文执行（权限按玩家元数据映射）；CACHE_PROTOCOL §3 已同步。
- [x] 复杂放置拒绝（源服 + slave 同判定）：hasBlockEntity（容器等，setBlock 建空 BE）/双格
      结构（DoublePlantBlock/DoorBlock/BedBlock，setBlock 只放半格）→ REJECTED，权威世界不
      得产生畸形方块。
- [x] 拒绝/竞争路径：不可破坏方块、距离外、两人竞争同一方块 → 回显撤销 + overlay 落回
      committed snapshot + RESYNC 范围（既有终态处理路径接线到玩家）。
- [x] 生存模式挖掘进度动画（ClientboundBlockDestructionPacket 逐 tick 进度）与掉落/经验：
      v1 创造模式即时破坏闭环已先行落地；生存路径保持拒绝（回显撤销，fail closed），
      resync 计划与方向性放置/音效等差异记录于草稿与 parity matrix（#14-#18）。

验证（阶段 8.1 已随实现落地）：OperationGateTest（全拒绝分支）、OperationOverlayTest
（pendingInChunk）、PlayerOperationHandlerTest（哈希已知向量）、全组件测试绿；真机闭环验收
归入 8.3。

### 8.2 快照覆盖扩容（修复"只能加载 1 个区块"）——已完成

- [x] 修复 master 重放推送缺陷（Replicator.java writeEvent SNAPSHOT_COMMITTED 分支）：对象库新增
      `loadPayload(key, revision)` 按事件 revision 取 payload（每 revision payload 已落盘），
      hash/uncompressedLength 取自该 revision metadata 并与事件 hash 交叉核对（不一致 → 不发，
      fail closed）；消除 slave REJECTED_HASH_MISMATCH 风暴，区块 bootstrap 恢复。回归测试
      `ReplicatorReplayTest`：源服提交 rev1+rev2 → slave 断线重连订阅 → 两个 SNAPSHOT_PUSH 的
      hash 与各自 payload 匹配（旧实现会旧 hash 配新 payload），slave 对象库 committed 水位与
      master 一致（2==2）。
- [x] 源服初始区域物化：配置 `icbm.materialize.initialRadius`（默认 4，0 关闭）→ SERVER_STARTED
      时对出生点周边 radius² 区块 setChunkForced 生成并发布快照（玩家首会话即有可探索区域）；
      已生成焦点区块显式 mark 发布（生成期 ProtoChunk markUnsaved 不跟踪——物化完成=FULL 生成
      完成，显式 mark 一次即可，草稿 §3.3 论证）。
- [x] 按需物化协议流（docs/CACHE_PROTOCOL.md + 两侧 codec + golden 同一提交）：
      CHUNK_MATERIALIZE_REQ(0x1B)/RES(0x1C) —— slave 玩家接近服务边界（PlayerBoundsMixin 撞墙
      → onBlockedMove）→ REQ(requestId/dimension/chunkX/chunkZ/priority/reason=
      PLAYER_PROXIMITY|PREFETCH) → master 路由源服（slave 角色校验；源未连接/发送失败 →
      RES(accepted=false)，slave 保持撞墙 fail closed）→ 源服 MaterializeService 主线程
      setChunkForced 生成 → 完成 mark 脏 → 快照管道 → master 对象库 → SNAPSHOT_PUSH → slave 装载。
- [x] 物化节流与去重：slave MaterializeRequester 同 chunk 冷却（5s）/PLAYER_PROXIMITY 每 tick
      限量（4）/周期预取（2s，半径 2，批量 PREFETCH）；源服每 tick 限量 addTicket（8）——快速
      移动不会引发生成风暴（源服主线程热路径不受阻塞）。
- [x] 装载后清理：源服物化 ticket（setChunkForced）在区块生成且 revision 发布后释放
      （GENERATING→PUBLISHING→移除 显式状态机，超时保护不永久驻留）；slave 注入即释放不变
      （256M 约束）。

### 8.4 slave LLM 翻译（参考 llm-translator）——已完成

- [x] 草稿 `draft/llm-translation/` 三步流程：参考仓库（llm-translator：OpenAI 兼容
      Chat Completions + system prompt 占位符 + 告示牌/书本 NBT 翻译）、官方
      （ServerPlayer.clientInformation().language 客户端 locale、PlayerList 聊天广播、
      SignBlockEntity/SignText、LecternBlockEntity/WrittenBookContent、sendSystemMessage/
      sendOverlayMessage、Commands.LEVEL_ALL）、差异取舍（自动 per-recipient locale 翻译
      替代手动 `t ` 前缀；绿色 [已翻译] 本地化标记；提示词进 slave 配置）。
- [x] 语言支持表 `TranslationLanguages`：zh_hk（繁中·香港白话）/ zh_tw（繁中·台湾）/ en /
      ja / de / fr / zh_cn 七种目标语言；MC locale 归一化（en_us→en、zh_cn、zh_hk、zh_tw、
      ja_jp→ja、de_de→de、fr_fr→fr）；每语言含提示词名 + 本地化 [已翻译] 标记
      （已翻譯/translated/翻訳済み/übersetzt/traduit/已翻译）。
- [x] 基本语言判断 `LanguageJudge`（纯逻辑）：同 locale 跳过、中文变体互发跳过（基础判断
      不细分简繁/白话）、空文本跳过、接收方 locale 不在支持表/配置跳过、脚本启发式
      （ja 目标含假名跳过、zh 目标含 CJK 且无假名跳过；en/de/fr 拉丁不按内容跳过）。
- [x] LLM 客户端 `LlmClient`：JDK 内置 HttpClient 异步（单线程 daemon Executor，不阻塞
      Server tick）+ Gson（Minecraft libraries 提供，无新依赖）；system prompt 支持
      {sourceLanguage}/{targetLanguage} 占位符；超时/非 200/响应缺内容 → LlmException
      fail closed；可选 HTTP 代理（llm.proxyHost/Port，不自动读 HTTPS_PROXY）。
- [x] 配置进 icbm-control.properties（`llm.*`）：baseUrl/model/apiKey/systemPrompt（提示词
      集成在 slave 配置中，可覆盖）/timeoutMillis/maxTextLength/languages/proxyHost/Port；
      apiKey 空 → 翻译功能关闭（fail closed）；配置文件改为 UTF-8 读取（Properties
      load(InputStream) 按 ISO-8859-1 解码会乱码中文提示词）。
- [x] 聊天自动翻译：fabric-message-api `ServerMessageEvents.CHAT_MESSAGE`（广播前观察，
      不取消——原文按原版路径广播，译文异步后达）；同目标语言接收方合并为一次 LLM 调用
      （控成本）；每玩家冷却 5s + pending 上限 2 + 输入截断（llm.maxTextLength）防滥用；
      译文经 server.execute 回 Server thread，绿色 [已翻译] 前缀 + 白色译文系统消息送达。
- [x] 告示牌/书本按需翻译：`/bmtool translate sign|book [x y z]`（坐标缺省 = 玩家位置）
      读取已装载区块 SignBlockEntity（front/back 各 4 行）与 LecternBlockEntity 成书
      （WrittenBookContent pages），一次 LLM 调用 → 绿色 Action Bar 送达；未装载/非告示牌/
      无成书 → 明确提示（fail closed）。
- [x] `/bmtool translate toggle` 无权限（LEVEL_ALL）：按玩家 UUID 内存开关（默认开启；
      slave 禁持久化约束，会话级）；`bmtool` 根命令拆层——debug toggle 保持 op-only，
      translate 子命令人人可用。
- [x] 验证：LanguageJudgeTest（locale 归一化/场景判定/脚本启发式）、LlmClientTest（请求体/
      响应解析/错误 fail closed）、TranslationServiceTest（toggle/冷却/截断）、SlaveConfigTest
      （llm.* 解析/未知短码 fail closed/UTF-8 中文提示词）全绿；全组件测试通过。

### 8.3 验证与收尾（吸收原阶段 2/6/7 开放项；开放项已并入阶段 9.5）

- [ ] 真机闭环验收：debug-run + 真实客户端——出生点周边 ≥N（如 25）区块可进入；破坏/放置经
      操作管道全链路（预测可见 → 源服裁决 → 快照 → slave 刷新 → 视角与源服权威一致）。
      （并入 9.5；破坏快路径见 9.2）
- [ ] 原阶段 2 遗留：源服游戏端口仅接受受信节点（master/受信接入）的隔离验证。（并入 9.5）
- [ ] 原阶段 7 剩余：256M 堆压测（长时间浏览/移动/操作，256M 堆）、真实客户端故障模拟
      （网络分区/缓存损坏/源服回档，全部 fail closed 复验）。（并入 9.5）
- [ ] parity matrix 更新：生存挖掘动画、掉落、经验、物化边界等有意差异记录。（并入 9.5）

验收：

- [ ] 真实玩家（创造模式）可破坏/放置简单方块（无容器/实体/流体/红石），操作结果与源服权威
      一致（含拒绝路径落回 committed snapshot，无幽灵 overlay）。
- [ ] 玩家可探索范围突破单区块：初始物化 + 按需物化下连续移动不断档；未命中/落后/epoch
      不匹配仍 fail closed（不展示旧区块）。
- [ ] master 重放缺陷修复后 slave 对象库水位与 master 一致（多 revision 重放回归测试）。
- [ ] 全组件测试绿 + 协议 golden 锁定 + 256M 约束保持（slave 堆自检通过）。

## 阶段 9：CDN 化改造（边缘会话 + 直传权威面）——当前规划

背景（真机连入测试，阶段 8 收尾前暴露两项已知缺陷；根因与规划细节见 `docs/PHASE9_PLAN.md`）：

1. **玩家破坏方块不能被快速同步**：预测即时、权威同步滞后——多 tick 管线（COMMITTED 与 SNAPSHOT
   双往返）+ 源服无玩家时目标区块卸载（物化 ticket 发布即释放）触发 1 tick/次重试、最坏 400 tick
   （20s）超时拒绝 → 幽灵方块回显。
2. **玩家聊天不能被同步**：聊天只在 slave 本地广播，master/源服/其它 slave 看不到；源服虚拟玩家
   连接未实现（CACHE_PROTOCOL §10 预留能力未落地），不存在权威广播方与下行分发通道。

模型定位（CDN 语义）：slave = CDN 边缘节点（全球部署反向代理源站、缓存高频数据=区块快照，接管
除地图外的玩家 tick 事件）；master = 控制面/聚合层；源服 = 源站（唯一权威）。玩家除 bmtool 外，
聊天、背包等内容直传 master；破坏方块以 master→源服裁决为权威（快路径化）；红石等世界演化
不归 slave。

### 9.1 边缘世界计算禁用（红石不归 slave）——已完成

- [x] 草稿 `draft/edge-no-world-sim/` 三步流程（参考仓库无现成参考 → 官方 ServerLevel.tick/
      tickChunk/tickChunks/NaturalSpawner/计划刻路径逐行核对，类名行号以 minecraft-assets
      为准；差异取舍：gamerule 兜底 + Mixin 禁 gamerule 不可覆盖路径；tickChunk/NaturalSpawner
      由 gamerule 短路无需 Mixin；否决全局 freeze 方案——purgeStaleTickets/chunkMap.tick
      随之停摆导致区块永不卸载）。
- [x] gamerule 兜底（26.3 命名与 1.21 完全不同，GameRules.java 核对）：random_tick_speed=0 /
      spawn_mobs=false / advance_time=false / advance_weather=false / raids=false——代码强制
      （WorldSimGate.apply，非配置项）+ 读回自检（WorldSimGate.verify，fail closed）。
- [x] Mixin 禁用不可由 gamerule 覆盖的路径：LevelTicks.tick（计划刻/红石 + 流体计划刻）、
      Level.tickBlockEntities（方块实体 tick）、ServerLevel.runBlockEvents（方块事件）、
      ServerLevel.tickThunder（闪电）、ServerLevel.tickTime（时间冻结 v1）、
      tickNonPassenger/tickPassenger（非玩家实体 tick 只保留 ServerPlayer）、
      Mob.checkDespawn（生物凭空消失）。
- [x] 时间处理决策：冻结 v1（tickTime cancel，gameTime/昼夜恒定），parity 记录
      （源服周期同步 worldTime 为后续优化，9.5 定稿）。
- [x] MixinTargetVerifier 扩展 tick 目标自检（ServerLevel.tick/tickTime/runBlockEvents/
      tickThunder/tickNonPassenger/tickPassenger、ServerChunkCache.tick、LevelTicks.tick、
      Level.tickBlockEntities、Mob.checkDespawn、GameRules 类，fail closed）。
- [x] 验证：WorldSimGateTest（GameRules 常量反射自检，防 26.3 常量漂移）+ slave-mod 全测试绿。
      红石不 tick/作物不生长/无生物生成/玩家移动视角正常的真机验证并入 9.5 闭环。

### 9.2 破坏快速同步（master 权威快路径）——已完成

- [x] 草稿 `draft/fast-operation-sync/` 三步流程（参考仓库无现成参考 → 官方 26.3
      BlockStateParser/onFullChunkStatusChange/setChunkForced 逐路径核对；canonicalState
      格式实测为 `Block{id}[props]`（StateHolder.java:42/Block.java:576，与 1.21 不同）；
      F3 用 ChunkMap.onFullChunkStatusChange 主线程回调（26.3 无 onFullChunkLoad））。
- [x] F1：slave COMMITTED 就地应用 canonicalState（Server thread，UPDATE_CLIENTS；
      normalizeCanonical + BlockStateParser 解析；未装载跳过等快照幂等兜底；STATUS_RES
      同路径）。
- [x] F2：MaterializeService PUBLISHED_HOLD 阶段 + hold()（30s warm ticket）；OperationQueue
      终态后委托 hold（不再立即释放）。
- [x] F3：ChunkMapFullLoadMixin（onFullChunkStatusChange FULL 回调）+ OperationQueue.
      onChunkFullLoaded 同 tick 续裁（按 chunk 索引；400 tick 上限 fail closed 保留）。
- [x] F4：pending 操作区块快照优先发布（ChunkSnapshotTicker 排序）。
- [x] 验证：e2e 时延度量 首次 58ms / warm 复操作 2ms（验收 热≤200ms 达标；冷路径由 9.5
      真机覆盖——RCON 无头代理无法装载未加载区块）；拒绝路径 bedrock 即时回显回归；
      全组件测试绿。

### 9.3 聊天直传 master（CHAT_RELAY + 下行分发）——已完成

- [x] 草稿 `draft/chat-relay/`：secure chat 签名链差异取舍记 parity（v1 不保留签名链，
      源服 `PlayerChatMessage.unsigned` 广播；断线 fallback 本地广播；/say 不跨节点；
      发送者回显经网络往返）。
- [x] 协议 CHAT_RELAY(0x1D)/CHAT_BROADCAST(0x1E) + codec + golden + CACHE_PROTOCOL 同一提交；
      slave 本地不再广播（fabric ALLOW_CHAT_MESSAGE 拦截，仅玩家聊天路径）→ RELAY →
      master → 源服权威广播（`ChatType.bind(CHAT, registryAccess, name)` +
      `broadcastChatMessage(CommandSourceStack 版)`，PlayerList.java:734）→ 下行分发所有
      订阅 slave 本地送达（瞬时消息不进事件日志、不 ACK）。
- [x] LLM 翻译迁移到接收路径（`TranslationService.onAuthoritativeBroadcast` 下行广播送达钩子
      触发；发送者/冷却/合并复用；bmtool sign/book 保持 slave 本地）。
- [x] 验证：e2e 聊天闭环（slave `icbmslave chat` 上行 → 源服控制台可见 → master 分发 →
      slave 下行送达）；真实客户端捕获 = fabric 事件一行，双 slave 互通由 master 广播保证
      （留 9.5 真机闭环）；golden 锁定；全组件测试绿。
- [x] 顺带修复：e2e 操作时序竞态（warm 复操作前等恢复快照；拒绝路径位置换 chunk 避开单
      pending 撞车）；master 操作转发发送失败显式日志 + fail closed 路由 REJECTED。

### 9.4 背包/互动直传（权威会话桥 v2：虚拟玩家）

- [ ] 草稿 `draft/authoritative-session-v2/`（官方虚拟连接要求、Velocity 注入思路借鉴、
      Protocolize 不引入结论保持）。
- [ ] 源服虚拟玩家会话：master 为每个真实玩家建立/销毁源服会话；BackendControlSession 丢弃改分发。
- [ ] master McPassthroughBridge 单会话 → 多会话（按 playerUuid 路由）。
- [ ] slave 互动包从拒绝改直传（use-item/interact/背包/容器 → MC_PASSTHROUGH 上行；下行注入真实
      客户端连接按真实会话重封装）。
- [ ] 验证：开背包移动物品/容器互动结果来自源服；断线 fail closed 不本地猜测库存。

### 9.5 验证收尾与文档定稿（吸收原 8.3 开放项）

- [ ] 真机闭环验收（原 8.3）：≥25 区块可进入；破坏/放置快路径全链路视角一致。
- [ ] 源服游戏端口受信隔离验证（原 8.3）。
- [ ] 256M 堆压测 + 真实客户端故障模拟（原 8.3）。
- [ ] parity matrix 更新（生存挖掘/掉落/经验、物化边界、世界时间冻结、聊天签名校验、红石不演化）。
- [ ] 文档定稿：ARCHITECTURE（CDN 职责表/报文分流）、HIERARCHY（虚拟玩家会话）、
      CACHE_PROTOCOL（聊天中继/权威会话 v2）、本文件归档。

阶段 9 验收：slave 世界计算全禁用（自检通过）；破坏/放置热 ≤200ms / 冷 ≤1s 视角一致、拒绝即时
回显；聊天跨 slave/源服互通且翻译生效；背包/容器直传源服权威处理并回传；全组件测试绿 + golden
锁定 + 256M 约束保持。

## 阻塞条件

以下任一项未解决前，不应开放相应能力：

- 26.3 mappings 与 Mixin 目标未经实际验证。
- 存在未覆盖的 chunk dirty 或方块实体变更路径。
- slave 与源服的 fingerprint 不一致。
- 缓存对象未达到对应 committedRevision。
- 某个操作类型没有完整 state resync plan，却试图在 slave 执行。
- slave 的 256M 堆自检未通过（内存预算未被证明）。

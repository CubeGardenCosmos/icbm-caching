# 阶段 9 规划：CDN 化改造（边缘会话 + 直传权威面）

状态：规划稿（阶段 9 为下一轮当前规划；本文件是「缺陷分析 + 修复方案 + 新一轮阶段 todo」的唯一
      规划来源，实施前须按 AGENTS.md 硬约束完成 `build/workspace/draft/<功能名>` 三步草稿）。
依据：docs/ARCHITECTURE.md、docs/HIERARCHY.md、docs/CACHE_PROTOCOL.md、docs/PHASES.md；
      26.3 行为结论一律以 `build/workspace/minecraft-assets` 反编译源码为准（AGENTS.md 最高权重）。
版本：本阶段首个功能提交前将 VERSION 从 1.22.0 递增（与改动相称）。

---

## 0. 摘要

真机连入（debug-run + 真实客户端）暴露两项已知缺陷：

1. **玩家破坏方块不能被快速同步**——预测即时、权威同步滞后（多 tick 管线 + 源服无玩家时目标区块
   卸载触发 1 tick/次重试、最坏 400 tick=20s 超时拒绝，"幽灵方块"回显）。
2. **玩家聊天不能被同步**——聊天只在 slave 本地广播（vanilla PlayerList），master/源服/其它 slave
   的玩家与源服控制台永远看不到；多 slave 各自为政，聊天碎片化。

根因不是单点，而是**会话模型没有按 CDN 语义切分**：slave 仍是"一个 vanilla 服务器"（在快照区块上
继续跑红石/随机刻/流体/方块实体 tick/生物生成，见 §2.3），聊天/背包等会话状态没有上行通道
（源服虚拟玩家连接未实现，`BackendControlSession` 对 MC_PASSTHROUGH 直接丢弃，CACHE_PROTOCOL §10
预留能力未落地）。本阶段按 CDN 模型重构职责边界并修复两项缺陷：

- slave = **CDN 边缘节点**：接管玩家会话 tick（移动/碰撞/视角，低延迟），缓存并服务高频数据
  （区块快照=地图）；**不做任何世界计算**（红石/随机刻/流体/计划刻/方块实体 tick/生物生成/天气）。
- 玩家聊天、背包、容器、使用物品等会话内容**直传 master**（→ 源服权威处理，结果回传）；bmtool
  保持 slave 本地（例外项）。
- 玩家破坏方块仍以 **master→源服裁决为权威**，但改为快路径（COMMITTED canonical 就地应用 +
  源服目标区块热驻留 + 加载完成立即裁决）。

---

## 1. 缺陷清单与根因

### 1.1 缺陷 A：玩家破坏方块不能被快速同步

**现象（真机）**：创造模式 instabuild 破坏 → 预测立即生效（方块消失），但权威同步滞后；源服无玩家
时目标区块已卸载，走 `NeedsChunk` 重载重试路径；若区块从未生成（物化未覆盖）则源服现场 worldgen，
最坏 400 tick（20s）后 `chunk-not-loaded` 拒绝 → slave RESYNC 落回 committed snapshot，玩家视角
"方块复活"（幽灵回显）。PHASES 8.3 已记录同源归因（真机日志 21:35 全部操作被拒）。

**根因（按延迟贡献排序）**：

- **R1 结构性管线延迟（热路径 ≥200–400ms）**：操作全程 4 跳、每跳 ≤1 tick：
  1. slave 玩家包 → `SpectateGateMixin.handlePlayerAction` → `PlayerOperationHandler.handleBreak` →
     `SUBMIT_OPERATION`（封装通道异步写）；
  2. master `ControlService.onSubmitOperation` → `SourceConnector` → 源服 `BackendControlSession` →
     `OperationQueue.enqueue`（backend 会话线程）；
  3. 源服 **END_SERVER_TICK** 才轮询裁决（`OperationQueue.onEndServerTick`，最多 16/tick）→ 脏标记 →
     同 tick `ChunkSnapshotTicker.onEndServerTick` 发布 → 后台序列化（`supplyAsync`）→ `server.execute`
     回主线程 → PUT_SNAPSHOT → master 对象库 + 事件日志 → `Replicator` → `SNAPSHOT_PUSH`；
  4. slave `handleSnapshotPush` → `SnapshotReloader.onChunkUpdated` → **下一 tick**
     `SnapshotReloader.onServerTick` → `refreshLoaded`（逐方块差异 + UPDATE_CLIENTS 广播）。
- **R2 双往返：COMMITTED 与 SNAPSHOT 两条链路**：`OPERATION_COMMITTED` 到达 slave 只清 overlay
  （`PlayerOperationHandler.onCommitted`），**不更新视角**；视角更新依赖第二条链路（快照到达 +
  刷新）。COMMITTED 携带的 canonicalState 从未被使用。
- **R3 源服无玩家 → 目标区块卸载（结构性）**：物化 ticket 在快照发布后即释放
  （GENERATING→PUBLISHING→移除），源服无玩家时区块随之卸载；**每次操作**都支付
  `setChunkForced` 重载 + 重试开销；未生成区块还要现跑 worldgen。e2e 日志佐证：单次 BREAK 后
  chunk 0,0 连续 12→13→14→15 多 revision 刷新（光照/邻接传播），管线噪声高。
- **R4 NeedsChunk 重试 1 tick/次**：`OperationQueue.MAX_CHUNK_RETRY_TICKS=400`，重试队列每 tick 只
  弹一个（防同 tick 循环占满槽位），区块加载完成后**最早下一 tick**才裁决，不能加载完成立即裁决。

**修复方案（保持 master/源服权威，只减延迟）**：

- **F1 快路径（核心）**：`OPERATION_COMMITTED` 到达 slave 后，在 Server thread 把
  `canonicalState`（`BlockState.toString()`，`PlayerOperationHandler.parseState` 已具备解析）**就地应用**
  到已装载 LevelChunk（UPDATE_CLIENTS），视角立即与源服一致；随后到达的 SNAPSHOT_PUSH 经
  `refreshLoaded` 差异为空（天然幂等，作为确认）。COMMITTED 与 SNAPSHOT 不再串行依赖。
- **F2 源服操作目标热驻留**：操作终态后目标 chunk 保留 **warm ticket**（如 30s 无操作才释放），
  连续破坏/放置不再逐 op 重载；物化状态机增加 PUBLISHED_HOLD 阶段（GENERATING→PUBLISHING→
  PUBLISHED_HOLD→移除，超时保护不变）。
- **F3 加载完成立即裁决**：`NeedsChunk` 的 `setChunkForced` 加载完成（chunk 进入 FULL 的主线程回调）
  时**同 tick 续裁**，去掉 1 tick/次的轮询等待；保留 400 tick 上限与 fail closed 语义（加载迟迟不完成
  仍拒绝）。
- **F4（可选）操作区块快照优先发布**：含 pending 操作的 chunk 跳过发布队列顺序，优先序列化
  （减少 COMMITTED 后到 SNAPSHOT 的间隔）。
- **F5（阶段外延后项）**：生存模式挖掘动画/掉落/经验（parity #14/#15/#18）不在本轮；真实玩家上下文
  已在 8.1 落地，生存路径按白名单机制逐项开放（每项先完整 resync plan）。

**验证**：e2e 度量「破坏 → 视角与源服权威一致」时延：热路径（warm ticket 命中）目标 ≤200ms，
冷路径（需加载）目标 ≤1s；拒绝路径（竞争/不可破坏）仍即时回显撤销；全组件测试 + golden 不变。

### 1.2 缺陷 B：玩家聊天不能被同步

**现象（真机）**：A 玩家在 slave 上聊天，只有该 slave 上的玩家可见；master 控制台/源服/其它 slave
的玩家看不到；`TranslationService` 只在本地观察 `CHAT_MESSAGE` 后本地送达译文。

**根因**：

- **C1 无聊天上行**：slave 未把聊天（`ServerboundChatPacket` 或 `CHAT_MESSAGE` 事件）转发给 master；
  源服**没有任何玩家会话**——`BackendControlSession` 对 MC_PASSTHROUGH 流直接丢弃
  （日志"虚拟玩家连接未实现"），CACHE_PROTOCOL §10 预留的虚拟玩家连接从未落地；不存在权威广播方。
- **C2 无下行分发**：即使源服产生权威广播，也没有「源服 → master → 所有 slave → 本地送达」通道；
  slave 侧 `SlaveControlClient.ChannelListener` **未覆写 `onMcPacket`**（MC_PASSTHROUGH 下行被默认
  no-op 丢弃），master 侧 `McPassthroughBridge` 仅支持**单一活跃权威会话**（v1）。
- **C3 会话状态全部本地化**：除聊天外，背包/容器/使用物品/实体互动当前被 `SpectateGateMixin` 全拒
  （fail closed），既无本地处理也无上行；源服权威会话缺失使"直传 master"无从谈起。

**修复方案（聊天直传 master）**：

- **F1 协议新增聊天中继**：`CHAT_RELAY(0x1D)`（slave → master → source，携带玩家身份 + 原文 +
  签名数据）+ 下行 `CHAT_BROADCAST(0x1E)`（source → master → 所有订阅 slave → 本地送达）。
  26.3 secure chat 签名：v1 在 slave 转发**签名报文原始字段**，源服校验（若虚拟玩家会话就绪）或
  明确不校验（记入 parity）；不得在 slave 伪造签名。
- **F2 两条实现路线（先后落地）**：
  - **路线 A（轻量 v1，本轮 9.3）**：专用 CHAT_RELAY/CHAT_BROADCAST 消息，源服 backend 以玩家身份
    调用 `PlayerList` 广播（广播结果回传所有 slave）；不做完整虚拟玩家。
  - **路线 B（完整，本轮 9.4）**：**权威会话桥 v2——虚拟玩家**：实现 CACHE_PROTOCOL §10 预留能力，
    master 为每个真实玩家在源服建立虚拟玩家会话，玩家会话报文（聊天/背包/容器/使用物品/实体互动）
    经 master 注入源服 vanilla 全权处理，结果回传。这是背包/容器直传的前提，也是路线 A 的最终归宿
    （A 的 CHAT_RELAY 可退化为 B 的特例）。
- **F3 LLM 翻译迁移到接收路径**：聊天原文经 master 权威广播后，**每个 slave 为自己的接收玩家**按
  locale 翻译（每边缘节点独立 LLM 成本/配额/冷却，原 `TranslationService` 判定逻辑复用，触发点从
  "本地 CHAT_MESSAGE" 改为 "下行广播送达钩子"）；bmtool translate sign|book 保持 slave 本地
  （用户明确例外项）。原文广播路径不因翻译而阻塞（fail closed：译文失败只丢译文不丢原文）。

**验证**：双 slave + 源服控制台三端互通：A 在 slave1 聊天 → slave2 玩家、源服控制台均可见；
`/bmtool translate toggle` 仍按玩家控制译文；签名行为按 parity 记录；golden 测试锁定新消息。

---

## 2. 架构澄清：CDN 模型下的职责边界

用户明确的工作模式：**slave 是 CDN 边缘节点（全球部署），反向代理源站（源服），缓存高频数据
（区块快照）；master 是控制面/聚合层；源服是源站（唯一权威）。**

### 2.1 职责表（CDN 语义）

| 面 | CDN 角色 | 职责 | 边界 |
| --- | --- | --- | --- |
| slave | 边缘节点（PoP） | 终止玩家连接、登录握手递交 master 验证、**玩家 tick 接管**（移动/碰撞/视角，除地图外）、快照区块服务（缓存命中即达，未命中撞墙+物化请求）、乐观操作预测、bmtool（translate/debug） | **不做任何世界计算**：红石/随机刻/流体/计划刻/方块实体 tick/生物生成/天气（地图演化全归源服）；不本地广播聊天；不本地处理背包/容器/使用物品（直传 master） |
| master | CDN 控制面/聚合 | 验证/记账、落点/切换、快照聚合/失效、操作路由（master 为权威路径）、聊天中继、权威会话多路复用、健康/断路器 | 不推断游戏规则；不是玩家可直连的代理 |
| 源服 | 源站 origin | 唯一权威世界：全部世界写入、红石/时间/生物演化、操作裁决、**聊天权威广播**、背包/容器/库存状态 | 不面向玩家（经 master 虚拟玩家会话接入） |

### 2.2 玩家报文分流（CDN 路由规则）

- **边缘自理（低延迟，本地）**：移动/传送确认/chunk 装载视图/keepalive/注册表数据/bmtool。
- **直传 master → 源服（权威）**：聊天、背包/容器/使用物品/实体互动（互动包从"拒绝"改为"直传"）、
  破坏/放置（既有 SUBMIT_OPERATION，改快路径）、伤害/死亡等状态变更。
- **禁止**：红石及一切地图上的演化在 slave 发生（见 9.1）。

### 2.3 现状缺口（本轮必须先修）

- **slave 仍在做世界计算（红石归因）**：slave 是 vanilla 服务器进程，`simulation-distance=8`
  （debug-run）/4（slave_server.py），**无任何 gamerule 覆盖、无 tick 禁用 Mixin**。快照区块以
  FULL 状态注入后进入 vanilla 模拟距离 → 红石计划刻/随机刻/流体/方块实体 tick/生物生成在 slave
  上运行：与源服分叉（同一快照产生不同演化）、快照刷新时被回滚（抖动）。9.1 修复。
- **权威会话桥未端到端接线**：master 侧 `McPassthroughBridge`（单会话）已实现，但 slave 下行
  `onMcPacket` 未覆写、源服虚拟玩家未实现 → 聊天/背包直传无通道。9.3/9.4 修复。
- **slave 本地广播聊天**：`TranslationService.onChatMessage` 观察本地 `CHAT_MESSAGE`（vanilla 在
  slave 本地 PlayerList 广播）。9.3 改为观察下行权威广播。

---

## 3. 新一轮阶段规划（阶段 9 todo list）

原则不变：先证明源服权威性与回滚正确性，再扩大可预测操作和优化性能；每个功能先走
`build/workspace/draft/<功能名>` 三步草稿；协议变更「文档 + 两侧实现 + golden」同一提交。

### 9.1 边缘世界计算禁用（红石不归 slave）

- [ ] 草稿 `draft/edge-no-world-sim/`：参考仓库（无现成参考，取官方 26.3
      `ServerLevel.tick`/`tickChunk`/`NaturalSpawner`/`BlockEntityTicker`/计划刻路径，类名以
      minecraft-assets 核对）、官方做法（哪些演化属世界计算）、差异取舍（slave 保留玩家 tick 与
      快照渲染，冻结地图演化）。
- [ ] gamerule 兜底：`doRandomTick=false`、`doMobSpawning=false`、`doDaylightCycle=false`、
      `doWeatherCycle=false`（slave 服务器配置 + 启动自检）。
- [ ] Mixin 禁用不可由 gamerule 覆盖的路径（实现时以 minecraft-assets 为准逐项核对）：
      计划刻/红石（`Level.scheduledBlockTicks` 相关 tick）、流体 tick、方块实体 tick
      （`ServerLevel.tickBlockEntities`，含熔炉/漏斗等）、非玩家实体 tick（`tickEntities` 只保留
      ServerPlayer）、生物生成（`NaturalSpawner` 兜底）。
- [ ] 时间处理决策落地：slave 冻结时间（安全，v1）或从源服周期同步世界时间（后续优化）；
      记录 parity。
- [ ] 自检：`MixinTargetVerifier` 扩展 tick 目标；启动时验证禁用生效（fail closed）。
- [ ] 验证：红石装置在 slave 不 tick（观察者视角与源服一致）、作物不生长、无生物生成、
      玩家移动/视角/碰撞正常；全组件测试绿。

### 9.2 破坏快速同步（master 权威快路径）

- [ ] 草稿 `draft/fast-operation-sync/`：官方（`ServerGamePacketListenerImpl`/`ServerPlayerGameMode`
      对照在 8.1 已做，本草稿聚焦延迟路径）、差异取舍（COMMITTED 就地应用与快照确认的幂等关系）。
- [ ] F1：slave `PlayerOperationHandler.onCommitted` 就地应用 canonicalState（Server thread，
      UPDATE_CLIENTS），COMMITTED→视角一致不再等 SNAPSHOT。
- [ ] F2：源服操作目标 chunk 热驻留（warm ticket 30s + 物化状态机 PUBLISHED_HOLD 阶段）。
- [ ] F3：NeedsChunk 加载完成回调同 tick 续裁（保留 400 tick 上限 fail closed）。
- [ ] F4（可选）：pending 操作区块快照优先发布。
- [ ] 验证：e2e 时延度量（热 ≤200ms / 冷 ≤1s 目标）+ 拒绝路径即时回显回归；多 revision 噪声
      观察与合并（R4 侧证：减少不必要的同 chunk 连续刷新）。

### 9.3 聊天直传 master（CHAT_RELAY + 下行分发）

- [ ] 草稿 `draft/chat-relay/`：官方（`PlayerList.broadcastChatMessage`、`ServerboundChatPacket`、
      secure chat 签名链、`ClientboundPlayerChatPacket`/系统消息）、差异取舍（签名校验 v1 不校验
      或源服校验、翻译迁移）。
- [ ] 协议新增 `CHAT_RELAY(0x1D)`/`CHAT_BROADCAST(0x1E)` + codec + golden + `docs/CACHE_PROTOCOL.md`
      同一提交；slave 捕获聊天（本地**不再**广播）→ RELAY；master 路由源服；源服以玩家身份权威
      广播 → master → 所有订阅 slave → 本地送达。
- [ ] LLM 翻译迁移到接收路径：`TranslationService` 触发点改为下行广播送达钩子（接收方视角），
      发送者/冷却/合并逻辑复用；bmtool sign/book 保持 slave 本地。
- [ ] 验证：双 slave 互通 + 源服控制台可见；翻译仍生效；secure chat 差异记入 parity；
      golden 锁定。

### 9.4 背包/互动直传（权威会话桥 v2：虚拟玩家）

- [ ] 草稿 `draft/authoritative-session-v2/`：官方（26.3 `ServerLoginPacketListenerImpl`/
      `ServerGamePacketListenerImpl` 对虚拟连接的要求、Packet 编码、注册表同步）、参考仓库
      （Velocity 后端连接注入思路，EXECUTION_PLAN 已评估 Protocolize 不引入）、差异取舍
      （源服虚拟玩家会话生命周期 = 真实玩家会话生命周期）。
- [ ] 源服虚拟玩家会话：master 为每个真实玩家建立/销毁源服会话（复用封装通道 MC_PASSTHROUGH
      流，源服侧实现注入）；`BackendControlSession` 丢弃改为分发。
- [ ] master `McPassthroughBridge` 单会话 → 多会话（按 playerUuid 路由；源服下行按会话回传）。
- [ ] slave 互动包从"拒绝"改"直传"：`SpectateGateMixin.handleUseItem/handleInteract` 与
      背包（`ServerboundContainer*`/`ServerboundContainerClick*`）→ 经 MC_PASSTHROUGH 上行；
      下行注入真实客户端连接（压缩/加密按真实连接会话重封装）。
- [ ] 验证：玩家可开背包移动物品（结果来自源服）、容器互动、使用物品；红石联动由源服处理；
      会话断开/故障 fail closed（不本地猜测库存）。

### 9.5 验证收尾与文档定稿（承接原 8.3 开放项）

- [ ] 真机闭环验收：debug-run + 真实客户端——出生点周边 ≥25 区块可进入；破坏/放置经快路径
      全链路（预测 → 裁决 → canonical 应用 → 快照确认，视角与源服权威一致）。
- [ ] 原阶段 2 遗留：源服游戏端口仅接受受信节点（master/受信接入）的隔离验证。
- [ ] 原阶段 7 剩余：256M 堆压测（长时间浏览/移动/操作）、真实客户端故障模拟
      （网络分区/缓存损坏/源服回档，全部 fail closed 复验）。
- [ ] parity matrix 更新：生存挖掘动画/掉落/经验、物化边界、世界时间冻结、聊天签名校验、
      redstone 不演化等有意差异记录。
- [ ] 文档定稿：ARCHITECTURE.md 增补 CDN 职责表与报文分流、HIERARCHY.md 增补虚拟玩家会话、
      CACHE_PROTOCOL.md 增补聊天中继与权威会话 v2、PHASES.md 归档。

### 阶段 9 验收

- [ ] slave 上红石/随机刻/流体/方块实体 tick/生物生成全部禁用（自检通过），玩家移动/视角正常。
- [ ] 破坏/放置经 master→源服权威裁决，热路径 ≤200ms / 冷路径 ≤1s 视角一致；拒绝路径即时回显。
- [ ] 聊天跨 slave/源服控制台互通，翻译按接收方 locale 生效（bmtool 例外保持本地）。
- [ ] 背包/容器/使用物品直传 master→源服权威处理并回传；fail closed 保持。
- [ ] 全组件测试绿 + 协议 golden 锁定 + 256M 约束保持。

---

## 4. 协议扩展草案

| 消息 | ID | 方向 | 用途 | 落地阶段 |
| --- | --- | --- | --- | --- |
| CHAT_RELAY | 0x1D | slave → master → source | 玩家聊天上行（玩家身份 + 原文 + 签名字段） | 9.3 |
| CHAT_BROADCAST | 0x1E | source → master → 所有 slave | 权威聊天广播下行（本地送达 + 按 locale 翻译钩子） | 9.3 |
| （复用）MC_PASSTHROUGH | streamId=2 | 双向 | 权威会话 v2：背包/容器/使用物品/实体互动字节透传（多会话） | 9.4 |

约束：新消息 type 与 `docs/CACHE_PROTOCOL.md`、`protocol/` codec、golden 测试同一提交；
MC_PASSTHROUGH 多会话路由按 playerUuid（master 会话记账复用），流序号由发起方维护（master 不
校验内容，CACHE_PROTOCOL §10 不变）。

## 5. 风险与防线

| 风险 | 防线 |
| --- | --- |
| slave 禁用模拟后玩家体验缺口（如作物不生长） | 地图演化全部由源服权威快照覆盖；parity 明确记录；后续按白名单逐项开放（先完整 resync plan） |
| COMMITTED 就地应用与快照确认竞态 | canonicalState 与快照同源（源服主线程裁决输出），refreshLoaded 差异为空幂等；epoch/revision 守卫不变 |
| 源服 warm ticket 常驻内存 | 30s 超时 + 终态后释放 + 256M 源服不受限（源服非 256M 约束），但防驻留泄漏有超时保护 |
| 聊天签名校验缺位 | v1 明确不校验（记入 parity）；虚拟玩家会话（9.4）后由源服 vanilla 校验链兜底 |
| 多会话权威桥混淆源服会话 | master 按 playerUuid 路由 + 会话记账（重复会话拒新/踢旧不变），源服虚拟会话生命周期与真实会话绑定 |
| 26.3 类名/方法漂移 | tick 禁用与虚拟玩家的 Mixin 目标以 minecraft-assets 实测核对；MixinTargetVerifier 启动自检 fail closed |

## 6. 依赖关系

```
9.1 边缘世界计算禁用（红石不归 slave）──┬──▶ 9.2 破坏快速同步（F1-F4，独立可并行）
                                        └──▶ 9.3 聊天直传（CHAT_RELAY 协议 + 下行分发）
                                                 │
                                                 └──▶ 9.4 背包/互动直传（权威会话桥 v2，依赖 9.3 的下行通道经验）
                                                          │
                                                          └──▶ 9.5 验证收尾与文档定稿（吸收原 8.3）
```

9.1 是 CDN 化的地基（先证明 slave 不再分叉世界）；9.2 与 9.3 相互独立可并行；9.4 依赖 9.3 的
下行分发通道（或直接复用 MC_PASSTHROUGH 下行接线经验）；9.5 收口。

# 开发设计：26.3 Fabric 权威源服、master 统筹中心与 slave Fabric 模组后端

状态：设计基线（阶段1 架构修正：master 统筹 + slave 模组化 + Auth Gateway 归位）
目标版本：Minecraft Java Edition 26.3，单一固定协议
范围：开发文档，不包含代码实现

## 1. 架构决策

- 玩家经 **slave** 接入；slave 是运行在原生 Minecraft 服务器上的 **Fabric 模组**，终止客户端
  登录握手，把验证材料递交给 master 验证。
- **master（Velocity 统筹中心 + Auth Gateway）** 是唯一信任根与决策中心：Mojang 正版验证、
  全局会话记账（防同一玩家加入多个 slave）、白名单/fallback 落点判定、多服务器切换、快照聚合、
  身份断言签发。master 不作为可直接连接的普通代理部署（玩家流量不经过 master）。
- **源 Fabric 服务器**是唯一权威：唯一能提交世界写入、裁决冲突与发布快照的节点。
- **slave 不做世界计算**：它运行在原生 MC 服务器进程上，但不会真正计算一个 Minecraft 世界；
  它接管游戏内事件，只用服务器基础设施实现：对客户端的 26.3 协议、committed snapshot 装载、
  一部分乐观预测。世界数据来自缓存（快照），不进行 worldgen、tick 模拟或持久化写回。
- slave 的预测只是乐观结果；源服裁决后以 canonical result 覆盖或回滚（“预测永不信任”不变）。

### 1.1 资源约束（硬约束）

- slave 以 **256M JVM 堆**的极端条件为设计目标：进程内不驻留完整世界或大量区块对象。
- **大部分缓存落磁盘**：对象库按区块文件组织（protocol 模块的 `SnapshotObjectStore`），
  内存只保留索引/元数据与极小的热缓存；区块数据按需从磁盘读取、编码即释放。
- 静态数据（注册表/方块状态表/标签）采用紧凑表示与懒加载，不得全量常驻内存副本
  （构建期转储资源按需解析）。
- 预测 overlay 同样遵循：可逆预测以最小增量表示，不做内存中的大结构快照。

## 2. 系统边界

    玩家
      │
      ▼
    slave（Fabric 模组后端：原生 MC 服务器进程，协议 + 快照装载 + 有限预测，不做世界计算）
      │                         │
      │验证材料/身份断言        │快照查询（本地磁盘对象库 + ServeGate）
      ▼                         ▼
    master 统筹中心（Velocity 插件 + Auth Gateway + 会话记账 + 快照聚合）
      │                         │
      │快照/失效/操作终态        │SUBMIT_OPERATION
      ▼                         ▼
    源 Fabric 权威模组 ◀─────── 权威世界、游戏规则、冲突裁决

| 组件 | 必须负责 | 不得负责 |
| --- | --- | --- |
| 源 Fabric 权威模组 | 脏标记、快照、revision、操作幂等与主线程裁决 | 信任虚拟后端/slave 的预测结果 |
| master 统筹中心（Velocity） | 快照聚合、required/committed revision、顺序与鉴权、验证/记账/落点判定/切换 | 推断游戏规则；充当普通游戏代理 |
| slave（Fabric 模组后端） | 与客户端的完整 26.3 后端协议、committed snapshot 装载、预测与回滚、协议状态机 | 世界计算（worldgen/tick 模拟/持久化写回）；最终判定世界写入成功 |
| Auth Gateway（master 内） | 唯一 Mojang 正版验证、全局会话记账、身份断言签发 | 处理游戏会话 |
| master 缓存目录 | 汇聚源服快照、签发已提交 revision、向 slave 发布 | 裁决世界操作 |

## 3. slave 后端的最低职责（由原“虚拟后端”演进）

slave 是独立后端服务（原生 MC 服务器进程 + Fabric 模组），不是向 Velocity 内部 pipeline
写入几个数据包的插件。职责：

- 登录后的 configuration 与 play 状态（26.3 最小可用状态机）；
- registry、数据包和维度元数据同步（构建期转储的 golden 资源，启动指纹校验）；
- compression、keepalive 与断线清理；
- 玩家位置、传送确认和 chunk loading window；
- Chunk Data and Update Light、Block Entity Data、Update Light 的编码（由 committed snapshot
  按需编码，内存即时释放）；
- Respawn、Set Center Chunk、Unload Chunk 的客户端视图语义；
- 玩家交互 sequence、Acknowledge Block Change 和状态纠正（预测/回滚路径）。

首版不实现游戏模拟。它只提供旁观者可见的地形、光照与方块实体快照，拒绝所有写入
（interaction 包拒绝且不应用）。之后按操作白名单逐项开启可预测操作。

### 3.1 不做世界计算的含义

slave 运行原生 MC 服务器进程，但：

- 不 worldgen：区块只从 committed snapshot 装载；未命中/落后/epoch 不匹配 → 不展示旧区块
  （fail closed），绝不本地生成“幽灵区块”；
- 不 tick 模拟世界：不跑方块/液体/随机刻/实体模拟（预测操作按白名单以最小模拟实现）；
- 不持久化：独立世界目录、禁 save；无任何到源服世界的写通道；
- 256M 约束：见 §1.1。

## 4. 快照、缓存键与一致性

### 4.1 缓存键

    sourceServerId
    worldEpoch
    dimensionId
    minecraftVersion
    runtimeProtocol
    registryAndDatapackFingerprint
    viewVariant
    chunkX
    chunkZ
    revision

worldEpoch 在世界回档、源服重启且 revision 持久性无法证明、模组或数据包变动、注册表变化、
生成设置变化和管理员范围失效时必须改变。viewVariant 用于隔离权限、队伍、幻觉方块或模组产生的
差异化视图。无法证明两个玩家视图等价时不得共享同一快照。

### 4.2 快照内容

持久缓存保存版本化的区块 NBT 快照和元数据，不保存加密的客户端网络帧：

    metadata: epoch、坐标、revision、格式版本、压缩算法、完整性哈希
    payload: 可由 slave 恢复的区块 NBT
    optional: 方块实体、poi、实体等独立版本化区段

Anvil 的 .mca 是磁盘 region 格式，不等于客户端 Chunk Data and Update Light 包。网络帧的
压缩阈值、会话加密和帧长度不能进入持久缓存。

### 4.3 提交顺序

1. 源 Fabric 模组在当前 tick 将可能变化的区块标为 dirty。
2. tick 末尾合并同一区块的修改，产生更高 revision 和稳定快照。
3. 控制服务先记录 requiredRevision，使旧快照不可读。
4. 快照经哈希、大小和 epoch 校验后持久化。
5. 控制服务原子设置 committedRevision。
6. slave 只以 matching epoch 的 committed snapshot 作为基线。

因此，在源服已知区块变化而新快照尚未到达时，slave 宁可冻结该区块或要求完整重同步，
也不得继续服务旧基线。

### 4.4 磁盘缓存策略（256M 约束）

- 对象库按区块文件落在磁盘（`SnapshotObjectStore`）；slave 启动时扫描重建索引（仅元数据入内存）。
- 区块装载：按需读取对象 → 解压解析 → 编码客户端包 → 发送后立即释放；内存不驻留区块。
- 已编码结果不做内存缓存（或极小 LRU）；revision 变化即失效。
- 静态转储资源：懒加载 + 紧凑内存表示（如 block state 表按需解析索引）。

## 5. 乐观方块操作

### 5.1 操作包

slave 为每个可预测操作生成：

    operationId
    virtualSessionId
    playerId
    clientInteractionSequence
    operationType
    dimensionId
    baseRevision
    preconditions
    actionParameters

preconditions 至少包含目标方块的状态哈希；有方块实体时也包含其哈希。chunk revision 可用于快速
拒绝，但不能作为唯一冲突键：无关方块变更会造成大量假冲突。成熟版本应携带最小化的受影响位置
及其状态版本/哈希集合。

### 5.2 执行与裁决

1. slave 接收客户端交互，并对尚未支持的类型直接拒绝。
2. 对支持的类型，slave 只在可丢弃 overlay 中应用可逆预测；不得改写 committed snapshot。
3. slave 异步发送 SUBMIT_OPERATION。
4. 源 Fabric 模组以 operationId 去重，把操作放到源服主线程。
5. 源服重新检查玩家身份、权限、距离、背包、冷却、方块状态、方块实体和游戏规则，并在权威世界
   中执行或等价模拟该操作。
6. 成功时源服发送 OPERATION_COMMITTED，包含唯一的 canonical delta、玩家状态变化和 revision 集合。
7. 失败时源服发送 OPERATION_REJECTED，包含原因及完整 state resync plan。

源服必须重算结果；禁止直接信任 slave 给出的“方块应该变成什么”。

### 5.3 初始操作白名单

阶段性开放顺序：

1. 无操作的旁观者浏览。
2. 单格 BREAK 与 PLACE，且不涉及容器、实体、液体、红石、活塞、爆炸或跨 chunk 副作用。
3. 每个新增操作类型先定义完整前置条件和回滚集，再进入测试。

USE_BLOCK、桶、容器、实体交互、命令、方块实体 GUI、爆炸、液体、活塞、红石及模组自定义互动在
完整模拟和回滚得到验证前必须拒绝，不应“尽力执行”。

## 6. 回滚不是单一方块包

拒绝、冲突和超时终态不确定时，slave 必须删除相关 overlay，并按源服的 state resync plan 纠正
所有受影响状态：

- 方块或完整 chunk；
- 方块实体和光照；
- 库存、cursor slot、打开容器和窗口状态；
- 实体、玩家位置、速度和传送状态；
- 客户端交互 sequence 的 Acknowledge Block Change。

Acknowledge Block Change 仅使客户端在给定 sequence 后采用服务端方块状态；它不能恢复库存、
容器、光照或复杂副作用。对不确定范围应直接重发完整 chunk 和相关玩家状态。

操作超时不等于操作失败：slave 必须查询 operationId 在源服的终态，或进行完整状态重同步，
不能自行保留或丢弃预测。

为控制回滚复杂度，首版每名玩家每个区块只允许一个 pending operation；相交的操作排队直到
前一个操作达到终态。

## 7. Fabric 源端

Fabric API 的 ServerChunkEvents 适合 chunk 加载、卸载和资源清理，但不代表所有内容变化。
玩家挖掘事件同样不能覆盖活塞、随机刻、命令、世界生成、其他模组或方块实体原地修改。

脏标记采用两层覆盖：

1. Fabric API 生命周期、玩家破坏和方块实体事件用于早期标记与诊断。
2. 使用 Mixin 钩住 26.3 的权威服务端方块修改、chunk 脏标记和 block entity 脏标记路径。

Mixin 的精确目标必须以 26.3 mappings 和实际字节码验证。任何目标缺失都要在启动时禁用缓存命中，
而不是静默降级。源服需保存每 chunk revision（阶段1 已实现并验证）。

## 8. 后台控制协议

“特殊协议字段”应定义为版本化的 ICBM 控制消息字段，而不是在标准 vanilla 游戏包中插入未知字段。
主数据流使用独立的认证后台连接（mTLS，独立受信端口；端口规划待定项见 CACHE_PROTOCOL.md §1）。
登录验证材料（server hash/shared secret/Profile）经受信控制通道递交给 master 的 Auth Gateway，
不进入游戏端口。

Fabric Custom Payload 仅适用于附着在玩家连接上的 client/server 模组消息。没有玩家在线时，
它不能作为源服到 slave 的可靠复制总线；可用于可选能力协商，但不能承载快照和写入提交的主数据流。

## 9. 26.3 兼容性约束

26.3 的 runtime protocol number、包 ID、registry 数据、Mixin 名称和 chunk 序列化细节必须在
构建和启动时验证；禁止复制 26.2 或 1.21.x 的常量。

逻辑上，chunk 初始状态包含高度图、section、方块实体和光照；section 使用 palette 与 packed
long array 编码 block states 和 biomes。具体 bits-per-entry、字段顺序和数组长度随版本、注册表
和数据包变化。slave 只针对单一固定 26.3 组合编码协议。

## 10. 风险与防线

| 风险 | 防线 |
| --- | --- |
| 源服与 slave 时序不同 | 只以源服主线程 CAS 成功顺序判定先到者 |
| 预测层分叉 | overlay 与 committed snapshot 隔离；只接受 canonical result |
| 回滚不完整 | 每个操作类型有 state resync plan 与集成测试 |
| Mixin 漏报 dirty | 先失效后提交；覆盖玩家、刻、命令、模组修改的测试 |
| 26.3 版本漂移 | fingerprint、启动自检、golden snapshot 和协议测试 |
| 控制服务不可用 | fail closed：停止使用新缓存基线，不服务不确定数据 |
| slave 内存超限（256M） | 磁盘对象库 + 按需装载 + 编码即释放 + 启动自检（堆占用上限检查） |
| slave 误 worldgen/持久化 | 生成器禁用/替换、独立世界目录禁 save、Mixin 启动自检 fail closed |

## 11. 参考资料

- Velocity 插件、事件、调度和插件消息
  https://docs.papermc.io/velocity/dev/creating-your-first-plugin/
  https://docs.papermc.io/velocity/dev/event-api/
  https://docs.papermc.io/velocity/dev/scheduler-api/
  https://docs.papermc.io/velocity/dev/plugin-messaging/
- Fabric API lifecycle 与 networking
  https://github.com/FabricMC/fabric-api/tree/26.2/fabric-lifecycle-events-v1
  https://github.com/FabricMC/fabric-api/tree/26.2/fabric-networking-api-v1
- Minecraft 网络区块格式与数据包
  https://minecraft.wiki/w/Java_Edition_protocol/Chunk_format
  https://minecraft.wiki/w/Java_Edition_protocol/Packets

# 开发设计：26.3 Fabric 权威源服与虚拟后端

状态：设计基线  
目标版本：Minecraft Java Edition 26.3，单一固定协议  
范围：开发文档，不包含代码实现

## 1. 架构决策

项目采用“master/slave Velocity + 虚拟后端 + Fabric 权威源服”的架构。部署拓扑和身份信任链以 HIERARCHY.md 为准；本文中的 Velocity 路由器特指各地区的 slave Velocity。

- 玩家经 Velocity 连接虚拟后端。
- 虚拟后端从本地已提交快照发送区块，因此玩家移动不触发源服为该玩家加载或发送新区块。
- 虚拟后端可对有限类型的方块操作进行乐观预测。
- 源 Fabric 模组异步接收操作，并在源服主线程按权威世界状态进行原子裁决。
- 源服接受操作后发布 canonical result；冲突、权限失败或状态不匹配时发布拒绝与回滚计划。

“先到者为准”被严格定义为：最先在源 Fabric 服务器主线程中通过前置条件校验并成功提交的操作。不得以虚拟后端、Velocity 或 TCP 套接字的接收时间决定胜负，因为网络重排、重连和时钟差异会造成双写。

## 2. 系统边界

    玩家
      │
      ▼
    Velocity 插件 ── 路由、健康检查、指标
      │
      ▼
    虚拟后端 ── 26.3 客户端会话、快照基线、pending overlay
      │                         │
      │快照查询                 │操作提交与终态
      ▼                         ▼
    缓存控制服务 ◀──────── 源 Fabric 权威模组
      │                         │
      └── 对象、revision        └── 权威世界、游戏规则、冲突裁决

| 组件 | 必须负责 | 不得负责 |
| --- | --- | --- |
| 源 Fabric 权威模组 | 脏标记、快照、revision、操作幂等与主线程裁决 | 信任虚拟后端的预测结果 |
| 缓存控制服务 | 快照对象、required/committed revision、顺序和鉴权 | 推断游戏规则 |
| 虚拟后端 | 与客户端的完整 26.3 后端协议、缓存区块、预测和回滚 | 最终判定世界写入成功 |
| Velocity 插件 | 把玩家接入虚拟后端、故障断路、观测 | 充当 Minecraft 世界服务器 |
| master 缓存目录 | 汇聚源服快照、签发已提交 revision、向 slave 发布 | 裁决世界操作 |
| slave Velocity 插件 | 验证 master 身份断言、管理本地快照和虚拟后端路由 | 验证 Mojang 正版会话 |

虚拟后端是独立后端服务，不是向 Velocity 内部 pipeline 写入几个数据包的插件。Velocity 应把它作为普通注册后端处理。

## 3. 虚拟后端的最低职责

虚拟后端必须正确实现 26.3 中客户端可见的连接状态机，至少包括：

- 登录后的 configuration 与 play 状态；
- registry、数据包和维度元数据同步；
- compression、encryption、keepalive 与断线清理；
- 玩家位置、传送确认和 chunk loading window；
- Chunk Data and Update Light、Block Update、Update Section Blocks、Block Entity Data、Update Light 的编码；
- Respawn、Set Center Chunk、Unload Chunk 的客户端视图语义；
- 玩家交互 sequence、Acknowledge Block Change 和状态纠正。

首版不实现游戏模拟。它只提供旁观者可见的地形、光照与方块实体快照，拒绝所有写入。之后逐项开启可预测的操作类型。

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

worldEpoch 在世界回档、源服重启且 revision 持久性无法证明、模组或数据包变动、注册表变化、生成设置变化和管理员范围失效时必须改变。

viewVariant 用于隔离权限、队伍、幻觉方块或模组产生的差异化视图。无法证明两个玩家视图等价时不得共享同一快照。

### 4.2 快照内容

持久缓存保存版本化的区块 NBT 快照和元数据，不保存加密的客户端网络帧：

    metadata: epoch、坐标、revision、格式版本、压缩算法、完整性哈希
    payload: 可由虚拟后端恢复的区块 NBT
    optional: 方块实体、poi、实体等独立版本化区段

Anvil 的 .mca 是磁盘 region 格式，不等于客户端 Chunk Data and Update Light 包。网络帧的压缩阈值、会话加密和帧长度也不能进入持久缓存。

### 4.3 提交顺序

1. 源 Fabric 模组在当前 tick 将可能变化的区块标为 dirty。
2. tick 末尾合并同一区块的修改，产生更高 revision 和稳定快照。
3. 控制服务先记录 requiredRevision，使旧快照不可读。
4. 快照经哈希、大小和 epoch 校验后持久化。
5. 控制服务原子设置 committedRevision。
6. 虚拟后端只以 matching epoch 的 committed snapshot 作为基线。

因此，在源服已知区块变化而新快照尚未到达时，虚拟后端宁可冻结该区块或要求完整重同步，也不得继续服务旧基线。

## 5. 乐观方块操作

### 5.1 操作包

虚拟后端为每个可预测操作生成：

    operationId
    virtualSessionId
    playerId
    clientInteractionSequence
    operationType
    dimensionId
    baseRevision
    preconditions
    actionParameters

preconditions 至少包含目标方块的状态哈希；有方块实体时也包含其哈希。chunk revision 可用于快速拒绝，但不能作为唯一冲突键：无关方块变更会造成大量假冲突。成熟版本应携带最小化的受影响位置及其状态版本/哈希集合。

### 5.2 执行与裁决

1. 虚拟后端接收客户端交互，并对尚未支持的类型直接拒绝。
2. 对支持的类型，虚拟后端只在 pending overlay 中应用可逆预测；不得改写 committed snapshot。
3. 虚拟后端异步发送 SUBMIT_OPERATION。
4. 源 Fabric 模组以 operationId 去重，把操作放到源服主线程。
5. 源服重新检查玩家身份、权限、距离、背包、冷却、方块状态、方块实体和游戏规则，并在权威世界中执行或等价模拟该操作。
6. 成功时源服发送 OPERATION_COMMITTED，包含唯一的 canonical delta、玩家状态变化和 revision 集合。
7. 失败时源服发送 OPERATION_REJECTED，包含原因及完整 state resync plan。

源服必须重算结果；禁止直接信任虚拟后端给出的“方块应该变成什么”。

### 5.3 初始操作白名单

阶段性开放顺序：

1. 无操作的旁观者浏览。
2. 单格 BREAK 与 PLACE，且不涉及容器、实体、液体、红石、活塞、爆炸或跨 chunk 副作用。
3. 每个新增操作类型先定义完整前置条件和回滚集，再进入测试。

USE_BLOCK、桶、容器、实体交互、命令、方块实体 GUI、爆炸、液体、活塞、红石及模组自定义互动在完整模拟和回滚得到验证前必须拒绝，不应“尽力执行”。

## 6. 回滚不是单一方块包

拒绝、冲突和超时终态不确定时，虚拟后端必须删除相关 overlay，并按源服的 state resync plan 纠正所有受影响状态：

- 方块或完整 chunk；
- 方块实体和光照；
- 库存、cursor slot、打开容器和窗口状态；
- 实体、玩家位置、速度和传送状态；
- 客户端交互 sequence 的 Acknowledge Block Change。

Acknowledge Block Change 仅使客户端在给定 sequence 后采用服务端方块状态；它不能恢复库存、容器、光照或复杂副作用。对不确定范围应直接重发完整 chunk 和相关玩家状态。

操作超时不等于操作失败：虚拟后端必须查询 operationId 在源服的终态，或进行完整状态重同步，不能自行保留或丢弃预测。

为控制回滚复杂度，首版每名玩家每个区块只允许一个 pending operation；相交的操作排队直到前一个操作达到终态。

## 7. Fabric 源端

Fabric API 的 ServerChunkEvents 适合 chunk 加载、卸载和资源清理，但不代表所有内容变化。玩家挖掘事件同样不能覆盖活塞、随机刻、命令、世界生成、其他模组或方块实体原地修改。

脏标记采用两层覆盖：

1. Fabric API 生命周期、玩家破坏和方块实体事件用于早期标记与诊断。
2. 使用 Mixin 钩住 26.3 的权威服务端方块修改、chunk 脏标记和 block entity 脏标记路径。

Mixin 的精确目标必须以 26.3 mappings 和实际字节码验证。任何目标缺失都要在启动时禁用缓存命中，而不是静默降级。

源服需保存每 chunk revision。可选择 Fabric Data Attachment API 或版本化 PersistentState；若重启后无法保证 revision 单调，就建立新的 worldEpoch。

## 8. 后台控制协议

“特殊协议字段”应定义为版本化的 ICBM 控制消息字段，而不是在标准 vanilla 游戏包中插入未知字段。主数据流使用独立的认证后台连接；推荐 mTLS 的 gRPC、HTTPS 或长连接 TCP。

Fabric Custom Payload 仅适用于附着在玩家连接上的 client/server 模组消息。没有玩家在线时，它不能作为源服到虚拟后端的可靠复制总线；可用于可选能力协商，但不能承载快照和写入提交的主数据流。

master/slave 间的订阅、身份断言、快照复制和路由查询都是异步控制平面，不依赖 Minecraft tick。只有源服对世界操作的最终提交进入源 Fabric 主线程。

协议细节见 CACHE_PROTOCOL.md。

## 9. 26.3 兼容性约束

26.3 的 runtime protocol number、包 ID、registry 数据、Mixin 名称和 chunk 序列化细节必须在构建和启动时验证；禁止复制 26.2 或 1.21.x 的常量。

逻辑上，chunk 初始状态包含高度图、section、方块实体和光照；section 使用 palette 与 packed long array 编码 block states 和 biomes。具体 bits-per-entry、字段顺序和数组长度随版本、注册表和数据包变化。虚拟后端只针对单一固定 26.3 组合编码协议。

## 10. 风险与防线

| 风险 | 防线 |
| --- | --- |
| 源服与虚拟后端时序不同 | 只以源服主线程 CAS 成功顺序判定先到者 |
| 预测层分叉 | overlay 与 committed snapshot 隔离；只接受 canonical result |
| 回滚不完整 | 每个操作类型有 state resync plan 与集成测试 |
| Mixin 漏报 dirty | 先失效后提交；覆盖玩家、刻、命令、模组修改的测试 |
| 26.3 版本漂移 | fingerprint、启动自检、golden snapshot 和协议测试 |
| 控制服务不可用 | fail closed：停止使用新缓存基线，不服务不确定数据 |

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

公开 Fabric API 示例目前可检索到 26.2 分支；实际开发前须用 26.3 对应 Loader、Fabric API 和 mappings 验证全部 API 名称与 Mixin 目标。

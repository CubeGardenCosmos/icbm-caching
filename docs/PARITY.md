# Parity Matrix：有意差异与一致性记录

本表记录与 Minecraft 26.3-snapshot-8 原版可观察行为的差异或一致性结论（AGENTS.md「原版一致性与运行时约束」）。

| # | 主题 | 原版行为 | icbm 行为 | 判定 | 依据/备注 |
| --- | --- | --- | --- | --- | --- |
| 1 | 光照跨区块边界的 revision | 光照变化经 `ChunkHolder.sectionLightChanged` → `markUnsaved()`（ChunkHolder.java:148），相邻区块持久化数据（光照 section）随之变化 | 与 vanilla 脏标记一致：光照传播使相邻区块 revision 递增 | 一致（有意接受） | 快照必须反映光照变化（虚拟后端需要），故跟踪 vanilla 的全部 markUnsaved 而非仅方块；草稿 phase1-source-revision §3 |
| 2 | 每 tick 合并 | 单次修改可触发多次 markUnsaved（方块 + 光照结算跨 tick） | 每 tick 末 drain 合并为一次 revision + 一个稳定快照；跨 tick 的光照结算产生后续 revision | 一致 | 与「tick 末尾合并同一区块的修改，产生更高 revision 和稳定快照」设计一致 |
| 3 | 区块卸载时未发布修改 | vanilla 保存路径持久化区块 | 先失效（raise required）不提交，等待下次加载+修改后补快照 | 有意差异 | ARCHITECTURE §4.3「宁可冻结该区块或要求完整重同步」 |
| 4 | epoch 触发 | 无（原版无 epoch 概念） | START / ROLLBACK / MODPACK_CHANGE / ADMIN 新 epoch 使旧快照失效 | 项目扩展 | CACHE_PROTOCOL WORLD_EPOCH |
| 5 | secure chat | — | 不适用（本项目无聊天实现） | — | 与 icbm 服务端项目不同，本仓库不涉及聊天 |
| 6 | slave 落后不服务 | 无（原版无缓存复制） | 落后 slave（committed < master required）拒绝服务该区块，直到收到新快照 | 项目扩展 | ServeGate STALE 判定；CACHE_PROTOCOL §6 |
| 7 | 控制服务不可用 | 无 | slave/虚拟后端拒绝不确定基线（连接断开即停止服务） | 项目扩展 | fail closed；CACHE_PROTOCOL §6 |
| 8 | 序号校验 | 无（原版协议无包序号） | 源→master 连接内序号严格递增（gap/重放断连）；跨连接由对象库单调性兜底 | 项目扩展 | CACHE_PROTOCOL §1 |
| 9 | 区块包高度图条目顺序 | vanilla `ClientboundLevelChunkPacketData` 用 `Collectors.toMap`（HashMap 迭代序，跨 JVM 不稳定） | 固定按枚举序 WORLD_SURFACE → MOTION_BLOCKING | 一致（渲染等价） | 客户端按类型 id 解入 EnumMap，顺序无关；虚拟后端确定性优先；草稿 phase3-virtual-backend §2 |
| 10 | 区块包方块实体 tag | vanilla 线上 tag = `BlockEntity.getUpdateTag`（多数类型空；sign/beacon 等为 saveAdditional） | 由快照 saveWithFullMetadata 剥离 x/y/z 后直发（含 id/components/附加数据） | 一致（渲染等价） | 客户端以包内 BlockEntityInfo.type 构造并 loadAdditional，多余字段无害；逐字节差异记录于此；草稿 phase3-virtual-backend §2 |
| 11 | 光照空层 mask | vanilla `prepareSectionData`：引擎持有全零 DataLayer → 置 emptyMask | 快照 NBT 只存非空层，空层不置 mask 也不发数据 | 一致（渲染等价） | 客户端初始光照为 0，缺失 mask 视为无变化 → 同样全零；草稿 phase3-virtual-backend §2 |
| 12 | 登录/身份 | 原版 online mode（Mojang 验证） | 阶段3 虚拟后端 offline 模式（离线 UUID，名字哈希），Auth Gateway 接入为阶段0/4 | 阶段限制 | HIERARCHY §3；LoginSession 扩展点 |
| 13 | 区块包编码非确定部分 | vanilla 同一快照的编码字节 | 虚拟后端从快照 NBT 确定性重编码（同输入同输出，测试锁定） | 一致 | 真实快照 golden 测试；vanilla 本身依赖内存态（调色板/光照引擎），无字节级参照 |
| 14 | 生存模式挖掘 | vanilla 逐 tick 挖掘进度（ClientboundBlockDestructionPacket）+ 破坏掉落/经验 | v1 只接创造模式（instabuild 即时破坏）；生存玩家挖掘保持拒绝（回显撤销，fail closed） | 阶段限制 | 阶段 8.1；挖掘动画/掉落/经验为阶段 8 延后项 |
| 15 | 破坏音效/粒子 | vanilla destroyBlock 触发 world event（音效/粒子） | v1 只发 ClientboundBlockUpdatePacket（UPDATE_CLIENTS），无音效/粒子 | 阶段限制 | 阶段 8.1；草稿 real-player-operations §3 |
| 16 | 方向性放置状态 | vanilla BlockItem.place 用 getStateForPlacement（玩家朝向/waterlogged） | v1 用 defaultBlockState（如楼梯恒朝北）；客户端以回显更新校正 | 有意差异 | 阶段 8.1；草稿 real-player-operations §5 待办 |
| 17 | 容器/双格/门/床放置 | vanilla BlockItem.place 处理块实体建置与 setPlacedBy、双格结构 placeAt | v1 拒绝（complex-placement-block：hasBlockEntity/DoublePlantBlock/DoorBlock/BedBlock，源服同判定） | 阶段限制 | 阶段 8.1；防权威世界畸形（setBlock 只放半格/空 BE） |
| 18 | 放置音效 | vanilla place 触发音效 | v1 无音效 | 阶段限制 | 阶段 8.1 |
| 19 | 未命中区块的可达性 | vanilla 未生成区块可进入（按需生成） | slave 未命中区块撞墙（fail closed），物化请求（CHUNK_MATERIALIZE_REQ）经 master 路由源服生成后装载；源服不可达时保持撞墙 | 项目扩展 | 阶段 8.2；PlayerBounds + MaterializeRequester |
| 20 | 物化 ticket 生命周期 | /forceload 永久强制加载 | 生成 + 快照发布后即释放（GENERATING→PUBLISHING→移除 状态机，超时保护） | 项目扩展 | 阶段 8.2；MaterializeService |
| 21 | 生成期脏标记跟踪 | ProtoChunk 生成期 markUnsaved（ProtoChunk.java:228）不面向保存调度 | 不跟踪 ProtoChunk；物化完成（FULL）显式 mark 一次进入快照管道 | 一致（快照语义等价） | 阶段 8.2；草稿 on-demand-materialization §3.3 |
| 22 | 操作目标区块未加载 | vanilla 玩家在线时目标区块必在内存（玩家周边加载） | 源服无玩家：物化 ticket 释放后区块卸载；操作裁决遇未加载区块 → setChunkForced 加载重试（NeedsChunk，上限 400t=20s，加载完成后同 tick 裁决），超时仍拒绝 chunk-not-loaded（fail closed） | 项目扩展（操作管道） | 阶段 8.3；OperationQueue + OperationAdjudicator；真机日志 21:35 全部操作被拒归因 |

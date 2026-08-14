# 阶段性任务与验收条件

状态：全部未开始  
原则：先证明源服权威性和回滚正确性，再扩大可预测操作和优化性能。

## 阶段 0：26.3 版本冻结

- [ ] 记录完整 Minecraft 26.3 构建、运行时 protocol、Java、Velocity、Fabric Loader、Fabric API 和 mappings 版本。
- [ ] 固定源服模组、配置、数据包和生成设置，并计算 modpack/registry/datapack fingerprint。
- [ ] 建立源 Fabric、master 缓存目录、slave Velocity、虚拟后端和控制服务的隔离实验环境。
- [ ] 建立 master 与至少两个地区 slave 的 mTLS 控制平面，验证 slave 之间不互信。
- [ ] 部署独立 Auth Gateway；它是唯一执行 Mojang 正版验证的组件。
- [ ] 确定玩家入口：默认 Auth Gateway -> master -> slave；只有在 master ticket 可安全兑换时才允许直接连接 slave。
- [ ] 准备覆盖普通方块、方块实体、液体、活塞、红石、命令、世界生成和模组修改的测试世界。

验收：

- [ ] 所有运行时组件记录相同的 26.3 兼容性清单。
- [ ] 任一模组或数据包变动都被判定为新 fingerprint。
- [ ] 任一 slave 只能连接受信任 master，不能把另一个 slave 作为身份或快照来源。

## 阶段 1：源 Fabric revision 与快照

- [ ] 实现 worldEpoch 和 chunk revision 的持久化。
- [ ] 接入 ServerChunkEvents、玩家破坏和方块实体事件作为诊断与早期 dirty 标记。
- [ ] 用 26.3 mappings 和字节码确认方块修改、chunk 脏标记和方块实体脏标记的 Mixin 目标。
- [ ] 每 tick 合并 dirty chunk，一次性生成 revision 和稳定快照。
- [ ] 实现先 requiredRevision 后 committedRevision 的对象提交流程。

验收：

- [ ] 玩家、刻、命令、活塞、液体、世界生成、方块实体和模组修改都会递增正确 chunk 的 revision。
- [ ] 未修改邻居不递增 revision。
- [ ] 重启后 revision 不倒退；无法证明时产生新 epoch。

## 阶段 2：后台控制协议

- [ ] 按 CACHE_PROTOCOL.md 实现 mTLS、HELLO、WORLD_EPOCH、INVALIDATE、PUT_SNAPSHOT 和 RESOLVE_CHUNK。
- [ ] 实现 schema、大小、哈希、序号、权限和 fingerprint 校验。
- [ ] 实现对象库、requiredRevision 与 committedRevision 的持久恢复。
- [ ] 实现连接断开、补发、范围失效和 epoch 切换。
- [ ] 实现 master 到多个 slave 的订阅、流量控制、滞后检测和快照重拉。

验收：

- [ ] INVALIDATE 到 snapshot committed 之间，RESOLVE_CHUNK 不返回旧 HIT。
- [ ] 乱序、重放和损坏快照不能降低 revision 或覆盖新对象。
- [ ] 控制服务不可用时，虚拟后端拒绝不确定基线。
- [ ] 落后的 slave 不会服务低于 master requiredRevision 的区块。

## 阶段 3：虚拟后端旁观者会话

- [ ] 实现 26.3 后端连接状态机的最小可用部分：configuration/play、registry、compression、keepalive、传送确认、chunk loading window 和断线清理。
- [ ] 实现 committed snapshot 的加载及 26.3 区块、方块实体和光照包编码。
- [ ] 只开放旁观者模式，拒绝所有世界互动。
- [ ] 验证源服和虚拟后端的区块坐标、方块状态、biome、光照和方块实体渲染一致。

验收：

- [ ] 玩家在虚拟后端移动时不建立到源服的游戏后端会话。
- [ ] 缓存未命中或 epoch/fingerprint 不匹配时不展示旧区块。
- [ ] 虚拟后端不会写入 committed snapshot 或源服世界。

## 阶段 4：Velocity 路由与运行观测

- [ ] 在 ProxyInitializeEvent 后初始化虚拟后端注册、控制服务客户端、命令和指标。
- [ ] 实现 master 身份断言的验签、受众校验、短时过期、单次兑换和吊销处理。
- [ ] 保证 slave 的监听端口策略与入口模式一致；未经 master 认证的客户端不能进入虚拟会话。
- [ ] 实现玩家到虚拟后端的路由、健康检查和断路。
- [ ] 记录 snapshot 命中、过期、加载时延、源服同步时延和虚拟后端会话数。
- [ ] 确保 Velocity 事件线程上没有同步 RPC、磁盘 IO 或大对象反序列化。

验收：

- [ ] 虚拟后端或控制服务故障时，路由安全失败且不服务不确定数据。
- [ ] 指标能够按世界、维度和失败原因定位问题。
- [ ] master/slave 的身份票据重放、跨 slave 转用、过期和吊销均被拒绝。

## 阶段 5：单格乐观操作

- [ ] 定义 operationId、virtualSessionId、client sequence、baseRevision 和状态哈希前置条件。
- [ ] 虚拟后端以可丢弃 overlay 存储预测，不修改 committed snapshot。
- [ ] 源 Fabric 模组在主线程实现幂等操作队列和原子前置条件检查。
- [ ] 只开放无容器、无实体、无流体、无红石、无跨 chunk 副作用的 BREAK/PLACE。
- [ ] 实现 OPERATION_COMMITTED 和 OPERATION_REJECTED。
- [ ] 实现超时后的 GET_OPERATION_STATUS 和保守状态重同步。

验收：

- [ ] 两人竞争同一方块时，源服只提交一个 operationId。
- [ ] 被拒绝玩家最终方块、方块实体、光照、库存和 interaction sequence 与源服一致。
- [ ] 重复、乱序和超时消息不会造成二次写入或幽灵 overlay。

## 阶段 6：扩大操作类型

- [ ] 为每种新增操作写出受影响状态集合、前置条件和 state resync plan。
- [ ] 按顺序评估 USE_BLOCK、库存/容器、桶、实体互动、红石/活塞、爆炸和模组自定义互动。
- [ ] 对跨 chunk、异步源服 tick 和多玩家争用建立集成测试。

验收：

- [ ] 每种操作的源服 canonical result 均可按 operationId 审计。
- [ ] 任何无法完整回滚的操作保持禁用。

## 阶段 7：故障、回归与性能

- [ ] 构建 26.3 golden snapshot、协议状态机和 Mixin 启动自检。
- [ ] 模拟源服回档、源服/控制服务重启、网络分区、消息重放、缓存损坏和虚拟后端故障。
- [ ] 压测多玩家移动、热点区块与高冲突操作。
- [ ] 正确性通过后再优化热区索引、磁盘分层、压缩、批处理和预热。

验收：

- [ ] 所有故障结果只有“权威最新状态”“完整权威回滚”或“停止使用不确定基线”。
- [ ] 性能优化关闭后仍保留全部一致性保证。

## 阻塞条件

以下任一项未解决前，不应开放相应能力：

- 26.3 mappings 与 Mixin 目标未经实际验证。
- 存在未覆盖的 chunk dirty 或方块实体变更路径。
- 虚拟后端与源服的 fingerprint 不一致。
- 缓存对象未达到对应 committedRevision。
- 某个操作类型没有完整 state resync plan，却试图在虚拟后端执行。

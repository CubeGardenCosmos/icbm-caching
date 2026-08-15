# 阶段性任务与验收条件

状态：阶段1（架构修正：master 统筹中心 + slave Fabric 模组化 + Auth Gateway 归位）文档层落地；
      原阶段 0-3 的成果作为已完成基线保留（不再以阶段编号呈现）。
原则：先证明源服权威性和回滚正确性，再扩大可预测操作和优化性能。

## 已完成基线（原阶段 0-3 成果，保留）

- [x] 26.3 版本冻结与指纹（原阶段0）：Minecraft 26.3-snapshot-8、runtime protocol 1073742154、
      DataVersion 5010、Java 25、Fabric Loader 0.19.3、Fabric API 0.157.1+26.3；
      modpackFingerprint 与 registryAndDatapackFingerprint 计算与记录。
- [x] 源 Fabric worldEpoch / chunk revision / 快照提交（原阶段1，本修正后不再单列阶段，仍为权威基线）：
      SourceState 持久化、markUnsaved 权威路径 Mixin、tick 末合并、required/committed 提交流程，
      重启不倒退、未修改邻居不递增、真实落盘验证。
- [x] 后台控制协议与对象库（原阶段2）：mTLS 控制平面、包络/消息编解码、HELLO 指纹、
      WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/RESOLVE_CHUNK、master→多 slave 订阅/流控/滞后/重拉、
      SnapshotObjectStore 磁盘对象库（required/committed 单调、损坏 fail closed）。
- [x] 26.3 旁观会话技术（原阶段3）：独立 netty 后端状态机、区块/方块实体/光照编码、注册表转储
      golden 资源、ServeGate fail closed（作为 slave Fabric 模组化的技术基础）。

## 阶段 1：架构修正（本次修正）

master 为统筹中心，slave 为 Fabric 模组后端；Auth Gateway 归 master。

- [x] 文档重定位：拓扑（玩家→slave→master→后端）、Auth Gateway 归位与会话记账、
      slave 形态（原生 MC 服务器 + Fabric 模组，不做世界计算）、资源约束（256M JVM、缓存落磁盘）、
      阶段体系重组（原阶段1 移除，本修正为新阶段1）。
- [ ] velocity-plugin → master 统筹插件：白名单/fallback 落点判定、多服务器切换、
      全局活跃会话记账、健康检查、身份断言签发与吊销（master 不作为可直接连接的普通代理）。
- [ ] Auth Gateway 归 master：slave 终止登录握手后递验证材料，master 唯一验证 + 记账
      （防同一玩家加入多个 slave），签发 MasterVerifiedIdentity；
      LOGIN_VERIFY 系列消息定义与两侧实现（见 CACHE_PROTOCOL.md §9，协议变更随两侧同一提交）。
- [ ] slave Fabric 模组（新组件）：原生 MC 服务器进程上接管游戏事件，只实现协议与部分预测，
      不做世界计算；256M JVM 与磁盘缓存约束落地（按需装载、编码即释放、生成器禁用/替换、
      独立世界目录禁 save、启动自检 fail closed）。
- [ ] 控制平面端口规划定案：独立受信端口（现状）vs 打包进 25565 游戏端口（备选，含安全/隔离
      评估结论），并同步 HIERARCHY §6 与 CACHE_PROTOCOL §1。

验收：

- [ ] master 只被 slave 接入，玩家不能把它当普通代理直接游戏。
- [ ] 同一玩家无法同时在多个 slave 建立会话（全局会话记账）。
- [ ] slave 以 256M 堆启动并通过自检（不驻留区块、对象库落盘、静态数据懒加载）。
- [ ] 文档中的拓扑/职责/约束与实现一致（AGENTS.md 组件归属同步）。

## 阶段 2：源服权威与快照（基线保持，修复期）

原阶段1 能力按新架构复验与维护：

- [ ] 新架构下源服 Mixin 目标、revision 持久化、快照发布与验证结论复验（无回归）。
- [ ] 源服游戏端口仅接受受信节点（master/受信接入）的隔离验证。

验收：

- [ ] 玩家、刻、命令、活塞、液体、世界生成、方块实体和模组修改仍递增正确 chunk 的 revision。
- [ ] 重启后 revision 不倒退；无法证明时产生新 epoch。

## 阶段 3：master 统筹控制平面

- [ ] master 插件实现落点判定（白名单/fallback）与多服务器切换（fallback、/server、transfer）。
- [ ] 全局会话记账：验证通过登记、登出/断开注销、竞态两阶段确认、重复会话踢旧/拒新策略。
- [ ] Auth Gateway 验证流程（LOGIN_VERIFY_REQ/RES）与身份断言签发/单次兑换/吊销。
- [ ] 健康检查与断路器：后端不可用时落点 fallback，不服务不确定数据。

验收：

- [ ] 玩家可在源服与 slave 后端之间切换，身份断言随会话传递且不重放。
- [ ] 未经 master 验证的客户端无法进入任何后端；重复会话被拒绝。
- [ ] 后端故障时路由安全失败且不服务不确定数据。

## 阶段 4：slave 后端会话（Fabric 模组化）

- [ ] slave 模组在原生 MC 服务器上实现 26.3 状态机与 committed snapshot 装载（迁移原阶段3 技术，
      移除独立 netty 进程依赖或保留为只读变体）。
- [ ] 世界计算禁用：生成器禁用/替换、tick 模拟裁剪、持久化写回禁止；启动自检 fail closed。
- [ ] 256M 约束：磁盘对象库按需装载、编码即释放、静态数据懒加载、堆占用自检。
- [ ] 只开放旁观者模式，拒绝所有世界互动（与 master 验证/记账联动）。

验收：

- [ ] 玩家移动不建立源服游戏会话；缓存未命中/epoch/fingerprint 不匹配不展示旧区块。
- [ ] slave 不写入 committed snapshot 或源服世界；不产生本地生成区块。
- [ ] 256M 堆下稳定运行（含长时间浏览与切换）。

## 阶段 5：单格乐观操作

- [ ] 定义 operationId、virtualSessionId、client sequence、baseRevision 和状态哈希前置条件。
- [ ] slave 以可丢弃 overlay 存储预测（256M 约束：最小增量表示），不修改 committed snapshot。
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
- [ ] 模拟源服回档、源服/控制服务重启、网络分区、消息重放、缓存损坏和 slave 故障。
- [ ] 压测多玩家移动、热点区块与高冲突操作；256M 堆压力测试。
- [ ] 正确性通过后再优化热区索引、磁盘分层、压缩、批处理和预热。

验收：

- [ ] 所有故障结果只有“权威最新状态”“完整权威回滚”或“停止使用不确定基线”。
- [ ] 性能优化关闭后仍保留全部一致性保证；256M 约束不因优化开启而失效。

## 阻塞条件

以下任一项未解决前，不应开放相应能力：

- 26.3 mappings 与 Mixin 目标未经实际验证。
- 存在未覆盖的 chunk dirty 或方块实体变更路径。
- slave 与源服的 fingerprint 不一致。
- 缓存对象未达到对应 committedRevision。
- 某个操作类型没有完整 state resync plan，却试图在 slave 执行。
- slave 的 256M 堆自检未通过（内存预算未被证明）。

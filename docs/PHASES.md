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

master 为统筹中心（玩家不能直连），slave 为 Fabric 模组后端，单端口封装通道，backend 模组管源服。

- [x] 文档重定位：拓扑（玩家只能→slave→master→源服）、Auth Gateway 归位与会话记账、
      slave 形态（原生 MC 服务器 + Fabric 模组，不做世界计算）、资源约束（256M JVM、缓存落磁盘）、
      单端口封装通道（ChaCha20-Poly1305 + 公共密码 + UUID + RST）、源服 velocity 授信模式、
      serverName 缓存隔离、阶段体系重组（原阶段1 移除，本修正为新阶段1）。
- [x] 传输层重写：master 单端口封装通道（magic → 握手[公共密码+UUID] → ChaCha20-Poly1305；
      畸形/原版握手 RST；多路复用：控制帧 + 明文 MC 报文转发）；golden 测试全更新
      （EncapsulatedControlServer 已实现；MC_PASSTHROUGH 转发随阶段5 权威会话桥）。
- [x] control-service 并入 master（velocity-plugin 模块承载 control 包；独立 mTLS 监听与
      证书 CN 角色绑定已删除，仅剩单端口封装通道；PeerRegistry 按 AUTH UUID 绑定
      SLAVE/SOURCE；virtual-backend 连接层已迁封装通道客户端）。
- [x] backend 模组（fabric-source 演化）：连接层已从 mTLS 换成 velocity 授信模式（forwarding secret，
      内网，master 主动连接源服 backend 端口；BackendControlServer/SourceConnector 两侧实现）；
      serverName 注册（HELLO 携带，对象库分域）；数据包缓存基线规则随里程碑 5 权威桥；
      事件集合不变（WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/操作终态）。
- [x] slave Fabric 模组（新组件 slave-mod/）：原生 MC 服务器进程上接管游戏事件，只实现协议与部分
      预测，不做世界计算（不 worldgen/tick 模拟/持久化写回）；256M JVM 与磁盘缓存约束落地
      （SnapshotObjectStore 磁盘 + 注入即释放 + 自检）；封装通道客户端（公共密码+UUID，SLAVE 角色
      订阅/ACK/LOGIN_VERIFY 递交）；区块注入（scheduleChunkLoad/applyStep Mixin）与 fail closed。
- [x] Auth Gateway 归 master（里程碑 4 最小版 + 里程碑 5 完整化）：slave 终止登录握手后递
      验证材料，master 记账（SessionRegistry 重复会话拒新/踢旧/断开自动注销）+ Mojang 正版验证
      （26.3 hasJoined 复刻 + offline 开关）+ 断言 HMAC 签名（keyId 轮换）+ 票据单次兑换
      （nonce 重放拒）+ 吊销（SESSION_CLOSED/断开）；MasterVerifiedIdentity 增加 profileProperties。

验收：

- [x] 玩家只能经 slave 接入；直接连接 master 的任意流量被 RST；畸形/原版握手被 RST。
- [x] 同一玩家无法同时在多个 slave 建立会话（全局会话记账：SessionRegistry 重复会话拒新）。
- [x] slave 以 256M 堆启动并通过自检（不驻留区块、对象库落盘、静态数据懒加载；
      MixinTargetVerifier 堆告警）。
- [x] 文档中的拓扑/职责/约束与实现一致（AGENTS.md 组件归属同步）。

## 阶段 2：源服权威与快照（基线保持，修复期）

原阶段1 能力按新架构复验与维护：

- [ ] 新架构下源服 Mixin 目标、revision 持久化、快照发布与验证结论复验（无回归）。
- [ ] 源服游戏端口仅接受受信节点（master/受信接入）的隔离验证。

验收：

- [ ] 玩家、刻、命令、活塞、液体、世界生成、方块实体和模组修改仍递增正确 chunk 的 revision。
- [ ] 重启后 revision 不倒退；无法证明时产生新 epoch。

## 阶段 3：master 统筹控制平面（里程碑 5 完成；独立进程形态 ControlServiceMain）

- [x] 落点判定（RoutingPolicy 白名单/fallback）与多服务器切换（重复会话拒新/踢旧；会话模式
      缓存 ↔ 权威由 slave 发起 MC_PASSTHROUGH 流 + master 授权）。
      （master 单端口封装通道的会话转发：权威会话时 slave 明文 MC 报文 → master → 源服。）
- [x] 全局会话记账：验证通过登记、登出/断开注销、竞态两阶段确认（登记+落点原子）、
      重复会话拒新/踢旧策略。
- [x] Auth Gateway 验证流程（LOGIN_VERIFY_REQ/RES）与身份断言签发/单次兑换/吊销。
- [x] 健康检查与断路器：后端不可用时落点 fallback，不服务不确定数据。
- [x] 源服数据包缓存（PacketLog 审计 + 源服快照管道进对象库）：权威会话时源服返回的数据包
      在 master 侧缓存（形成快照供缓存会话）。
- [ ] Velocity 插件入口：独立进程形态完整；插件形态（velocity-api）需真实 Velocity 26.3
      环境，随阶段 6/7 端到端装配接线。

验收：

- [ ] 玩家可在源服与 slave 后端之间切换，身份断言随会话传递且不重放。
- [ ] 未经 master 验证的客户端无法进入任何后端；重复会话被拒绝。
- [ ] 后端故障时路由安全失败且不服务不确定数据。

## 阶段 4：slave 后端会话（Fabric 模组化）（里程碑 6 端到端验证：slave-server 真实装配）

- [x] slave 模组在原生 MC 服务器上实现 26.3 状态机与 committed snapshot 装载（迁移原阶段3 技术，
      移除独立 netty 进程依赖或保留为只读变体）。（真实装配：build/workspace/slave-server +
      256M 堆 + jar-in-jar；初始同步闸门 + setChunkForced 装载出生点快照）

- [ ] slave 模组在原生 MC 服务器上实现 26.3 状态机与 committed snapshot 装载（迁移原阶段3 技术，
      移除独立 netty 进程依赖或保留为只读变体）。
- [ ] 世界计算禁用：生成器禁用/替换、tick 模拟裁剪、持久化写回禁止；启动自检 fail closed。
- [ ] 256M 约束：磁盘对象库按需装载、编码即释放、静态数据懒加载、堆占用自检。
- [ ] 只开放旁观者模式，拒绝所有世界互动（与 master 验证/记账联动）。

验收：

- [ ] 玩家移动不建立源服游戏会话；缓存未命中/epoch/fingerprint 不匹配不展示旧区块。
- [ ] slave 不写入 committed snapshot 或源服世界；不产生本地生成区块。
- [ ] 256M 堆下稳定运行（含长时间浏览与切换）。

## 阶段 5：单格乐观操作（里程碑 7 完成）

- [x] 定义 operationId、virtualSessionId、client sequence、baseRevision 和状态哈希前置条件
      （协议定稿 CACHE_PROTOCOL §3；baseRevision 快速拒绝、preconditions 哈希为冲突键）。
- [x] slave 以可丢弃 overlay 存储预测（内存 Map，256M 最小增量），不修改 committed snapshot。
- [x] 源 Fabric 模组在主线程实现幂等操作队列和原子前置条件检查（OperationQueue + OperationAdjudicator）。
- [x] 只开放无容器、无实体、无流体、无红石、无跨 chunk 副作用的 BREAK/PLACE。
- [x] 实现 OPERATION_COMMITTED 和 OPERATION_REJECTED（含 canonical delta + resync 范围）。
- [x] 实现超时后的 GET_OPERATION_STATUS 和保守状态重同步（终态历史 + overlay 清除落回 committed
      snapshot；UNKNOWN → 丢弃全部预测 fail closed）。

验收：

- [x] 两人竞争同一方块时，源服只提交一个 operationId（OperationQueue 幂等 + OperationRoutingTest）。
- [x] 被拒绝玩家最终方块、方块实体、光照、库存和 interaction sequence 与源服一致（REJECTED 清除
      overlay 落回 committed snapshot——源服拒绝即世界未变）。
- [x] 重复、乱序和超时消息不会造成二次写入或幽灵 overlay（OperationQueueTest/OperationOverlayTest）。

## 阶段 6：扩大操作类型（延后，保持禁用）

- [ ] 为每种新增操作写出受影响状态集合、前置条件和 state resync plan。
- [ ] 按顺序评估 USE_BLOCK、库存/容器、桶、实体互动、红石/活塞、爆炸和模组自定义互动。
- [ ] 对跨 chunk、异步源服 tick 和多玩家争用建立集成测试。

验收（现状满足）：

- [x] 任何无法完整回滚的操作保持禁用（slave 对不支持类型直接拒绝，不发提交；阶段 8 真实玩家
      会话接线后按序评估）。
- [ ] 每种操作的源服 canonical result 均可按 operationId 审计（协议已支持；待类型扩大）。

## 阶段 7：故障、回归与性能（里程碑 7 部分完成）

- [x] 构建 26.3 golden snapshot、协议状态机和 Mixin 启动自检（协议 golden + MixinTargetVerifier）。
- [x] 消息重放/乱序（对象库单调守卫 + OperationQueue 幂等）、源服/控制服务重启（EventLog 容错 +
      源服 outbox 重发）、slave 故障（断连 fail closed 服务闸门）、操作超时（GET_OPERATION_STATUS）
      测试覆盖。
- [x] 快照到达刷新已装载区块（卸载重载 + 就地应用兜底）——修复里程碑 6 记录的故障场景限制。
- [ ] 模拟源服回档、网络分区、缓存损坏；压测多玩家移动、热点区块与高冲突操作；256M 堆压力测试
      （延后：需真实玩家负载，阶段 8）。
- [ ] 正确性通过后再优化热区索引、磁盘分层、压缩、批处理和预热。

验收：

- [x] 所有故障结果只有“权威最新状态”“完整权威回滚”或“停止使用不确定基线”（fail closed 语义
      贯穿：未命中/epoch/fingerprint/前置条件不匹配均拒绝或落回快照）。
- [ ] 性能优化关闭后仍保留全部一致性保证；256M 约束不因优化开启而失效（压测延后）。

## 阻塞条件

以下任一项未解决前，不应开放相应能力：

- 26.3 mappings 与 Mixin 目标未经实际验证。
- 存在未覆盖的 chunk dirty 或方块实体变更路径。
- slave 与源服的 fingerprint 不一致。
- 缓存对象未达到对应 committedRevision。
- 某个操作类型没有完整 state resync plan，却试图在 slave 执行。
- slave 的 256M 堆自检未通过（内存预算未被证明）。

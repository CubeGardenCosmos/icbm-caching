# ICBM Caching 文档索引

项目面向 Minecraft Java Edition 26.3：源后端运行 Fabric 权威模组，slave Fabric 模组后端提供缓存
世界会话（不做世界计算），master（统筹中心）负责连接策略、验证/记账与快照聚合。

本文档集刻意不包含插件或模组代码骨架。

- [ARCHITECTURE.md](ARCHITECTURE.md)：目标、master/slave 架构、缓存一致性、单端口封装通道与
  26.3 协议注意事项（设计基线）。
- [CACHE_PROTOCOL.md](CACHE_PROTOCOL.md)：master/slave/源服之间的控制协议（实现 v4：
  角色 SOURCE/SLAVE、HELLO serverName、封装通道线上布局、LOGIN_VERIFY 系列消息）。
- [HIERARCHY.md](HIERARCHY.md)：master/slave 拓扑、认证断言（Auth Gateway 归 master）与
  异步控制平面。
- [PHASES.md](PHASES.md)：按验收条件组织的阶段性任务（阶段1 架构修正：单端口封装通道 +
  backend/slave 双模组）。
- [VERSION_FREEZE.md](VERSION_FREEZE.md)：26.3 版本与指纹冻结清单（唯一来源）。
- [PARITY.md](PARITY.md)：与原版行为的一致性/有意差异记录。
- [EXECUTION_PLAN.md](EXECUTION_PLAN.md)：阶段1 落地执行计划与里程碑进度。

## 已确认的前提

- Minecraft 目标版本：26.3-snapshot-8（单一固定协议，见 VERSION_FREEZE.md）。
- 权威后端：源 Fabric 服务器（backend 模组，fabric-source/），唯一能提交世界写入、裁决冲突
  与发布快照的节点；经 velocity 授信模式接入 master。
- master（统筹中心）不是权威世界服务器；它负责验证/记账、落点判定/切换、快照聚合与身份断言
  签发，玩家不能直连。
- slave（Fabric 模组后端，slave-mod/）拥有客户端连接状态、committed snapshot 装载与有限预测层，
  但源 Fabric 服务器拥有最终世界状态与冲突裁决权。
- master 是唯一可信的缓存与身份断言来源；slave 只接受 master 的封装通道（公共密码 + UUID +
  ChaCha20-Poly1305）会话与已提交快照。
- 首个可用版本应固定单一 Minecraft 协议与相同的模组、数据包、注册表组合，不支持 ViaVersion 的
  跨版本缓存复用。

## 核心结论

当玩家仍连接源服时，源服会依据位置主动推送区块；客户端没有“请求某个区块”的独立包。因此，透明地
截获并缓存区块包并不能避免源服加载、生成或向代理发送新区块。

“只读时不从源服拉取新区块”的可验证实现是：玩家连接 slave（原生 MC 服务器进程 + Fabric 模组），
slave 以本地 committed snapshot 提供区块（未命中/落后/epoch 不匹配 fail closed，不展示旧区块）。
方块互动可以乐观执行并异步提交给源 Fabric 服务器；源服按自身主线程中的到达顺序原子裁决，返回
提交结果或权威回滚。Fabric 模组提供区块快照和单调 revision，保证 slave 不会把过期世界状态当作
可读状态。

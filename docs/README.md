# ICBM Caching 文档索引

项目面向 Minecraft Java Edition 26.3：源后端运行 Fabric 权威模组，独立虚拟后端提供缓存世界会话，Velocity 负责连接策略和路由。

本文档集刻意不包含插件或模组代码骨架。

- [ARCHITECTURE.md](ARCHITECTURE.md)：目标、虚拟后端架构、缓存一致性和 26.3 协议注意事项。
- [CACHE_PROTOCOL.md](CACHE_PROTOCOL.md)：Fabric 源服、虚拟后端、缓存服务和 Velocity 之间的控制协议草案。
- [HIERARCHY.md](HIERARCHY.md)：master/slave Velocity 拓扑、认证断言和异步控制平面。
- [PHASES.md](PHASES.md)：按验收条件组织的阶段性任务。
- [VERSION_FREEZE.md](VERSION_FREEZE.md)：26.3 版本与指纹冻结清单（唯一来源）。

## 已确认的前提

- Minecraft 目标版本：26.3。
- 权威后端：Fabric 模组服务器。
- Velocity 不是权威世界服务器；它只负责代理和路由。
- 虚拟后端拥有客户端连接状态和预测层，但源 Fabric 服务器拥有最终世界状态与冲突裁决权。
- master 是唯一可信的缓存与身份断言来源；各地区 slave 只接受 master 的签名断言和已提交快照。
- 首个可用版本应固定单一 Minecraft 协议与相同的模组、数据包、注册表组合，不支持 ViaVersion 的跨版本缓存复用。

## 核心结论

当玩家仍连接源服时，源服会依据位置主动推送区块；客户端没有“请求某个区块”的独立包。因此，透明地截获并缓存区块包并不能避免源服加载、生成或向代理发送新区块。

“只读时不从源服拉取新区块”的可验证实现是：玩家连接独立虚拟后端，虚拟后端以本地快照提供区块。方块互动可以乐观执行并异步提交给源 Fabric 服务器；源服按自身主线程中的到达顺序原子裁决，返回提交结果或权威回滚。Fabric 模组提供区块快照和单调 revision，保证虚拟后端不会把过期世界状态当作可读状态。

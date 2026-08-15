# ICBM Caching

面向 **Minecraft Java Edition 26.3**（冻结 26.3-snapshot-8）的缓存会话系统：源 Fabric 服务器是唯一
权威世界，slave Fabric 模组后端以 committed snapshot 提供缓存世界会话（不做世界计算），master
统筹中心负责验证/记账、落点判定与快照聚合。

```
玩家 ──(仅入口)──▶ slave（Fabric 模组后端：缓存会话，不做世界计算）
                    │  ▲  单端口封装通道（magic + AUTH + ChaCha20-Poly1305；控制/验证/MC 报文流）
                    ▼  │
master（统筹中心：Auth Gateway + 会话记账 + 快照聚合；玩家不能直连；畸形/原版握手 RST）
                    │  velocity 授信模式（forwarding secret，内网）
                    ▼
源服（backend Fabric 模组：唯一权威世界；脏标记/快照/revision/主线程裁决）
```

- **源 Fabric 服务器（`fabric-source/`）**：唯一能提交世界写入与裁决冲突的节点；产生
  WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/操作终态消息事件。连接层为 velocity 授信模式：源服
  模组监听 backend 端口（`icbm.backend.*`，PSK = forwarding secret），master 主动连接（无玩家
  在线也可用），方向与 slave 封装通道相反（源服发 HELLO，master 验证）。
- **master（`velocity-plugin/`，含已并入的 `cn.cubegarden.icbm.caching.control` 包）**：唯一信任根与
  决策中心；Auth Gateway（Mojang 正版验证 + 断言签名 + 票据单次兑换 + 吊销）、全局会话记账
  （防同一玩家加入多个 slave，重复会话拒新/踢旧/断开自动注销）、落点判定（白名单/fallback）+
  健康检查/断路器、权威会话桥（slave↔master↔源服 MC_PASSTHROUGH 字节透传 + 数据包缓存）、
  单端口封装通道、required/committed revision 快照聚合。独立进程形态入口 `ControlServiceMain`
  （Velocity 插件入口于阶段 6/7 端到端装配）。
- **slave（`slave-mod/`）**：原生 MC 服务器进程上的 Fabric 模组（只做协议与预测，不做世界计算）；
  初始同步闸门 + 出生点快照强制装载 + 乐观操作可丢弃 overlay + 快照到达刷新已装载区块
  对象库命中注入 FULL 快照区块（SerializableChunkData 解析），未命中/落后/epoch 不匹配 fail closed
  （EMPTY 不生成 + 玩家移动边界拦截）；旁观（互动拒绝）；禁持久化；SLAVE 角色封装通道客户端
  （订阅/ACK/LOGIN_VERIFY 递交）；256M JVM 约束；F3 品牌可配置（icbm.brandLocation，
  默认 ICBM Caching Server，配置后如 ICBM Caching Server (HongKong:Kowloon)）。
  `virtual-backend/` 保留为只读变体参考。
- **`protocol/`**：跨组件共享控制协议（包络/消息编解码/对象库/封装通道层，纯 Java 25）。

## 开发环境

统一使用仓库内固定路径开发环境（Git 忽略），由 Python 工具管理（uv）：

- `uv run build/main.py init`：部署 JDK 25、克隆参考仓库（Fabric/Velocity）、部署测试服务器、
  反编译 26.3-snapshot-8 服务端源码（行为分析最高权重参考）。
- `uv run build/main.py test`：运行全部组件测试（protocol/velocity-plugin/virtual-backend/fabric-source/slave-mod）。
- `uv run build/main.py server|control|reinit|cleanup`：测试服务器 / master 控制服务（封装通道
  11420，生成 PSK 与 UUID 角色绑定）/ 破坏性重置与清理（见 `build/README.md`）。
- `uv run build/main.py debug-run`：交互式三进程 debug 实例（`build/workspace/server` 下
  source/master/slave + jre；TUI 三窗口，Shift+Tab 切换；slave 监听 0.0.0.0:25565、区域
  `icbm:lan`；配置由 python 覆盖：正版验证/创造模式/32 视距，源服正常地形生成器 + 固定 seed）。

硬约束：新增功能必须先完成 `build/workspace/draft/<功能名>` 三步草稿（参考仓库 → 官方源码 →
差异取舍）；协议变更必须「文档 + 两侧实现 + golden 测试」同一提交；版本冻结见 `docs/VERSION_FREEZE.md`。
完整约定见 [AGENTS.md](AGENTS.md)。

## 文档

- [docs/README.md](docs/README.md)：文档索引与已确认前提
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：架构与设计基线
- [docs/CACHE_PROTOCOL.md](docs/CACHE_PROTOCOL.md)：控制协议（实现 v4）与封装通道线上布局
- [docs/HIERARCHY.md](docs/HIERARCHY.md)：master/slave 拓扑与认证边界
- [docs/PHASES.md](docs/PHASES.md)：阶段性任务与验收条件
- [docs/EXECUTION_PLAN.md](docs/EXECUTION_PLAN.md)：阶段1 落地执行计划
- [docs/VERSION_FREEZE.md](docs/VERSION_FREEZE.md)：26.3 版本与指纹冻结（唯一来源）
- [docs/PARITY.md](docs/PARITY.md)：与原版行为的一致性/有意差异

## 版本

软件版本号（大版本.阶段.bug修改版本）记录在仓库根 [VERSION](VERSION)。

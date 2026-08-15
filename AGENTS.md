# icbm-caching 仓库开发约定

本文件是仓库内所有开发（含 AI 与人类开发者）的硬约束。项目目标、架构与阶段顺序以 [docs/README.md](docs/README.md) 与 [docs/PHASES.md](docs/PHASES.md) 为当前来源；core/region 通讯、缓存一致性等设计基线见 docs/ 其余文档。

## 项目范围

- 项目为 **ICBM Caching**：面向 Minecraft Java Edition 26.3 的缓存会话系统。源后端运行 Fabric 权威模组，独立虚拟后端提供缓存世界会话，Velocity 负责连接策略和路由；master 缓存目录是快照聚合与身份断言信任根。
- 固定单一 Minecraft 协议与相同的模组、数据包、注册表组合：当前冻结 **26.3-snapshot-8**（见 [docs/VERSION_FREEZE.md](docs/VERSION_FREEZE.md)）。不支持 ViaVersion 的跨版本缓存复用；非冻结版本客户端确定性拒绝。
- 组件职责边界以 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 为准：源 Fabric 模组是唯一能提交世界写入与裁决冲突的节点；master/slave 拓扑与身份断言以 [docs/HIERARCHY.md](docs/HIERARCHY.md) 为准；控制协议以 [docs/CACHE_PROTOCOL.md](docs/CACHE_PROTOCOL.md) 为唯一来源。
- 以可观察行为为移植目标：状态变化、数据包、事件顺序、存档结果与 26.3 对齐；不得凭记忆或 1.21.x/26.2 的常量臆测 26.3 行为。
- 本文档集刻意不包含插件或模组代码骨架（见 docs/README.md）；组件代码位于各自顶层目录（见“命名与目录归属”）。

## 开发环境

- 所有开发人员统一使用仓库内固定路径 `build/workspace/` 下的开发环境（Git 忽略）。
- 运行 `python3 build/main.py init`（推荐 `uv run build/main.py init`）完成环境就绪，步骤与产物：
  1. 部署 JDK 25（Adoptium 官方源失败自动降级清华 TUNA 镜像）到 `build/workspace/jdk-25`。
  2. 克隆参考仓库（FabricMC/fabric 的 26.3 分支、PaperMC/Velocity master，非子模块）到 `build/workspace/fabric` 与 `build/workspace/velocity`，只读使用。
  3. 下载并部署测试服务器：Mojang 官方 26.3-snapshot-8 server + Fabric Loader 0.19.3，安装位置 `build/workspace/server`。
  4. 用 Vineflower 反编译 Mojang 官方 26.3-snapshot-8 服务端 jar（官方 mojmap，无需 yarn/intermediary）到 `build/workspace/minecraft-assets`，只作行为分析参考；不得把 Mojang/Fabric/Velocity 源码、注释或资源复制进本仓库。
  5. 运行 `fabric-source` 的 `./gradlew build` 预热编译缓存（成败不阻断）。
  - init 末尾询问是否安装基础软件（Rust 工具链、Node、pi AI 工具）。
- Python 工具由 uv 管理（`build/pyproject.toml`、`build/uv.lock`）；功能按分层拆分在 `build/icbm_dev_env/` 包内（入口 → 编排 → 领域功能 → 基础设施），`build/main.py` 只做 uv 自举与 CLI 分发，禁止向 `main.py` 堆逻辑。
- 所有联网操作（git/urllib/Gradle/curl）遵循 `HTTPS_PROXY`/`HTTP_PROXY` 环境变量；Gradle 步骤由脚本翻译为 JVM 代理参数。
- 测试服务器：`build/main.py server` 启动 `build/workspace/server` 的 Fabric 服务器（测试用，独立世界目录），`build/main.py test` 运行全部组件测试（protocol/control-service/velocity-plugin/fabric-source）；`build/main.py control` 前台启动控制服务（端口 11420，mTLS 证书由 `build/main.py certs` 生成到 `build/workspace/certs`）。
- 环境重置与清理：`reinit`（删除 `build/workspace` 后重新完整部署，适合反编译结果被污染时）与 `cleanup`（删除 `build/workspace` 与各组件构建产物，腾出硬盘空间）均为破坏性操作，脚本会先请求确认。
- `build/workspace/`、`build/.venv/`、各组件 `build/`、`.gradle/`、`run/` 目录被 Git 忽略。
- 开发环境改动（如升级 Loom/Gradle/JDK 版本）需同步更新 `docs/VERSION_FREEZE.md`、`build/` 脚本与本文档。
- 如果用户的提示词输入了一个 Git 仓库，你应该同步新建一个 `build/main.py` 的模块与步骤，作为 init 的一环。用户引用的 Git 仓库必须被 clone 到 `build/workspace/`。

## 参考基线（最高权重）

- **Minecraft 26.3-snapshot-8 反编译源码（`build/workspace/minecraft-assets`，由 `build/main.py init` 生成）是行为一致性的最高权重参考**：协议包字段与编码、NBT 结构、ChunkSerializer 格式、PalettedContainer 规则、注册表数据、区块脏标记与保存路径等一切可观察行为，实现时必须以源码为准频繁对照，不得凭记忆或二手资料臆测。
- 源码与本文档冲突时以源码为准，并同步修订本文档。
- 每个依赖源码结论的实现点应在代码注释中标注对应的源码类/方法（如 `ChunkSerializer.java`、`LevelChunk.java`、`ChunkMap.java`）。
- docs/ 设计文档（ARCHITECTURE/CACHE_PROTOCOL/HIERARCHY/PHASES）是第二权重参考；[docs/VERSION_FREEZE.md](docs/VERSION_FREEZE.md) 是版本与指纹快照的唯一来源。

## Git 原子提交

- 一个可独立验证的功能只能出现在一个提交中；不得混入构建产物、格式化噪声或无关改动。
- 提交消息使用英文类型前缀 + 中文说明，例如 `Docs: 记录26.3版本冻结`、`Test: 覆盖chunk revision重启不倒退`、`Feat: 源模组发布committed快照`。
- 删除或重构前先建立测试基线；功能代码、对应测试与必要文档在同一原子提交中完成。
- 提交前运行与改动范围相称的检查，并运行 `git diff --check` 与 `git diff --cached --check`。
- 提交后检查工作树；若存在他人改动，必须保留、避开并在交付说明中指出。
- 不执行破坏性 Git 操作，不重写他人提交，不自动推送远端。

## 新功能草稿流程（硬约束）

**在增加任何新功能之前，必须在 `build/workspace/draft` 完成草稿**，草稿顺序固定为：

1. **先直接原样获取参考仓库的做法**（`build/workspace/fabric` 与 `build/workspace/velocity`，只读参考，原样照搬对应功能/协议/状态机的实现思路与结构，不擅自改动）。
2. **再对照官方 Minecraft 服务器的做法**（`build/workspace/minecraft-assets` 反编译源码，以 26.3-snapshot-8 行为为准）。
3. **对比二者不同**，用参考仓库的做法修改为**兼容原版特性**的做法——即以参考实现为骨架，将其与官方行为有出入的地方修正为符合原版可观察行为；**不得采用与 icbm 所有权模型冲突的做法**（如把世界操作裁决移出源 Fabric 主线程、依赖不可靠的接收时间判胜负）。

该流程适用于所有智能体（AI 与人类开发者），是硬约束：未在 `build/workspace/draft` 完成三步草稿不得进入实现阶段；草稿结论（参考仓库做法、官方做法、差异与取舍）须记录在 `build/workspace/draft/<功能名>/README.md`。

## 命名与目录归属

- 组件目录归属是架构约束，不是建议，第一方组件使用 `icbm-` 前缀；Java 包统一以 `cn.cubegarden.icbm.caching` 为根（与仓库/项目身份一致），按组件分子包：
  - `fabric-source/`：源服 backend Fabric 模组（Java；包 `cn.cubegarden.icbm.caching.source`，模组 ID `icbm_source`；
      管理源服务器并产生消息事件：脏标记、快照、revision、主线程裁决；连接层用 velocity 授信模式）。
  - `slave-mod/`：slave Fabric 模组后端（阶段1 修正新增，原生 MC 服务器进程上接管游戏事件，只实现协议与部分预测，
      不做世界计算；256M JVM / 磁盘缓存约束；封装通道客户端）。
  - `velocity-plugin/`：master 统筹中心 Velocity 插件（包 `cn.cubegarden.icbm.caching.velocity`；单端口封装通道服务、
      落点判定/切换、会话记账联动、健康检查）。
  - `virtual-backend/`：阶段3 已实现的独立 26.3 旁观会话后端（包 `cn.cubegarden.icbm.caching.virtual`）；
      修正后作为 slave-mod 的技术基础（只读变体参考；Nbt/对象库/ServeGate 逻辑迁移入 slave-mod）。
  - `control-service/`：并入 master（包 `cn.cubegarden.icbm.caching.control`；对象库/事件日志/订阅逻辑成为 master 内部模块，不再独立监听端口）。
  - `auth-gateway/`：Mojang 正版验证组件（归 master 受信面：master 内实现验证 + 全局会话记账）。
  - `protocol/`：跨组件共享控制协议（包 `cn.cubegarden.icbm.caching.protocol`，纯 Java 25，netty/slf4j 由消费方提供；含包络/消息编解码、SnapshotObjectStore、ControlChannel 客户端基类）。
  - `docs/`：设计与协议文档；`build/`：Python 开发环境工具。
  - 跨组件共享的控制协议消息类型放 `cn.cubegarden.icbm.caching.protocol`（单侧实现，两侧复用，不得另写副本）。
- 源模组（backend）只做权威世界侧工作（脏标记、快照、revision、主线程裁决、消息事件）；不得在源模组内实现客户端会话、slave 后端或缓存对象库职责（见 ARCHITECTURE.md 的“必须负责/不得负责”表）。
- slave（含原 virtual-backend 演进）不做世界计算：不 worldgen、不 tick 模拟世界、不持久化写回；未命中/落后/epoch 不匹配时 fail closed 不展示旧区块；以 256M JVM 与磁盘缓存为设计约束（见 ARCHITECTURE.md §1.2/§3.1）。
- slave↔master 传输：单端口封装通道（ChaCha20-Poly1305 + 公共密码 + 每 slave UUID；不使用 TLS；畸形/原版握手 RST）；源服↔master 用 velocity 授信模式（内网，master 主动连接）。PSK 内网信任边界，运维手册禁止弱公共密码（见 ARCHITECTURE.md §1.1 / CACHE_PROTOCOL.md §1）。
- 通讯协议变更必须同步修改 `docs/CACHE_PROTOCOL.md`；新增消息 type 必须在同一提交中更新文档与两侧实现。
- 不得机械替换 `minecraft:*`、原版 NBT 键、协议字段、Mojang 类型名或第三方项目名；26.3 常量必须来自 `build/workspace/minecraft-assets` 或实际字节码验证，禁止复制 26.2/1.21.x 的常量。

## 代码质量：禁止面条代码

- **单一职责**：模块/类职责单一；禁止“上帝对象”。RevisionStore、DirtyTracker、SnapshotPublisher 等核心类型按域拆分（持久化、脏标记、序列化、发布分开）。
- **小函数**：优先短函数与明确命名；避免超过一屏的嵌套分支/循环。复杂流程用中间结构体或子函数拆分，不为“少写几行”牺牲可读性。
- **显式状态机**：源模组提交流程（dirty → requiredRevision → PUT_SNAPSHOT → committedRevision）、操作裁决（SUBMIT → COMMITTED/REJECTED）必须用显式状态机表达，禁止靠散落的布尔标志推断状态。
- **无全局可变状态**：禁止静态可变容器与隐藏的全局可变状态；跨线程数据必须经所有权边界或明确的锁/通道传递。
- **错误处理**：不以 `unwrap`、静默 fallback 或吞错方式掩盖协议、存档或注册表不一致；错误要有类型与上下文，向调用方显式传播。缓存命中在 Mixin 目标缺失时必须禁用而不是静默降级（fail closed）。
- **避免重复实现**：同一格式（NBT、快照、协议消息）只在一处实现；组件间通过 `docs/CACHE_PROTOCOL.md` 定义的消息交换，不在两侧另写不兼容副本。

## 协议、存档与数据约束

- 新增协议代码只能针对冻结版本 26.3-snapshot-8，不得引入运行时版本分支。
- 固定版本快照：协议 1073742154、DataVersion（world_version）5010、资源包格式 96、数据包格式 116、Java 25、Fabric Loader 0.19.3、Fabric API 0.157.1+26.3；见 `docs/VERSION_FREEZE.md`。存档与网络各层不得混用其它版本号。
- 包 ID、字段布局、registry、tag、tracked data、data component、实体 metadata 必须来自同一 26.3-snapshot-8 基线。
- 控制协议以 `docs/CACHE_PROTOCOL.md` 为唯一来源：包络（schemaVersion/messageType/requestId/sourceServerId/worldEpoch/sequence/sentAt/body）、HELLO 指纹、快照消息（WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/RESOLVE_CHUNK）与操作消息（SUBMIT_OPERATION/OPERATION_COMMITTED/OPERATION_REJECTED/GET_OPERATION_STATUS）。schema、认证、epoch、序号或哈希校验失败时，接收方关闭缓存命中或操作通道，不猜测兼容格式。
- 持久缓存只保存版本化的区块 NBT 快照与元数据，不保存加密的客户端网络帧；Anvil 的 .mca 是磁盘 region 格式，不等于客户端 Chunk Data and Update Light 包。
- 修改生成数据时优先修改生成器与固定输入再重新生成，不手工修补生成文件。

## 原版一致性与运行时约束

- 以可观察行为为移植目标：状态变化、数据包、事件顺序、存档结果、命令反馈与 26.3 对齐；参考反编译源码（官方 mojmap 命名）分析行为，用独立实现并以测试证明。
- **源 Fabric 服务器是唯一权威**：先到者以源服主线程中通过前置条件校验并成功提交的顺序定义，不得以虚拟后端、Velocity 或 TCP 套接字的接收时间决定胜负。
- **虚拟后端预测永不信任**：源服必须重算结果；禁止直接信任虚拟后端给出的“方块应该变成什么”。
- **fail closed**：缓存或控制通道故障时停止使用不确定基线；不得服务过期或未验证数据。
- 每项“原版一致”结论关联单元测试、golden test、快照或可复现实验；有意差异记录到 parity matrix。
- 游戏状态只可在模拟/tick 所有权边界内变更；网络、认证、文件 I/O、压缩、哈希不得阻塞源服主线程 tick 热路径。
- 随机行为使用正确的世界/实体随机源，保证固定种子测试可复现。

## 验证要求

- 文档或仓库约定改动：至少 `git diff --check` 与 `git diff --cached --check`。
- Java 组件改动：至少受影响模块的 Gradle 构建、单元测试与 `git diff --check`；公共 API 或跨组件改动运行全组件测试。
- 源模组改动（revision/快照/裁决）：worldEpoch 与 chunk revision 持久化的 round-trip 测试、dirty 合并与邻居不递增测试、重启后 revision 不倒退测试、Mixin 目标启动自检测试。
- 协议改动：`docs/CACHE_PROTOCOL.md` 消息的 golden 序列化测试 + 两侧集成测试。
- 依赖清理后运行未使用依赖检查；不提交 `target/`、Gradle 缓存、反编译源码、临时下载或本机专用配置。

## 版本号管理

- 软件版本号命名规则为 大版本.阶段.bug修改版本；例：1.2.2 代表第一个大版本，第二个阶段，第二次修复。
- 版本号记录在仓库根 `VERSION` 文件；每次提交前修改版本号（与改动相称的递增），并在提交信息中附上 Git 提交记录（上一条提交的短 hash 与主题）。

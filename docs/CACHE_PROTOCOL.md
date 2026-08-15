# ICBM 控制协议

状态：实现 v3（阶段2 后台控制协议落地；草稿 `build/workspace/draft/phase2-control-protocol`）；
     阶段1 架构修正新增 §9 登录验证草案（未实现，随两侧同一提交落地）
参与方：源 Fabric 权威模组、master（统筹中心 + Auth Gateway + 缓存目录）、slave（Fabric 模组后端）
非目标：替代客户端与 Minecraft 后端之间的标准 26.3 游戏协议

## 1. 传输、安全与包络

主控制通道使用独立后台连接（源→master、slave→master 均为 mTLS 长连接），不依赖玩家 Minecraft
连接。mTLS 证书 CN 绑定角色（`control.roleBindings`），源与 slave 使用独立证书；slave 之间
不互信（slave 只与 master 对话，slave 证书无法以 SOURCE 角色发送写入消息）。请求大小限制
（单帧上限可配置，默认 16 MiB；PUT_SNAPSHOT payload 单独上限 64 MiB）、连接速率与审计日志
由控制服务实施。只有源服对世界操作的最终裁决进入源 Fabric 的主线程；复制、订阅、失效与
快照均不依赖 Minecraft tick。

> 端口规划（待定项，阶段1 修正）：当前为独立 mTLS 受信端口；“打包进 25565 游戏端口”为备选
> 方向，因安全隔离（控制面与公网玩家入口混流）、流量干扰与审计因素尚未定案；定案后同步
> 更新 HIERARCHY §6 与本节。玩家流量不经过 master；登录验证材料经受信控制通道递交 master
> （见 §9），不进入游戏端口。

每条消息使用二进制包络（golden 序列化测试锁定线上字节布局）：

    帧： [4字节大端长度][包络体]
    包络体： ICBM magic(4) + schemaVersion(1) + messageType(1) + requestId(8)
           + sourceServerId(2字节长度+UTF-8) + worldEpoch(8) + sequence(8)
           + sentAt(8) + bodyLength(4) + body(bodyLength 字节)

- `schemaVersion` 当前为 1。schema/认证/epoch/序号/哈希校验失败时，接收方关闭缓存命中或
  操作通道（fail closed），不猜测兼容格式。
- `sequence` 在同一连接发送方向严格递增：master 校验源→master 入站序号（gap/重放 → ERROR +
  断连）；master→slave 推送使用事件日志序号（跨连接单调，供 ACK 水位）；HEARTBEAT 不受
  序号约束（保活探测，不承载有序状态）。跨连接连续性由对象库持久化 required/committed
  单调性与幂等操作保证（控制服务重启不持久化序号）。
- `worldEpoch`：源→master 消息携带源侧当前维度 epoch（PUT_SNAPSHOT 的 epoch 校验来源）；
  master→slave 推送携带事件所属维度当前 epoch。

HELLO 包还必须携带：

    role: SOURCE | VIRTUAL_BACKEND | VELOCITY
    minecraftVersion: 26.3
    runtimeProtocol
    fabricLoaderVersion
    fabricApiVersion
    modpackFingerprint
    registryAndDatapackFingerprint
    capabilities

指纹不一致、证书 CN 绑定角色与 HELLO.role 不一致、版本/runtimeProtocol 不匹配时，控制服务
拒绝连接（HELLO_ACK accepted=false）与快照复用。slave/虚拟后端的指纹必须与已注册源的指纹一致。
阶段3 起虚拟后端以 VIRTUAL_BACKEND 角色订阅并维护本地对象库（与 slave 同协议），装载前按
§6 服务闸门判定（master 连接存活 + committed≥required + epoch 匹配），否则不展示旧区块（fail closed）。

## 2. 快照消息

### WORLD_EPOCH

    dimensionId
    worldEpoch
    activationReason: START | ROLLBACK | MODPACK_CHANGE | ADMIN
    registryAndDatapackFingerprint

新 epoch 立即使对应维度的旧对象不可读（对象可读 ⇔ 对象 epoch == 维度当前 epoch）。

### INVALIDATE

    dimensionId
    chunkX
    chunkZ
    revision
    reason: BLOCK_STATE | BLOCK_ENTITY | CHUNK_SAVE | WORLDGEN | ADMIN | UNKNOWN

控制服务先提高该区块 requiredRevision（仅增，max 语义，重放幂等），使低 revision 对象不能
作为虚拟后端基线。

### PUT_SNAPSHOT

    dimensionId
    chunkX
    chunkZ
    revision
    snapshotFormat: NBT_CHUNK_V1
    compression: GZIP
    uncompressedLength
    payloadHash
    payload

控制服务按序校验：格式白名单 → revision==当前 required（防乱序/重放）→ epoch==维度当前
epoch → payloadHash==SHA-256(payload) → uncompressedLength==解压后长度 → 重放守卫
（同 revision 目录已存在且 hash 不同 → 拒收，绝不覆盖新对象）。校验通过后持久化对象并以
**单一原子操作**写入 committedRevision，随后追加事件日志并向订阅 slave 推送。

### PUT_SNAPSHOT_ACK

    dimensionId / chunkX / chunkZ / revision
    result: OK | REJECTED_OUT_OF_ORDER | REJECTED_EPOCH_MISMATCH | REJECTED_HASH_MISMATCH
          | REJECTED_SIZE_MISMATCH | REJECTED_UNSUPPORTED | REJECTED_UNAVAILABLE

任意拒绝都保持 required（fail closed）；OK 意味着服务端已原子提交（commit 随 put 完成）。

### RESOLVE_CHUNK

请求：

    dimensionId / chunkX / chunkZ
    expectedProtocol
    registryAndDatapackFingerprint
    minRevision

响应：

    dimensionId / chunkX / chunkZ
    status: HIT | MISS | STALE | INCOMPATIBLE | UNAVAILABLE
    committedRevision
    objectId
    objectHash

判定：protocol/fingerprint 不匹配 → INCOMPATIBLE；服务关闭或源未注册 → UNAVAILABLE；
committed<required 或 committed<minRevision → STALE（INVALIDATE 到 committed 之间绝不
返回旧 HIT）；epoch 不匹配 → INCOMPATIBLE；其余 → HIT。只有 HIT 可被虚拟后端装载为
committed snapshot 基线。

## 3. 乐观操作消息

（阶段 5 实现；消息定义不变）

### SUBMIT_OPERATION / OPERATION_COMMITTED / OPERATION_REJECTED / GET_OPERATION_STATUS

见原草案字段。虚拟后端发给源 Fabric 模组；源服以 operationId 去重并只在其主线程判定总顺序。

## 4. 管理与复制（阶段2 新增）

### ADMIN_INVALIDATE

    dimensionId / scope: CHUNK | REGION | DIMENSION | WORLD
    chunkX / chunkZ / regionX / regionZ / reason

按范围将该维度已知区块 required 提高一级并记录审计原因（对象库枚举 + 事件日志）。

### SUBSCRIBE

    dimensionIds[]
    pushEnabled
    ackedSequence

slave 注册订阅；`ackedSequence` 为断线前已确认的事件序号。pushEnabled 时 master 重放
事件日志中 seq > ackedSequence 的全部条目（WORLD_EPOCH/INVALIDATE/SNAPSHOT_COMMITTED，
即断线补发、范围失效与 epoch 切换全部经日志送达），随后实时推送。

### SNAPSHOT_PUSH

与 PUT_SNAPSHOT 同 body；master→slave 已提交对象（包络序号 = 事件序号，包络 epoch = 对象
epoch）。slave 应用前同样过对象库单调守卫（乱序/重放拒绝，不降低 revision、不覆盖新对象）。

### ACK

    ackedSequence

slave 确认已收到的事件序号；master 以此推进流控窗口（未确认 in-flight 达到 pushWindow
时暂停新推送并排队）。滞后检测：slave 超过 lagThreshold 未 ACK → 标记 LAGGING（记录日志，
推送被窗口自然限流）。

### RESYNC_REQ / RESYNC_RES

    dimensionId / fromRevision

日志裁剪后的兜底路径：slave 请求维度内 committed >= fromRevision 的全部已提交对象，
master 以 SNAPSHOT_PUSH 重放并返回 RESYNC_RES（lastCommittedRevision / replayedCount）。

### HEARTBEAT

master 周期发送（保活）；slave 以 ACK 应答。HEARTBEAT 不受包络序号递增约束。

### ERROR

    reason

协议违规时接收方发出并关闭通道。

## 5. 事件日志与持久恢复

- master 追加写事件日志（WORLD_EPOCH / INVALIDATE / SNAPSHOT_COMMITTED，跨连接单调序号），
  供 slave 重连重放；启动恢复时全量读入，不完整尾部容忍截断，中段损坏拒绝加载（fail closed）。
- 控制服务重启后从持久 requiredRevision / committedRevision（对象库目录）恢复，不依赖内存
  队列（对象库损坏判定：committed>required 或 committed 对象缺失/元数据不可解析 → 该区块
  fail closed）。
- 源端重连：outbox 保留未确认的 INVALIDATE/PUT_SNAPSHOT 并按序重发（INVALIDATE 幂等 max；
  PUT 由服务端 required/hash 重放守卫幂等）；发布器 begin 在控制通道不可用时失败并保持
  dirty 重试（fail closed）。

## 6. slave 服务闸门

slave 只在满足以下全部条件时把本地对象作为虚拟后端基线（`ServeGate`）：

1. 与 master 的连接存活（否则拒绝一切——无法证明 required 水位最新）；
2. 存在非损坏的 committed 对象；
3. committed >= master 要求的 requiredRevision（否则落后，不得服务低于 master required 的区块）；
4. 对象 epoch == 维度当前 epoch。

## 7. Fabric Custom Payload 的位置

不变：Custom Payload channel 仅用于玩家会话内可选功能（独占命名空间、版本化 codec、大小限制、
能力协商），不承担后台 snapshot、invalidate 或 operation 主通道。

## 8. HELLO minecraftVersion

控制服务校验 HELLO.minecraftVersion 为 26.3 系列（前缀匹配，完整版本名如 “26.3 Snapshot 8”
与快照 id 均接受）；精确版本由 runtimeProtocol 锁定（1073742154）。

## 9. 登录验证与会话记账（阶段1 修正草案，未实现）

背景：Auth Gateway 归 master（HIERARCHY §2/§3）。slave 终止 Minecraft 登录握手（RSA 密钥在
slave），把验证材料经受信控制通道递交给 master；master 是唯一执行 Mojang 正版验证的组件，
验证通过后登记全局活跃会话表（同一玩家 UUID 同时只允许一个会话，防玩家加入多个 slave）并签发
身份断言。

草案消息（消息 type 与字段在实现时冻结并做 golden 测试；按 AGENTS.md 约定，新增消息 type 必须
在同一个提交中更新本文档与两侧实现）：

### LOGIN_VERIFY_REQ（slave → master）

    playerName / playerUuid / profileProperties
    serverHash / sharedSecret（加密材料，供 master 调用 session server 验证）
    slaveId / connectionInfo / nonce / sentAt

### LOGIN_VERIFY_RES（master → slave）

    VERIFIED（含 MasterVerifiedIdentity 断言：issuer/keyId/audience/expiresAt/sessionId/nonce/signature）
    | REJECTED（reason：验证失败 / DUPLICATE_SESSION（已存在于其它 slave）/ 黑名单 / 吊销）

### SESSION_CLOSED（slave → master）

    playerUuid / sessionId / reason

master 注销记账（防会话表泄漏）；玩家断开、切换落点、后端故障均需上报。

约束：

- 验证请求走 mTLS 受信通道，不经游戏端口；不进入 Minecraft 登录协议本身。
- master 记账与落点判定的竞态（验证通过→玩家真正进入后端之间）需两阶段确认或持有锁，避免双通过。
- offline 模式（开发/测试）：master 配置开关，verify=false 时直接放行但仍记账（沿用测试服务器惯例）。
- 重复会话策略（拒新 / 踢旧）与“源服 + 一个缓存后端同时在线”是否允许，由部署策略定案。

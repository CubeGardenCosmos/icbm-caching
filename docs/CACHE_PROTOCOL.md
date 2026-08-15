# ICBM 控制协议

状态：实现 v4（阶段1 架构修正：角色重定义为 SOURCE/SLAVE、HELLO 增加 serverName、
     LOGIN_VERIFY_REQ/RES 与 SESSION_CLOSED 落地，随两侧同一提交；草稿
     `build/workspace/draft/encapsulated-channel`）；封装通道传输层见 §1
参与方：源 Fabric 权威模组、master（统筹中心 + Auth Gateway + 缓存目录）、slave（Fabric 模组后端）
非目标：替代客户端与 Minecraft 后端之间的标准 26.3 游戏协议

## 1. 传输、安全与包络

slave↔master 使用**单端口封装通道**（master 只监听一个端口，可被简单转发）：

- 首字节 magic 匹配进入封装握手；畸形或原版握手立即 **RST**；
- 鉴权 = 共享公共密码 + 每 slave UUID（明文、可打印日志）；不使用 TLS；
- 会话加密 = **ChaCha20-Poly1305**（JDK 内置，防篡改/重放）；
- 承载：登录验证材料 / 身份断言 / 落点与切换指令 / 快照与失效 / 操作提交 /
  （权威会话时）明文 Minecraft 报文转发；
- PSK 内网信任边界：知道公共密码者可读取/伪造封装流量（运维手册禁止弱公共密码，
  UUID 仅身份标签与审计）。

### 1.1 封装通道线上布局（golden 测试锁定，草稿 `build/workspace/draft/encapsulated-channel`）

   明文层（握手期）：[magic "ICBM"(4)][length(4 大端)][payload]
     payload = AUTH / AUTH_ACK（仅握手期明文）

   加密层（ACTIVE 后）：[magic "ICBM"(4)][length(4 大端，= ciphertext 长)][ciphertext]
     ciphertext = ChaCha20-Poly1305([streamId(1)][body]，AAD=magic‖length)
     streamId：CONTROL(0) / LOGIN_VERIFY(1) / MC_PASSTHROUGH(2)
     body（CONTROL）= 下述包络体；body（MC_PASSTHROUGH）= [流序号(8)][长度(4)][明文 MC 报文字节]

- AUTH：`[authTokenLen(2)=16][authToken(16)][uuid(16)][clientNonce(16)]`，
  `authToken = HMAC-SHA256(公共密码, "ICBM-AUTH" ‖ uuid ‖ clientNonce)` 前 16 字节；
- AUTH_ACK：`[accepted(1)][serverNonce(16)][reason(2+utf8)]`，accepted=false 后 master 立即关闭；
- 会话密钥：`sessionKey = HKDF-SHA256(公共密码, salt=clientNonce‖serverNonce‖uuid,
  info="icbm-session")` 派生为两个 32 字节方向密钥；nonce = 8 字节大端帧计数器 + 4 字节
  方向常量（client→server=1 / server→client=2），双向密钥不同 ⇒ nonce 永不碰撞；
- 帧长度上限 `MAX_ENCAPSULATED_FRAME_BYTES`（硬上限，超限/负长度 → RST，防放大攻击）；
- 连接首字节非 magic（原版握手/畸形/扫描）→ master 立即 RST（SO_LINGER=0，不读剩余数据）。

源服↔master 使用 **velocity 授信模式**（modern forwarding secret，内网，master 主动连接），
不经封装通道；承载快照/失效/epoch/操作终态消息事件（无玩家在线时也可用）。

### 1.2 源服 backend 通道（velocity 授信，里程碑 3；草稿 `draft/backend-velocity-trust`）

- 方向：**master 主动连接**源服 Fabric 模组监听的 backend 端口（`icbm.backend.*`）；
  无玩家在线也可用（独立受信长连接，不依赖玩家会话）。
- 传输：复用封装通道传输层（magic + AUTH 帧 + HKDF + ChaCha20-Poly1305 + CONTROL 流），
  **PSK = velocity forwarding secret**（velocity.toml `forwarding-secret`）；不使用 TLS。
- AUTH：master 为客户端，`token = HMAC-SHA256(secret, "ICBM-AUTH" ‖ masterUuid ‖ clientNonce)`
  前 16 字节（与 §1.1 同机制）；源服校验失败 → AUTH_ACK(false) 关闭（fail closed）。
- 握手方向与 slave 通道相反：**源服（服务端）主动发 HELLO**（role=SOURCE + sourceServerId +
  serverName + 指纹 + runtimeProtocol；包络 sourceServerId 为源服标识），master 验证并回
  HELLO_ACK（accepted/reason）；源服 HELLO 校验失败（指纹/runtimeProtocol/版本不匹配）→
  HELLO_ACK(false) 并关闭，master 退避重连。
- 事件流不变：源服发 WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT（包络序号自增，master 防重放），
  master 回 PUT_SNAPSHOT_ACK/ERROR；源服定期 HEARTBEAT（master 读超时误断防护）。
- 无玩家时事件可达：master 主动连接 + 断线重连，事件经 master 对象库/事件日志推 slave。

只有源服对世界操作的最终裁决进入源 Fabric 的主线程；复制、订阅、失效与快照均不依赖
Minecraft tick。请求大小限制（单帧上限可配置，默认 16 MiB；PUT_SNAPSHOT payload 单独上限
64 MiB）、连接速率与审计日志由 master 实施。

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

    role: SOURCE | SLAVE
    serverName（velocity.toml 服务器名，源服注册用；缓存隔离键）
    minecraftVersion: 26.3
    runtimeProtocol
    fabricLoaderVersion
    fabricApiVersion
    modpackFingerprint
    registryAndDatapackFingerprint
    capabilities

指纹不一致、角色与鉴权结果不一致、版本/runtimeProtocol 不匹配时，master 拒绝连接
（HELLO_ACK accepted=false）与快照复用。slave 的指纹必须与已注册源（按 serverName 隔离）的指纹一致。
指纹计算排除 icbm_* 框架模组（icbm_source/icbm_slave 是框架组件非内容 modpack；其资源也不计入
registryAndDatapack 指纹）——slave（装 icbm_slave）与源服（装 icbm_source）共享同一内容 modpack
时指纹相等（里程碑 6 端到端验证）；registry 部分按 key 排序（registries() 迭代序不稳定）。
slave 以 SLAVE 角色订阅并维护本地对象库，装载前按 §6 服务闸门判定（master 连接存活 +
committed≥required + epoch 匹配），否则不展示旧区块（fail closed）。

缓存隔离：缓存键含 `serverName`（velocity.toml 服务器名，区分玩家所在世界/维度的隔离依据）与
`sourceServerId`；不同 serverName 的源服指纹分别注册与校验。

## 2. 快照消息

### WORLD_EPOCH

    dimensionId
    worldEpoch
    activationReason: START | ROLLBACK | MODPACK_CHANGE | ADMIN
    registryAndDatapackFingerprint
    spawnX / spawnY / spawnZ / spawnYaw / spawnPitch（源服 RespawnData 快照，26.3
      LevelData.RespawnData(GlobalPos, yaw, pitch)；slave join 时放置玩家的基准）

新 epoch 立即使对应维度的旧对象不可读（对象可读 ⇔ 对象 epoch == 维度当前 epoch）。
spawn 随事件日志原样重放（断线重连补发后 slave 仍可拿到出生点）。

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

（里程碑 7 实现；字段定稿如下）

### SUBMIT_OPERATION / OPERATION_COMMITTED / OPERATION_REJECTED / GET_OPERATION_STATUS / OPERATION_STATUS_RES

乐观操作（ARCHITECTURE §5）：slave 提交 intent → master 纯转发（按发起 slave 会话路由，不裁决）→
源服主线程幂等裁决（operationId 去重；原子前置条件；重算结果，不信任 slave 的“方块应该变成什么”）→
终态回发。操作超时不等于失败：slave 以 GET_OPERATION_STATUS 查询终态（有界终态历史，源服保留
最近 1024 个）。

    SUBMIT_OPERATION(0x14)
        operationId(16) / playerId(16) / virtualSessionId / clientInteractionSequence(8)
        operationType(1): BREAK=0 | PLACE=1（其余类型 slave 直接拒绝，不发提交）
        dimensionId / posX / posY / posZ
        baseRevision(8)      —— slave 看到的目标 chunk committed revision（快速拒绝，非唯一冲突键）
        preconditions        —— 目标方块状态哈希（sha256(BlockState.toString)）；源服与权威状态比对
        placeState           —— PLACE 的目标状态 id（如 minecraft:diamond_block；BREAK 为空）
        sentAt(8)

    OPERATION_COMMITTED(0x15)
        operationId / canonicalState（执行后 getBlockState 权威状态 id）/ chunkRevision / epoch / committedAt

    OPERATION_REJECTED(0x16)
        operationId / reason / dimensionId / resyncChunkX[] / resyncChunkZ[]（完整纠正范围）/ rejectedAt

    GET_OPERATION_STATUS(0x17)
        operationId

    OPERATION_STATUS_RES(0x18)
        operationId / status(1): UNKNOWN=0 | PENDING=1 | COMMITTED=2 | REJECTED=3
        canonicalState / chunkRevision / rejectReason / dimensionId / resyncChunkX[] / resyncChunkZ[]

裁决语义（26.3 ServerPlayerGameMode.destroyBlock 对照）：前置条件 = 目标非 air（BREAK）/ 可替换
（PLACE）+ preconditions 哈希 == 权威状态哈希 + 非 GameMasterBlock；执行走 level.setBlock/removeBlock
（Level.java:216 权威世界修改底层）；成功 → 脏标记 + revision 递增（快照管道随后发布新状态）。
PLACE 复杂放置拒绝（阶段 8.1）：hasBlockEntity（容器/告示牌等，setBlock 会建空 BE 且无 setPlacedBy
副作用）与双格结构（DoublePlantBlock/DoorBlock/BedBlock，setBlock 只放半格）→ REJECTED，权威世界
不得产生畸形方块；slave 预检验同判定，提前拒绝不发提交。

真实玩家接线（阶段 8.1）：slave 把真实玩家互动包（创造模式 START_DESTROY_BLOCK / BlockItem 放置）
接入操作管道——预检验（目标区块可服务 + 距离内 + 目标状态合法 + overlay 单 pending，fail closed）
→ overlay 预测登记并应用到已装载 LevelChunk（UPDATE_CLIENTS，玩家视角即时可见；不写对象库/不落盘）
→ SUBMIT_OPERATION：operationId=新 UUID；playerId=玩家 UUID（16 字节）；virtualSessionId="uuid:"+UUID
（master 按 operationId 路由回发起 slave，忽略该字段）；clientInteractionSequence=互动包 sequence；
baseRevision=slave 所见目标 chunk committed revision（快速拒绝，非唯一冲突键）；preconditions=
sha256hex(BlockState.toString)（slave 视图 = committed snapshot + overlay 已应用预测，相交操作被单
pending 挡住）。源服裁决仍以模拟上下文执行（跨进程无玩家实体；权限按玩家元数据映射，GameMasterBlock
按模拟权限级别拒绝）。终态回发后 slave：COMMITTED → 清预测，快照到达刷新（玩家在 chunk 内走就地
刷新，UPDATE_CLIENTS 广播）；REJECTED → RESYNC 落回 committed snapshot 并重应用该 chunk 其余
pending 预测（overlay 是唯一增量）。生存模式挖掘动画（逐 tick 进度）与掉落/经验延后，保持拒绝。

slave 侧：可丢弃 overlay（内存 Map，不落盘、不写 committed snapshot）登记预测；COMMITTED → 清除
该 pos 预测（真实快照随后覆盖）；REJECTED → 清除预测并落回 committed snapshot（源服拒绝即世界未变，
显示旧状态即与源一致）；每玩家每 chunk 单 pending（相交操作排队）。新快照到达时若区块已装载，
slave 触发刷新（卸载重载 / 就地应用快照）——不展示旧区块（里程碑 7 修复里程碑 6 限制）。

### CHUNK_MATERIALIZE_REQ / CHUNK_MATERIALIZE_RES（阶段 8.2 新增，按需物化）

slave 玩家接近服务边界（未命中/落后/epoch 不匹配的区块不可进入）或预取时，请求源服生成并发布
目标区块：slave → master → source 单跳转发，源服生成结果经既有快照管道（PUT_SNAPSHOT → 对象库
→ SNAPSHOT_PUSH）回流，无需显式完成回执。

    CHUNK_MATERIALIZE_REQ(0x1B)   slave → master → source
        requestId(8) / dimensionId / chunkX / chunkZ
        priority(1): LOW=0 | NORMAL=1 | HIGH=2
        reason(1):    PLAYER_PROXIMITY=0 | PREFETCH=1

    CHUNK_MATERIALIZE_RES(0x1C)   master → slave 即时应答
        requestId(8) / accepted(1) / reason

路由与失败语义：master 校验 slave 角色后转发源服（SourceConnector）；源未连接/发送失败 →
RES(accepted=false, reason="source-unavailable")（fail closed，slave 保持撞墙，冷却后由下一次
撞墙/预取重试）。源服侧去重由物化 pending 集合保证（重复 REQ 幂等）。生成完成（FULL）→ 脏标记
→ 快照管道 → 发布后释放物化 ticket（不永久驻留）；slave 侧节流：同 chunk 冷却（默认 5s）、
PLAYER_PROXIMITY 每 tick 限量、周期预取（PREFETCH）。源服每 tick 限量 addTicket（默认 8），
防快速移动引发生成风暴阻塞源服主线程。

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

## 9. 登录验证与会话记账（阶段1 已实现；登录握手移交 master 重构后）

背景：Auth Gateway 归 master（HIERARCHY §2/§3）。**登录握手由 master 按 velocity/原版模式
执行**（RSA 密钥对在 master，启动时生成一次，1024 位，26.3 Crypt.generateKeyPair 对照）；
slave 只透传握手并拿回会话密钥——以 sharedSecret 建 AES/CFB8 Cipher 解密客户端报文
（Crypt.getCipher 对照）后继续原版流程。master 是唯一执行 Mojang 正版验证的组件（借
Mojang authlib 官方实现：MinecraftServicesDiscoveryService.create(proxy) →
SessionService.hasJoinedServer，不自造 HTTP/JSON），验证通过后以**验证通过的 UUID** 登记
全局活跃会话表（同一玩家 UUID 同时只允许一个会话，防玩家加入多个 slave）并签发身份断言。

登录流（两阶段，走 LOGIN_VERIFY 流；流内帧 = [type(1)][消息体]，golden 测试锁定）：

阶段 A（握手挑战）：
- slave 收到客户端 hello → LOGIN_VERIFY_REQ；master 依次 票据单次兑换 → 落点判定，
  通过后生成每登录随机 challenge（官方 ServerLoginPacketListenerImpl.java:67 对照）；
- LOGIN_VERIFY_RES(ok=true) 附 challenge + master 公钥 X.509 DER——slave 构造
  ClientboundHelloPacket("", pubkey, challenge, true) 发客户端（:127 对照）；
  客户端回 ServerboundKeyPacket（RSA 加密会话密钥 + 加密挑战回显，26.3 同字段）。

阶段 B（验证 + 会话密钥）：
- slave 把 ServerboundKeyPacket 原样字段递交 LOGIN_KEY_REQ；master 用私钥解密会话密钥
  （Crypt.decryptByteToSecretKey 对照）→ 校验挑战回显 → 计算 digest
  （Crypt.digestData 对照：SHA-1 over serverId‖secret‖pubkey，BigInteger hex）→
  authlib SessionService.hasJoinedServer(name, digest, ip)（ip 仅 hasJoinedIp=true 携带，
  26.3 prevent-proxy-connections 语义）→ 以验证 UUID 记账（重复会话拒新）→ 签发断言；
- LOGIN_KEY_RES(verified=true) 附 sharedSecret(16) + MasterVerifiedIdentity
  （profileProperties = hasJoined 纹理签名属性，随断言传 slave 供 GameProfile 皮肤）。

消息 type 与字段（LOGIN_VERIFY_REQ=0x11 / LOGIN_VERIFY_RES=0x12 / SESSION_CLOSED=0x13 /
LOGIN_KEY_REQ=0x19 / LOGIN_KEY_RES=0x1A；golden 测试锁定）：

### LOGIN_VERIFY_REQ（slave → master，阶段 A）

    playerName(2+utf8) / playerUuid(16)（客户端声明）/ slaveId(2+utf8) /
    connectionInfo(2+utf8)（"ip:port"，slave 侧 remote）/ nonce(16) / sentAt(8)

### LOGIN_VERIFY_RES（master → slave，阶段 A 响应）

    ok(1) + reason(2+utf8)
    ok=true 时附：challenge(16) + publicKeyDer(2+bytes)
    ok=false 时 reason 为拒绝原因：REPLAYED_NONCE（票据重放）/ SERVER_NOT_ALLOWED（落点白名单）/
      RATE_LIMITED / 黑名单

### LOGIN_KEY_REQ（slave → master，阶段 B）

    playerUuid(16) / nonce(16)（与阶段 A 同一 nonce 关联）/ keybytes(2+bytes) /
    encryptedChallenge(2+bytes)（ServerboundKeyPacket 原样字段）

### LOGIN_KEY_RES（master → slave，阶段 B 响应）

    verified(1) + reason(2+utf8) + sharedSecret(16)
    verified=true 时附 MasterVerifiedIdentity 断言：
      issuer(2+utf8) / keyId(2+utf8) / audience(2+utf8) / playerUuid(16)（Mojang 验证通过的
      UUID）/ sessionId(2+utf8) / nonce(16) / expiresAt(8) / signature(2+bytes) /
      profileProperties(count(2) + 每项 name(2+utf8) + value(2+utf8) + signature(2+utf8))
    verified=false 时 reason：DUPLICATE_SESSION（验证 UUID 已存在于其它 slave）/
      UNVERIFIED_USERNAME（hasJoined 未命中）/ AUTH_SERVERS_UNAVAILABLE /
      CHALLENGE_MISMATCH / KEY_DECRYPT_FAILED / KEY_UNKNOWN（阶段 A 过期或重放）

### SESSION_CLOSED（slave → master）

    playerUuid(16) / sessionId(2+utf8) / reason(2+utf8)

master 注销记账（防会话表泄漏）；玩家断开、切换落点、后端故障均需上报。

落地状态（里程碑 4/5 + 握手移交重构）：slave 侧 LoginVerifyGate 在玩家登出时上报
SESSION_CLOSED；master 侧 SessionRegistry（控制服务内模块）经 ControlService.onSessionClosed
注销（SESSION_CLOSED 同时吊销票据）；slave 连接断开时按 slave 维度批量注销
（SESSION_CLOSED 未送达时防会话表泄漏；挂起握手随断开清理）。

完整验证流程（握手移交 master 后）：master 是唯一验证者，依次：
1. （阶段 A）票据单次兑换：LOGIN_VERIFY_REQ.nonce 首次出现才继续；同一 nonce 重放
   → REPLAYED_NONCE；
2. （阶段 A）落点判定：serverName（= slaveId）不在白名单 → SERVER_NOT_ALLOWED；
3. （阶段 A）每登录随机 challenge + master 公钥 → slave 发 ClientboundHelloPacket 给客户端；
4. （阶段 B）RSA 解密会话密钥（Crypt.decryptByteToSecretKey 对照）→ 挑战回显校验 →
   digest = SHA-1(serverId‖secret‖pubkey) hex（Crypt.digestData 对照）；
5. （阶段 B）Mojang 正版验证：借 Mojang authlib 官方实现
   MinecraftServicesDiscoveryService.create(proxy).createMinecraftSessionService() →
   SessionService.hasJoinedServer(name, digest, ip)（ip 仅 control.auth.hasJoinedIp=true 时携带，
   26.3 prevent-proxy-connections 默认不带——slave 透传时其看到的客户端地址在 localhost/LAN
   与 Mojang 会话来源 IP 不一致）→ 未验证/服务不可用 → 拒绝（fail closed，
   UNVERIFIED_USERNAME / AUTH_SERVERS_UNAVAILABLE）；offline 开关
   （control.auth.verifyMojang=false）以客户端声明身份放行但仍记账（开发/测试惯例，生产必须开启）；
6. （阶段 B）全局会话记账：以**验证通过的 UUID** 记账，已活跃 → DUPLICATE_SESSION（拒新；
   踢旧为管理操作 kickOld），与官方 verifyLoginAndFinishConnectionSetup 同序；
7. （阶段 B）断言签发：MasterVerifiedIdentity 经 HMAC-SHA256(master assertion key, canonical
   字段编码) 签名（keyId 轮换支持；断言短过期 expiresAt；profileProperties 随断言），
   LOGIN_KEY_RES(verified=true, sharedSecret, 断言)。

失败路径回滚记账与票据（保证不泄漏）。断言签名密钥在 master 离线保护；对称 HMAC 在封装
通道受信面内防重放/完整性，slave 间伪造由通道会话密钥隔离阻断（非对称公钥验证随密钥分发流
阶段 6/7）。

约束：

- 登录握手走封装通道受信流（§1）两阶段，不经玩家流量（玩家流量只在 slave 本地解密后进入
  缓存/权威会话数据面）。
- RSA 密钥对在 master 启动时生成一次（1024 位，官方 Crypt.generateKeyPair 同规格），全体登录
  共用；每登录仅 challenge 随机。
- master 记账与落点判定的竞态（验证通过→玩家真正进入后端之间）需两阶段确认或持有锁，避免双通过。
- offline 模式（开发/测试）：master 配置开关，verify=false 时直接放行但仍记账（沿用测试服务器惯例）。
- 重复会话策略（拒新 / 踢旧）与“源服 + 一个缓存后端同时在线”是否允许，由部署策略定案。

## 10. 权威会话桥（里程碑 5 v1；ARCHITECTURE §3.2）

权威会话（玩家需要源服实时权威数据）时，slave 把明文 Minecraft 报文经封装通道
**MC_PASSTHROUGH 流（streamId=2）** 交给 master，master 以 velocity 授信模式转发到源服
（backend 封装通道扩展承载 MC_PASSTHROUGH 流）；源服返回的数据包在 master 侧缓存后回传
slave。

- 方向与承载：slave ↔ master 与 master ↔ 源服均走封装通道 MC_PASSTHROUGH 流，body =
  `[流序号(8)][长度(4)][明文 MC 报文字节]`，**master 只做字节透传**（不解析 26.3 包结构，
  不复制常量）；流序号由发起方维护（master 不校验内容）。
- 桥建立：slave 发起（首个报文）登记为**单一活跃权威会话**（v1；多玩家并发随阶段 6/7
  虚拟玩家连接）；master 经落点判定授权（源服健康 + 断路器未 OPEN）；源服故障/断路器 OPEN
  → 拒绝转发，slave 继续缓存会话（后端故障 fallback，fail closed 不猜测）。
- 数据包缓存（PacketLog）：master 对每条转发报文做字节级审计日志（方向/时间戳/长度/
  SHA-256，有界内存）；**对象库仍是唯一基线**（committed+epoch 守卫不变）——权威会话期间
  源服区块数据由源服快照管道（ChunkSnapshotTicker）经既有 PUT_SNAPSHOT 进对象库，"源服
  返回数据包形成快照"的落点。字节日志不参与基线判定。
- 源服侧：BackendControlSession 新增 MC_PASSTHROUGH 流回调（默认丢弃，fail closed）；
  虚拟玩家连接（把报文注入真实 Minecraft 连接）随阶段 6/7 端到端实现。

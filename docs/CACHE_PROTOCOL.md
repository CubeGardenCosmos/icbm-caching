# ICBM 控制协议草案

状态：草案 v2  
参与方：源 Fabric 权威模组、master 缓存目录、slave 虚拟后端、master/slave Velocity 插件  
非目标：替代客户端与 Minecraft 后端之间的标准 26.3 游戏协议

## 1. 传输、安全与包络

主控制通道使用独立后台连接，不依赖玩家 Minecraft 连接。推荐 mTLS，加上每服务身份、请求大小限制、解压后大小限制、连接速率限制和审计日志。master/slave 的复制、订阅、身份断言验证和路由均不依赖 Minecraft tick；只有源服对世界操作的最终裁决进入源 Fabric 的主线程。

每条消息都有通用包络：

    schemaVersion
    messageType
    requestId
    sourceServerId
    worldEpoch
    sequence
    sentAt
    body

sequence 在同一发送方向递增。schema、认证、epoch、序号或哈希校验失败时，接收方应关闭缓存命中或操作通道，而非猜测兼容格式。

HELLO 包还必须携带：

    role: SOURCE | VIRTUAL_BACKEND | VELOCITY
    minecraftVersion: 26.3
    runtimeProtocol
    fabricLoaderVersion
    fabricApiVersion
    modpackFingerprint
    registryAndDatapackFingerprint
    capabilities

fingerprint 不一致时，控制服务拒绝快照复用和虚拟后端连接。

## 2. 快照消息

### WORLD_EPOCH

    dimensionId
    worldEpoch
    activationReason: START | ROLLBACK | MODPACK_CHANGE | ADMIN
    registryAndDatapackFingerprint

新 epoch 立即使对应维度的旧对象不可读。

### INVALIDATE

    dimensionId
    chunkX
    chunkZ
    revision
    reason: BLOCK_STATE | BLOCK_ENTITY | CHUNK_SAVE | WORLDGEN | ADMIN | UNKNOWN

控制服务先提高该区块 requiredRevision，使低 revision 对象不能作为虚拟后端基线。

### PUT_SNAPSHOT

    dimensionId
    chunkX
    chunkZ
    revision
    snapshotFormat: NBT_CHUNK_V1
    compression
    uncompressedLength
    payloadHash
    payload

控制服务验证并持久化对象后，以单一原子操作写入 committedRevision。

### RESOLVE_CHUNK

请求：

    dimensionId
    chunkX
    chunkZ
    expectedProtocol
    registryAndDatapackFingerprint
    minRevision

响应：

    status: HIT | MISS | STALE | INCOMPATIBLE | UNAVAILABLE
    committedRevision
    objectId
    objectHash

只有 HIT 可被虚拟后端装载为 committed snapshot 基线。

## 3. 乐观操作消息

### SUBMIT_OPERATION

虚拟后端发给源 Fabric 模组：

    operationId
    playerId
    virtualSessionId
    clientInteractionSequence
    operationType
    dimensionId
    baseRevision
    preconditions
    actionParameters

preconditions 至少有目标方块状态哈希；有方块实体时带方块实体哈希。需要库存的操作还必须带物品/库存前置版本。

此消息的含义是“请在权威世界中尝试操作”，不是“虚拟后端已成功修改世界”。

### OPERATION_COMMITTED

    operationId
    canonicalRevisionSet
    canonicalDeltas
    playerStateDelta
    acknowledgedClientSequence

源服以 operationId 去重，并只在其主线程中判定总顺序。第一个通过权威前置条件并成功提交的操作获胜。虚拟后端只可把 canonicalDeltas 合并到基线，随后删除对应 overlay。

### OPERATION_REJECTED

    operationId
    reason: CONFLICT | INVALID_PRECONDITION | PERMISSION | OUT_OF_RANGE | TIMEOUT | UNSUPPORTED
    canonicalRevisionSet
    stateResyncPlan
    acknowledgedClientSequence

stateResyncPlan 是回滚的权威清单，按操作需要列出完整 chunk、方块、方块实体、光照、库存、cursor slot、容器、实体或玩家位置的重同步。

### GET_OPERATION_STATUS

虚拟后端在 SUBMIT_OPERATION 超时后查询：

    operationId
    virtualSessionId

结果只能是 COMMITTED、REJECTED 或 UNKNOWN。UNKNOWN 时虚拟后端必须执行保守状态重同步；不得将本地预测自行判为成功或失败。

## 4. 管理与恢复

ADMIN_INVALIDATE 可按 chunk、region、dimension 或 world 生效，并记录审计原因。

断线重连后的规则：

- 源端补发 revision 期间，虚拟后端不使用可能断档的区块。
- 控制服务重启后，从持久 requiredRevision 和 committedRevision 恢复，不能仅依赖内存队列。
- 操作重发保持相同 operationId；源服返回第一次已确定的终态。
- 多个相交 pending operation 由虚拟后端串行化。首版限制为每玩家每区块一个。

## 5. Fabric Custom Payload 的位置

如果使用 Fabric Custom Payload，其 channel 必须使用独占命名空间、版本化 codec、大小限制和能力协商。它可用于玩家会话内的可选功能。

它不承担后台 snapshot、invalidate 或 operation 主通道，因为这类消息在无玩家连接、玩家切服或客户端不支持模组时不能保证送达。

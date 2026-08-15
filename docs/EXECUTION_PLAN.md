# 执行计划存档：阶段1 架构修正落地（里程碑 0-7 全部完成）

依据：docs/HIERARCHY.md、docs/ARCHITECTURE.md、docs/PHASES.md、docs/CACHE_PROTOCOL.md。
状态：里程碑 0-7 全部完成（交付摘要如下）；遗留开放项已并入 docs/PHASES.md 阶段 8
      （真实玩家操作接线、快照覆盖扩容、256M 压测与真实客户端故障模拟）。
      本文件为完成记录存档，不再维护 checkbox。

## 里程碑交付摘要

- **里程碑 0 决策定案**：玩家只能连 slave（master 不面向玩家，任意直连流量 RST）；单端口封装
  通道（magic → 握手[公共密码 + 每 slave UUID] → ChaCha20-Poly1305，不用 TLS，畸形/原版握手
  RST）；PSK 内网信任边界；control-service 并入 master；源服唯一权威 + velocity 授信模式
  （forwarding secret，serverName 缓存隔离键）；双 Fabric 模组（fabric-source backend /
  slave-mod slave）；virtual-backend 保留只读变体参考。
- **里程碑 1 传输层与协议重写**：封装层（帧 magic+长度+载荷、AUTH 握手、HKDF-SHA256 会话密钥
  派生、ChaCha20-Poly1305 多路复用：控制/验证/MC 报文流）；角色 SOURCE/SLAVE；HELLO serverName；
  LOGIN_VERIFY_REQ/RES、SESSION_CLOSED 消息 + codec + golden；缓存键 serverName 分域
  （`<root>/<serverName>/<namespace>/<path>/...`）；master 单端口封装通道服务（畸形/原版握手
  RST）。
- **里程碑 2 control-service 并入 master**：对象库/事件日志/订阅逻辑迁入 velocity-plugin 的
  control 包；删除独立 mTLS 监听与证书 CN 角色绑定；封装通道客户端（EncapsulatedChannelClient）
  两侧复用；PeerRegistry 按 AUTH UUID 绑定角色；仅剩封装通道 11420 单端口。
- **里程碑 3 backend 模组**：连接层 mTLS → velocity 授信模式通道（master 主动连接源服 backend
  端口；AUTH 公共密码证明 PSK=forwarding secret；无玩家在线也可用）；BackendControlServer/
  BackendControlSession（fabric-source）与 SourceConnector（master）；HELLO 携带 serverName；
  事件集合不变（WORLD_EPOCH/INVALIDATE/PUT_SNAPSHOT/操作终态）。
- **里程碑 4 slave Fabric 模组**：ChunkMap.scheduleChunkLoad/applyStep Mixin（对象库命中注入 FULL
  快照、未命中 EMPTY、跳过非 FULL 生成步骤）；PlayerBounds 移动边界 / SpectateGate 旁观互动拒绝 /
  SaveGate 禁持久化；SlaveControlClient（SLAVE HELLO → SUBSCRIBE → 事件应用 → ACK，断线重连
  重订阅）；LoginVerifyGateMixin 登录握手递交；256M 约束（磁盘对象库、注入即释放、
  MixinTargetVerifier 自检）；WORLD_EPOCH 携带 spawn（协议变更同一提交）；master 最小 Auth
  Gateway（SessionRegistry + AuthGatewayListener）。
- **里程碑 5 master 统筹与权威会话桥**：落点判定（RoutingPolicy）+ 健康检查（BackendHealth）+
  断路器（CircuitBreaker）；全局会话记账（SessionRegistry 登记/注销/拒新/踢旧，两阶段确认）；
  Auth Gateway 完整化（Mojang 正版验证 26.3 hasJoined 复刻 + offline 开关 + 断言 HMAC 签名 +
  票据单次兑换 + 吊销）；权威会话桥 v1（slave↔master↔源服 MC_PASSTHROUGH 字节透传 +
  PacketLog + 后端故障 fallback）。
- **里程碑 6 源服复验 + 端到端**：全组件测试绿复验；`build/main.py e2e` 真实三进程装配
  （源服 + master + slave 256M 堆）；forceload+setblock → 快照管道 → slave 订阅装载 → RCON
  玩家视角命中（Test passed）；修复 e2e 暴露问题（ControlServiceMain 主线程、EventLog recover
  尾部截断、backend SO_TIMEOUT、封装通道心跳、jar-in-jar、slave mixins 包、指纹时序与确定性、
  初始同步闸门 + setChunkForced 出生点装载）。
- **里程碑 7 操作与故障**：协议 5 消息（SUBMIT_OPERATION/COMMITTED/REJECTED/
  GET_OPERATION_STATUS/STATUS_RES，0x14-0x18）+ codec + golden；slave OperationOverlay（预测
  登记/清除/单 pending/discardAll）+ /icbmslave operate 调试命令；源服 OperationQueue（主线程
  幂等去重、有界终态历史）+ OperationAdjudicator（BREAK/PLACE 原子前置条件 + setBlock 权威
  执行 + canonical delta）；master 操作路由（operationId → 发起 slave；源服未连接 fail closed）；
  快照到达刷新（卸载重载/就地应用兜底）；e2e 乐观操作闭环（slave RCON BREAK → 源服裁决 air →
  快照 → slave 刷新 → 玩家视角 air Test passed）；修复 e2e 暴露问题（master↔slave 心跳、
  主线程 getChunkNow、裁决先于快照序列化、空服暂停、卸载卡住兜底）。
- **遗留开放项（已并入 PHASES.md 阶段 8）**：真实玩家会话接线（模拟玩家 → 真实互动包）、
  USE_BLOCK 等操作类型扩大、256M 堆压测、真实客户端故障模拟、源服游戏端口受信隔离验证。

## 依赖关系

```
0 定案（完成）→ 1 传输层 → 2 并入 master ─┬→ 3 backend
                                        ├→ 4 slave-mod（草稿先行）
                                        └→ 5 master 统筹/权威桥（依赖 1、2）
→ 6 复验/端到端 → 7 操作/故障
```

## 风险与注意

- 封装通道安全性 = 公共密码强度（PSK 边界）；运维手册与部署配置必须强制强密码与安全分发。
- ChaCha20-Poly1305 会话密钥派生建议包含 UUID 与连接 nonce（防重放、弱化 slave 间互读）。
- 权威会话桥涉及"slave 终止登录 + 明文转发"双重角色，是阶段 5 最大的协议复杂度点；
  里程碑 4 先保证缓存会话闭环，再开权威桥。
- 256M 约束是全链路硬约束：任何"整块驻留"路径（快照、编码、封装缓冲）都要按需流式。
- **第三方协议库评估（Protocolize，Exceptionflug/protocolize）：不引入。** MIT 许可且仍在
  维护，但 `ProtocolVersions.MINECRAFT_LATEST = 1_21_5`（协议 770），不支持 26.3
  （协议 1073742154）；包 ID/字段/data components/tracked data 布局不同，引入即违反
  VERSION_FREEZE「26.3 常量必须来自 minecraft-assets」硬约束。借鉴点：其 Velocity pipeline
  注入（捕获后端连接原始包字节）思路，里程碑 5 权威会话桥用 Velocity 原生 API 实现。

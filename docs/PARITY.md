# Parity Matrix：有意差异与一致性记录

本表记录与 Minecraft 26.3-snapshot-8 原版可观察行为的差异或一致性结论（AGENTS.md「原版一致性与运行时约束」）。

| # | 主题 | 原版行为 | icbm 行为 | 判定 | 依据/备注 |
| --- | --- | --- | --- | --- | --- |
| 1 | 光照跨区块边界的 revision | 光照变化经 `ChunkHolder.sectionLightChanged` → `markUnsaved()`（ChunkHolder.java:148），相邻区块持久化数据（光照 section）随之变化 | 与 vanilla 脏标记一致：光照传播使相邻区块 revision 递增 | 一致（有意接受） | 快照必须反映光照变化（虚拟后端需要），故跟踪 vanilla 的全部 markUnsaved 而非仅方块；草稿 phase1-source-revision §3 |
| 2 | 每 tick 合并 | 单次修改可触发多次 markUnsaved（方块 + 光照结算跨 tick） | 每 tick 末 drain 合并为一次 revision + 一个稳定快照；跨 tick 的光照结算产生后续 revision | 一致 | 与「tick 末尾合并同一区块的修改，产生更高 revision 和稳定快照」设计一致 |
| 3 | 区块卸载时未发布修改 | vanilla 保存路径持久化区块 | 先失效（raise required）不提交，等待下次加载+修改后补快照 | 有意差异 | ARCHITECTURE §4.3「宁可冻结该区块或要求完整重同步」 |
| 4 | epoch 触发 | 无（原版无 epoch 概念） | START / ROLLBACK / MODPACK_CHANGE / ADMIN 新 epoch 使旧快照失效 | 项目扩展 | CACHE_PROTOCOL WORLD_EPOCH |
| 5 | secure chat | — | 不适用（本项目无聊天实现） | — | 与 icbm 服务端项目不同，本仓库不涉及聊天 |

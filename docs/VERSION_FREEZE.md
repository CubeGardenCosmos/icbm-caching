# 26.3 版本冻结记录

状态：已冻结（首个可用版本的基线）
冻结日期：2026-08-14
来源：piston-meta 官方 version manifest 与 Fabric meta / Modrinth 实测

本文件是版本与指纹快照的唯一来源（AGENTS.md「参考基线」）。任何版本升级必须先改本文件、`build/` 脚本与 AGENTS.md，再重建环境。

## 冻结清单

| 项 | 值 | 来源/验证方式 |
| --- | --- | --- |
| Minecraft 版本 | `26.3-snapshot-8` | piston-meta version manifest |
| 运行时协议 | `1073742154` | server.jar 内 `version.json` |
| DataVersion（world_version） | `5010` | server.jar 内 `version.json` |
| 资源包格式 | `96`（resource_major 96 / minor 0） | server.jar 内 `version.json` |
| 数据包格式 | `116`（data_major 116 / minor 0） | server.jar 内 `version.json` |
| Java | 25（`java-runtime-epsilon`，Adoptium Temurin 25.0.4+7 LTS） | piston-meta / Adoptium API |
| 映射 | 官方 mojmap（server jar 已为 mojmap 命名，无 yarn/intermediary） | server.jar 字节码抽查 |
| Fabric Loader | `0.19.3` | meta.fabricmc.net |
| Fabric API | `0.157.1+26.3` | Modrinth fabric-api |
| Fabric Loom（Gradle 插件） | `1.17.19` | maven.fabricmc.net |
| netty（控制服务/协议模块） | `4.1.115.Final` | mavenCentral（mod 内由 Minecraft 提供，不重复打包） |
| slf4j / logback | `2.0.16` / `1.5.12` | mavenCentral（control-service 显式；mod 内由 loader 提供） |
| 参考仓库 | FabricMC/fabric `26.3` 分支；PaperMC/Velocity `master` | 只读克隆到 `build/workspace/` |

## 控制协议冻结

- 包络 schemaVersion：`1`；帧格式 `[4字节长度][ICBM magic + 包络 + body]`（golden 测试锁定，`protocol/` 模块）。
- 组件版本：protocol `1.2.0`、control-service `1.2.0`、velocity-plugin `1.3.0`、fabric-source `1.3.0`（大版本.阶段.bug 规则，见 AGENTS.md「版本号管理」）。

## 构建指纹

- `registryAndDatapackFingerprint` 与 `modpackFingerprint` 在 init 部署后由脚本计算（固定模组、数据包与生成设置输入），结果记录于 `build/workspace/fingerprints.json`（Git 忽略）。
- 任一模组或数据包变动必须判定为新 fingerprint（对应 `WORLD_EPOCH` 的 `MODPACK_CHANGE` 激活原因）。

## 注意事项

- 26.3 仍为 snapshot 系列：Mixin 名称、包 ID、chunk 序列化细节必须在构建和启动时对 `build/workspace/minecraft-assets` 反编译源码与实际字节码验证；禁止复制 26.2 或 1.21.x 的常量。
- 26.3 正式版发布后重新冻结并同步更新本表与 AGENTS.md。

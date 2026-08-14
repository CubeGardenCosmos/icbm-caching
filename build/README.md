# ICBM Caching 开发环境工具

`build/` 下的 Python 项目（uv 管理，纯标准库），用于部署与维护 `build/workspace/` 开发环境：

- `uv run build/main.py init`：部署 JDK 25、克隆参考仓库（Fabric/Velocity）、部署测试 Fabric 服务器、反编译 26.3-snapshot-8 服务端源码、构建 `fabric-source/` 模组。
- `uv run build/main.py server`：启动测试 Fabric 服务器（前台）。
- `uv run build/main.py test`：运行组件测试（fabric-source 的 `gradlew test`）。
- `uv run build/main.py build`：构建组件（fabric-source 的 `gradlew build`）。
- `uv run build/main.py reinit|cleanup`：破坏性操作，先确认后执行。

分层：`icbm_dev_env/cli.py`（入口）→ `orchestrator.py`（编排）→ `domain/`（领域功能）→ `infra/`（基础设施）。`main.py` 只做 uv 自举与 CLI 分发。

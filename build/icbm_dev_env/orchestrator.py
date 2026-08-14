"""编排层：串联领域功能的 init/reinit/cleanup/server/test/build/control 步骤。"""

from __future__ import annotations

from .domain import control, decompile, jdk, modbuild, refs, server
from .infra import shell


class Orchestrator:
    """按 AGENTS.md「开发环境」章节编排环境生命周期。"""

    def run(self, command: str) -> int:
        handler = {
            "init": self.init,
            "reinit": self.reinit,
            "cleanup": self.cleanup,
            "server": server.start_test_server,
            "test": modbuild.run_tests,
            "build": modbuild.run_build,
            "control": control.start_control_service,
            "certs": control.gen_certs,
        }[command]
        return int(handler() or 0)

    def init(self) -> None:
        jdk.install()
        refs.clone_all()
        server.deploy()
        decompile.run()
        # 预热编译缓存，成败不阻断（AGENTS.md）
        try:
            modbuild.run_build()
        except Exception as exc:  # noqa: BLE001
            print(f"[warn] 组件构建失败（不阻断 init）: {exc}")

    def reinit(self) -> None:
        from .infra import paths

        if shell.confirm("删除 build/workspace 后重新完整部署？（破坏性操作）"):
            paths.remove_workspace()
            self.init()

    def cleanup(self) -> None:
        from .infra import paths

        if shell.confirm("删除 build/workspace 与各组件构建产物？（破坏性操作）"):
            paths.remove_workspace()
            modbuild.clean_artifacts()

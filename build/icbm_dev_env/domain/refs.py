"""参考仓库克隆：FabricMC/fabric（26.3 分支优先）与 PaperMC/Velocity（master）。"""

from __future__ import annotations

from . import config
from ..infra import paths, shell


def _clone_with_fallback(url: str, branches: tuple[str, ...], dest) -> None:
    if (dest / ".git").exists():
        print(f"参考仓库已存在: {dest}")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    for branch in branches:
        print(f"克隆 {url} @ {branch} -> {dest} ...")
        proc = shell.run(
            [
                "git", "clone", "--depth", "1", "--branch", branch,
                "--single-branch", url, str(dest),
            ],
            check=False,
        )
        if proc.returncode == 0:
            print(f"  完成（分支 {branch}）")
            return
        # 分支不存在时清理半成品再试下一个
        shell.run(["rm", "-rf", str(dest)], check=False)
    raise RuntimeError(f"克隆失败: {url}（尝试分支 {branches}）")


def clone_all() -> None:
    paths.ensure_workspace()
    _clone_with_fallback(config.FABRIC_REF_URL, config.FABRIC_REF_BRANCHES, paths.FABRIC_REF)
    _clone_with_fallback(
        config.VELOCITY_REF_URL, (config.VELOCITY_REF_BRANCH,), paths.VELOCITY_REF
    )

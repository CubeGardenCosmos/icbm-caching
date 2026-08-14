"""工作区路径常量（build/workspace，Git 忽略）。"""

from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # build/
REPO_ROOT = ROOT.parent

WORKSPACE = ROOT / "workspace"
JDK_DIR = WORKSPACE / "jdk-25"
TOOLS_DIR = WORKSPACE / "tools"
MINECRAFT_ASSETS = WORKSPACE / "minecraft-assets"
FABRIC_REF = WORKSPACE / "fabric"
VELOCITY_REF = WORKSPACE / "velocity"
SERVER_DIR = WORKSPACE / "server"
DRAFT_DIR = WORKSPACE / "draft"
FINGERPRINTS = WORKSPACE / "fingerprints.json"

# 组件目录（仓库根下的 Java/其他组件）
FABRIC_SOURCE = REPO_ROOT / "fabric-source"
PROTOCOL = REPO_ROOT / "protocol"
CONTROL_SERVICE = REPO_ROOT / "control-service"
VELOCITY_PLUGIN = REPO_ROOT / "velocity-plugin"

# 全部 Java 组件（构建/测试/清理按此遍历；fabric-source 为 Loom 模组，其余为纯 Java）
JAVA_COMPONENTS = (PROTOCOL, CONTROL_SERVICE, VELOCITY_PLUGIN, FABRIC_SOURCE)

# 控制服务运行目录与 mTLS 证书
CONTROL_RUN = WORKSPACE / "control-run"
CERTS_DIR = WORKSPACE / "certs"


def component_root(name: str) -> Path:
    """按名字解析组件根（protocol/control-service/velocity-plugin/fabric-source）。"""
    return REPO_ROOT / name


def ensure_workspace() -> None:
    for d in (WORKSPACE, TOOLS_DIR, MINECRAFT_ASSETS, DRAFT_DIR):
        d.mkdir(parents=True, exist_ok=True)


def remove_workspace() -> None:
    shutil.rmtree(WORKSPACE, ignore_errors=True)

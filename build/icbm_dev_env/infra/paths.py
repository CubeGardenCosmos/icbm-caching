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

# debug 实例根（build/workspace/server；三个子文件夹 + jre，见 domain/debug_run.py）
DEBUG_ROOT = WORKSPACE / "server"
DEBUG_SOURCE = DEBUG_ROOT / "source"  # 真后端：正常 Minecraft 世界服务器（icbm-source 模组）
DEBUG_MASTER = DEBUG_ROOT / "master"  # velocity master（ControlServiceMain；封装通道/对象库）
DEBUG_SLAVE = DEBUG_ROOT / "slave"  # slave 缓存后端（监听 0.0.0.0:25565，区域 icbm:lan）
DEBUG_JRE = DEBUG_ROOT / "jre"  # JDK 25 运行时（符号链接 → ../jdk-25）

SERVER_DIR = DEBUG_ROOT  # 兼容旧名：现为 debug 实例根（源服目录一律用 DEBUG_SOURCE）
SLAVE_SERVER = DEBUG_SLAVE  # slave 服务器目录（里程碑6 端到端同用；旧 workspace/slave-server 废弃）
DRAFT_DIR = WORKSPACE / "draft"
FINGERPRINTS = WORKSPACE / "fingerprints.json"

# 组件目录（仓库根下的 Java/其他组件）
FABRIC_SOURCE = REPO_ROOT / "fabric-source"
PROTOCOL = REPO_ROOT / "protocol"
VELOCITY_PLUGIN = REPO_ROOT / "velocity-plugin"  # master（control 逻辑已并入，阶段2）
VIRTUAL_BACKEND = REPO_ROOT / "virtual-backend"
REGISTRY_DUMP = REPO_ROOT / "tools" / "registry-dump"

# 全部 Java 组件（构建/测试/清理按此遍历；fabric-source 为 Loom 模组，其余为纯 Java）
JAVA_COMPONENTS = (PROTOCOL, VELOCITY_PLUGIN, VIRTUAL_BACKEND, FABRIC_SOURCE)

# 控制服务运行目录（master 独立进程形态；封装通道配置）
CONTROL_RUN = WORKSPACE / "control-run"


def component_root(name: str) -> Path:
    """按名字解析组件根（protocol/velocity-plugin/fabric-source）。"""
    return REPO_ROOT / name


def ensure_workspace() -> None:
    for d in (WORKSPACE, TOOLS_DIR, MINECRAFT_ASSETS, DRAFT_DIR):
        d.mkdir(parents=True, exist_ok=True)


def remove_workspace() -> None:
    shutil.rmtree(WORKSPACE, ignore_errors=True)

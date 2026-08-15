"""CLI 入口：解析子命令并分发到 Orchestrator。"""

from __future__ import annotations

import argparse
import sys

from .orchestrator import Orchestrator


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="icbm-env",
        description="ICBM Caching 开发环境工具",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("init", help="部署完整开发环境（JDK/参考仓库/测试服务器/反编译/构建）")
    sub.add_parser(
        "reinit",
        help="删除 build/workspace 后重新完整部署（破坏性，需确认）",
    )
    sub.add_parser(
        "cleanup",
        help="清理 build/workspace 与构建产物，腾出硬盘空间（破坏性，需确认）",
    )
    sub.add_parser("server", help="启动测试 Fabric 服务器（前台，Ctrl-C 退出）")
    sub.add_parser(
        "test",
        help="运行全部组件测试（protocol/velocity-plugin/virtual-backend/fabric-source）",
    )
    sub.add_parser(
        "build",
        help="构建全部组件（protocol/velocity-plugin/virtual-backend/fabric-source）",
    )
    sub.add_parser(
        "control",
        help="启动 master 控制服务（封装通道，前台，端口 11420）",
    )
    sub.add_parser(
        "capture-registry",
        help="构建期转储源服注册表数据 → virtual-backend 资源（阶段3）",
    )
    sub.add_parser(
        "vb",
        help="启动虚拟后端（阶段3 旁观者服务，前台；ICBM_VIRTUAL_BACKEND_CONFIG 指定配置）",
    )
    sub.add_parser(
        "slave",
        help="启动 slave 服务器（build/workspace/server/slave，前台；256M 堆）",
    )
    sub.add_parser(
        "debug-run",
        help="启动 debug 实例（source/master/slave + jre；TUI 三窗口，Shift+Tab 切换；"
        "slave 监听 0.0.0.0:25565，区域 icbm:lan；Ctrl-C 退出）",
    )
    sub.add_parser(
        "e2e",
        help="里程碑6 端到端验证：源服 setblock → 快照 → master → slave → RCON 玩家视角 + fail closed",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return Orchestrator().run(args.command)


if __name__ == "__main__":
    raise SystemExit(main())

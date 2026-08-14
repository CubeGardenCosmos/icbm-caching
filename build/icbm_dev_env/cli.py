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
    sub.add_parser("test", help="运行组件测试（fabric-source 的 gradlew test）")
    sub.add_parser("build", help="构建组件（fabric-source 的 gradlew build）")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return Orchestrator().run(args.command)


if __name__ == "__main__":
    raise SystemExit(main())

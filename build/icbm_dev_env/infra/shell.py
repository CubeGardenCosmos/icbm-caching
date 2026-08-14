"""shell 封装：环境变量代理、确认交互、命令执行。"""

from __future__ import annotations

import os
import subprocess
from typing import Sequence


def proxy_env() -> dict[str, str]:
    """合并当前环境，确保代理变量存在。"""
    env = dict(os.environ)
    env.setdefault("HTTPS_PROXY", env.get("https_proxy", ""))
    env.setdefault("HTTP_PROXY", env.get("http_proxy", ""))
    return env


def jvm_proxy_args() -> list[str]:
    """把代理翻译为 JVM 系统属性（Gradle 等 JVM 工具使用）。"""
    args: list[str] = []
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY") or ""
    if proxy and proxy.startswith("http"):
        # http://host:port
        host_port = proxy.split("://", 1)[1]
        host, _, port = host_port.rpartition(":")
        if host and port.isdigit():
            args += [
                f"-Dhttp.proxyHost={host}",
                f"-Dhttp.proxyPort={port}",
                f"-Dhttps.proxyHost={host}",
                f"-Dhttps.proxyPort={port}",
            ]
    return args


def run(
    cmd: Sequence[str],
    *,
    check: bool = True,
    cwd: str | None = None,
    env: dict[str, str] | None = None,
    capture: bool = False,
) -> subprocess.CompletedProcess:
    """执行命令；默认 check=True 并继承环境。"""
    full_env = proxy_env()
    if env:
        full_env.update(env)
    return subprocess.run(
        list(cmd),
        check=check,
        cwd=cwd,
        env=full_env,
        capture_output=capture,
    )


def confirm(prompt: str) -> bool:
    """交互确认（非 TTY 默认拒绝）。"""
    if not sys_stdin_tty():
        print(f"[非交互环境] 自动拒绝：{prompt}")
        return False
    answer = input(f"{prompt} [y/N] ").strip().lower()
    return answer in ("y", "yes")


def sys_stdin_tty() -> bool:
    import sys

    return sys.stdin.isatty()

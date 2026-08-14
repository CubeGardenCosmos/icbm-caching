"""JDK 25 部署：Adoptium 官方源，失败自动降级清华 TUNA 镜像。"""

from __future__ import annotations

import json
import re
import subprocess
import tarfile
from pathlib import Path

from . import config
from ..infra import http, paths, shell

_JDK_MARKER = ".icbm-jdk-25"


def _java_bin(jdk_dir: Path) -> Path:
    return jdk_dir / "bin" / "java"


def installed() -> bool:
    java = _java_bin(paths.JDK_DIR)
    if not java.exists() or not (paths.JDK_DIR / _JDK_MARKER).exists():
        return False
    try:
        out = subprocess.run(
            [str(java), "-version"], capture_output=True, text=True, timeout=30
        )
        return bool(re.search(r'version "25', out.stderr))
    except Exception:
        return False


def _adoptium_link() -> str:
    """查询 Adoptium API 获取最新 JDK 25 linux x64 下载链接。"""
    import urllib.request

    req = urllib.request.Request(
        config.ADOPTIUM_API, headers={"User-Agent": "icbm-dev-env/1.0"}
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.load(resp)
    first = data[0]
    package = first["binary"]["package"]
    return package["link"], package["name"]


def _tuna_link(filename: str) -> str:
    return config.TUNA_JDK_PATTERN.format(filename=filename)


def _install_from(url: str, filename: str) -> None:
    archive = paths.TOOLS_DIR / filename
    http.download_checked(url, archive, "JDK 25")
    paths.JDK_DIR.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tf:
        tf.extractall(paths.JDK_DIR, filter="data")
    # tar 顶层目录如 jdk-25.0.4+7/，把内容提升到 jdk-25/
    extracted = [p for p in paths.JDK_DIR.iterdir() if p.is_dir() and p.name != _JDK_MARKER]
    if len(extracted) == 1 and extracted[0].name != "bin":
        src = extracted[0]
        for item in src.iterdir():
            item.rename(paths.JDK_DIR / item.name)
        src.rmdir()
    (paths.JDK_DIR / _JDK_MARKER).write_text(str(config.JAVA_MAJOR))
    archive.unlink(missing_ok=True)


def install() -> None:
    paths.ensure_workspace()
    if installed():
        print(f"JDK {config.JAVA_MAJOR} 已就绪: {paths.JDK_DIR}")
        return
    try:
        link, filename = _adoptium_link()
        print(f"从 Adoptium 官方源部署 JDK {config.JAVA_MAJOR} ...")
        _install_from(link, filename)
    except Exception as exc:  # noqa: BLE001 - 官方源失败自动降级
        print(f"Adoptium 官方源失败（{exc}），降级清华 TUNA 镜像 ...")
        link, filename = _adoptium_link()
        _install_from(_tuna_link(filename), filename)
    if not installed():
        raise RuntimeError("JDK 25 部署失败：版本校验未通过")
    print(f"JDK 25 部署完成: {paths.JDK_DIR}")


def java_home_env() -> dict[str, str]:
    """返回包含 JAVA_HOME 的环境（供 Gradle/服务器使用）。"""
    if not installed():
        raise RuntimeError("JDK 25 未部署，请先运行 init")
    return {"JAVA_HOME": str(paths.JDK_DIR), "PATH": f"{paths.JDK_DIR}/bin:" + shell.proxy_env()["PATH"]}

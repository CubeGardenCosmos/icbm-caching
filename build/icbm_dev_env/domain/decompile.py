"""反编译 Mojang 官方 26.3-snapshot-8 服务端到 build/workspace/minecraft-assets。

server jar 已为官方 mojmap 命名（无需 yarn/intermediary），直接对 bundler 内层
server jar 运行 Vineflower。产物只作行为分析参考，不进入 Git。
"""

from __future__ import annotations

import json
import urllib.request
import zipfile
from pathlib import Path

from . import config
from ..infra import http, paths, shell


def _vineflower_jar() -> Path:
    jar = paths.TOOLS_DIR / f"vineflower-{config.VINEFLOWER}.jar"
    if not jar.exists():
        http.download_checked(config.VINEFLOWER_RELEASE_URL, jar, "Vineflower")
    return jar


def _fetch_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "icbm-dev-env/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def _vanilla_server_url() -> str:
    """从 piston-meta 定位 26.3-snapshot-8 的官方 server 下载地址。"""
    manifest = _fetch_json(config.PISTON_MANIFEST)
    entry = next(
        (v for v in manifest["versions"] if v["id"] == config.MINECRAFT_VERSION), None
    )
    if entry is None:
        raise RuntimeError(f"piston-meta 中未找到 {config.MINECRAFT_VERSION}")
    version_json = _fetch_json(entry["url"])
    server = version_json.get("downloads", {}).get("server", {})
    url = server.get("url")
    if not url:
        raise RuntimeError(f"{config.MINECRAFT_VERSION} 的 server 下载地址缺失")
    return url


def _vanilla_server_jar() -> Path:
    """下载 Mojang 官方 server jar 到 tools/（bundler 形态）。"""
    jar = paths.TOOLS_DIR / f"vanilla-server-{config.MINECRAFT_VERSION}.jar"
    if not jar.exists():
        http.download_checked(_vanilla_server_url(), jar, "Vanilla Server")
    return jar


def _inner_server_jar(vanilla: Path, out_dir: Path) -> Path:
    """从 bundler jar 中提取内层 server jar（含全部 net/minecraft 类）。"""
    inner_name = f"server-{config.MINECRAFT_VERSION}.jar"
    target = out_dir / inner_name
    if target.exists():
        return target
    with zipfile.ZipFile(vanilla) as zf:
        entry = next(
            (n for n in zf.namelist() if n.endswith(f"{config.MINECRAFT_VERSION}/server-{config.MINECRAFT_VERSION}.jar")),
            None,
        )
        if entry is None:
            raise RuntimeError("bundler 中未找到内层 server jar")
        target.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(entry) as src, open(target, "wb") as out:
            out.write(src.read())
    return target


def run() -> None:
    paths.ensure_workspace()
    marker = paths.MINECRAFT_ASSETS / ".decompiled"
    if marker.exists():
        print(f"反编译源码已存在: {paths.MINECRAFT_ASSETS}")
        return

    jar = _vineflower_jar()
    vanilla = _vanilla_server_jar()
    inner = _inner_server_jar(vanilla, paths.TOOLS_DIR / "server-inner")
    print("反编译 26.3-snapshot-8 服务端源码（Vineflower，官方 mojmap）...")
    # 4G 堆：本机内存受限时的保守值；-dgs=1 保留泛型签名便于对照
    shell.run(
        [
            "java",
            "-Xmx4G",
            "-jar", str(jar),
            "-dgs=1",
            "-log=warn",
            str(inner),
            str(paths.MINECRAFT_ASSETS),
        ],
        cwd=str(paths.MINECRAFT_ASSETS),
    )
    if not (paths.MINECRAFT_ASSETS / "net" / "minecraft").exists():
        raise RuntimeError("反编译失败：未生成 net/minecraft 源码树")
    marker.write_text(config.MINECRAFT_VERSION)
    print(f"反编译完成: {paths.MINECRAFT_ASSETS}")

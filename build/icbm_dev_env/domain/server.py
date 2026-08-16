"""测试服务器部署与启动：Fabric 官方安装器产出 26.3-snapshot-8 Fabric 服务器。

部署位置：debug 实例的源服目录 build/workspace/server/source（debug 实例根
build/workspace/server，见 domain/debug_run.py；旧布局 workspace/server 根即源服的
内容由 _migrate_legacy_layout 迁入 source/）。
"""

from __future__ import annotations

import shutil
from pathlib import Path

from . import config
from ..infra import http, paths, shell


def _installer_jar() -> Path:
    jar = paths.TOOLS_DIR / f"fabric-installer-{config.FABRIC_INSTALLER}.jar"
    if not jar.exists():
        http.download_checked(config.INSTALLER_JAR_URL, jar, "Fabric Installer")
    return jar


def _server_jar() -> Path:
    """兼容新旧安装器布局：fabric-server-launch.jar（新）或 server.jar（旧）。"""
    for name in ("fabric-server-launch.jar", "server.jar"):
        jar = paths.DEBUG_SOURCE / name
        if jar.exists():
            return jar
    raise RuntimeError(f"测试服务器未部署: {paths.DEBUG_SOURCE}")


def _migrate_legacy_layout() -> None:
    """旧布局（workspace/server 根即源服）→ 新 debug 布局（workspace/server/source/）。

    仅迁移一次：根目录出现 server.jar/fabric-server-launch.jar 且 source/ 尚不存在时，
    把根下旧源服内容全部移入 source/（跳过 debug 子文件夹名）。
    """
    root = paths.DEBUG_ROOT
    has_legacy = (root / "server.jar").exists() or (root / "fabric-server-launch.jar").exists()
    if not has_legacy or (paths.DEBUG_SOURCE / "server.jar").exists():
        return
    paths.DEBUG_SOURCE.mkdir(parents=True, exist_ok=True)
    moved = 0
    for item in root.iterdir():
        if item.name in ("source", "master", "slave", "jre"):
            continue
        shutil.move(str(item), str(paths.DEBUG_SOURCE / item.name))
        moved += 1
    print(f"[migrate] 旧布局 workspace/server 根内容 {moved} 项已迁入 {paths.DEBUG_SOURCE}")


def _ensure_test_config() -> None:
    """确保测试配置存在且确定（幂等）：EULA 接受 + 固定端口/offline-mode 测试属性。

    注意：debug 实例（debug-run）的 server.properties 由 python debug 运行时覆盖
    （online-mode=true / gamemode=creative / view-distance=32）；本函数是
    e2e/registry-capture 等确定性测试流程用的最小配置。
    """
    (paths.DEBUG_SOURCE / "eula.txt").write_text("eula=true\n")
    props = paths.DEBUG_SOURCE / "server.properties"
    props.write_text(
        "server-port=25599\n"
        "online-mode=false\n"
        "level-type=minecraft:flat\n"
        "spawn-protection=0\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "enable-command-block=true\n"
        "white-list=false\n"  # 26.3 默认 true；测试流程用 RCON 无玩家，显式关闭防误拒
        "pause-when-empty-seconds=0\n"  # 空服持续 tick（脏标记 → 快照发布/操作裁决依赖 Server thread；
        # 默认 60s 暂停→停止会杀掉 e2e 后半段——9.4 暴露）
    )


def ensure_fabric_api(mods_dir: Path) -> Path:
    """确保 mods 目录有 fabric-api jar：先复制源服 mods，缺失则从 Fabric maven 下载。"""
    name = f"fabric-api-{config.FABRIC_API}.jar"
    jar = mods_dir / name
    if jar.exists():
        return jar
    mods_dir.mkdir(parents=True, exist_ok=True)
    src = paths.DEBUG_SOURCE / "mods" / name
    if src.exists():
        shutil.copyfile(src, jar)
        return jar
    url = (f"{config.FABRIC_MAVEN}/net/fabricmc/fabric-api/fabric-api/"
           f"{config.FABRIC_API}/fabric-api-{config.FABRIC_API}.jar")
    http.download_checked(url, jar, "fabric-api")
    return jar


def deploy() -> None:
    """部署 Fabric 服务器到 build/workspace/server/source（官方 server + loader 0.19.3）。"""
    paths.ensure_workspace()
    _migrate_legacy_layout()
    if not (paths.DEBUG_SOURCE / "fabric-server-launch.jar").exists() and not (paths.DEBUG_SOURCE / "server.jar").exists():
        jar = _installer_jar()
        print("部署测试 Fabric 服务器（vanilla 26.3-snapshot-8 + loader 0.19.3）...")
        paths.DEBUG_SOURCE.mkdir(parents=True, exist_ok=True)
        # 用系统 Java 运行安装器（安装器仅需 Java 8+）；服务器运行由 jre 负责
        shell.run(
            [
                "java",
                "-jar", str(jar),
                "server",
                "-mcversion", config.MINECRAFT_VERSION,
                "-loader", config.FABRIC_LOADER,
                "-dir", str(paths.DEBUG_SOURCE),
            ],
            cwd=str(paths.DEBUG_SOURCE),
        )
    _server_jar()  # 存在性检查（新安装器需要自备 vanilla server.jar）
    # 新安装器不自带 vanilla jar：从 piston-meta 复制官方 server.jar（与反编译同源）
    vanilla = paths.TOOLS_DIR / f"vanilla-server-{config.MINECRAFT_VERSION}.jar"
    if not vanilla.exists():
        from . import decompile

        decompile._vanilla_server_jar()
    if not (paths.DEBUG_SOURCE / "server.jar").exists():
        shutil.copyfile(vanilla, paths.DEBUG_SOURCE / "server.jar")
    _ensure_test_config()
    print(f"测试服务器就绪: {paths.DEBUG_SOURCE}（端口 25599，offline-mode 测试用）")


def start_test_server() -> int:
    """前台启动测试服务器；先确保已部署。"""
    try:
        jar = _server_jar()
    except RuntimeError:
        deploy()
        jar = _server_jar()
    _ensure_test_config()
    from . import jdk

    env = jdk.java_home_env()
    print(f"启动测试服务器（JAVA_HOME={env['JAVA_HOME']}）...")
    proc = shell.run(
        ["java", f"-Xmx{_heap()}G", "-jar", jar.name, "nogui"],
        cwd=str(paths.DEBUG_SOURCE),
        env=env,
        check=False,
    )
    return proc.returncode


def _heap() -> int:
    """按机器内存取保守堆大小（最多 3G，测试服务器足够）。"""
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemTotal"):
                    kib = int(line.split()[1])
                    return min(max(kib // (1 << 20) // 2, 1), 3)
    except OSError:
        pass
    return 2

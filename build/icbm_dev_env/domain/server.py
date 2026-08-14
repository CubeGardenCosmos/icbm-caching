"""测试服务器部署与启动：Fabric 官方安装器产出 26.3-snapshot-8 Fabric 服务器。"""

from __future__ import annotations

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
        jar = paths.SERVER_DIR / name
        if jar.exists():
            return jar
    raise RuntimeError(f"测试服务器未部署: {paths.SERVER_DIR}")


def _ensure_test_config() -> None:
    """确保测试配置存在且确定（幂等）：EULA 接受 + 固定端口/offline-mode 测试属性。"""
    (paths.SERVER_DIR / "eula.txt").write_text("eula=true\n")
    props = paths.SERVER_DIR / "server.properties"
    props.write_text(
        "server-port=25599\n"
        "online-mode=false\n"
        "level-type=minecraft:flat\n"
        "spawn-protection=0\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "enable-command-block=true\n"
    )


def deploy() -> None:
    """部署 Fabric 服务器到 build/workspace/server（Mojang 官方 server + loader 0.19.3）。"""
    paths.ensure_workspace()
    if not (paths.SERVER_DIR / "fabric-server-launch.jar").exists() and not (paths.SERVER_DIR / "server.jar").exists():
        jar = _installer_jar()
        print("部署测试 Fabric 服务器（vanilla 26.3-snapshot-8 + loader 0.19.3）...")
        paths.SERVER_DIR.mkdir(parents=True, exist_ok=True)
        # 用系统 Java 运行安装器（安装器仅需 Java 8+）；服务器运行由 jdk-25 负责
        shell.run(
            [
                "java",
                "-jar", str(jar),
                "server",
                "-mcversion", config.MINECRAFT_VERSION,
                "-loader", config.FABRIC_LOADER,
                "-dir", str(paths.SERVER_DIR),
            ],
            cwd=str(paths.SERVER_DIR),
        )
    _server_jar()  # 存在性检查（新安装器需要自备 vanilla server.jar）
    # 新安装器不自带 vanilla jar：从 piston-meta 复制官方 server.jar（与反编译同源）
    vanilla = paths.TOOLS_DIR / f"vanilla-server-{config.MINECRAFT_VERSION}.jar"
    if not vanilla.exists():
        from . import decompile

        decompile._vanilla_server_jar()
    if not (paths.SERVER_DIR / "server.jar").exists():
        import shutil

        shutil.copyfile(vanilla, paths.SERVER_DIR / "server.jar")
    _ensure_test_config()
    print(f"测试服务器就绪: {paths.SERVER_DIR}（端口 25599，offline-mode 测试用）")


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
        cwd=str(paths.SERVER_DIR),
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

"""slave 服务器部署与启动（里程碑 6 端到端）：build/workspace/slave-server（Git 忽略）。

与源服 build/workspace/server 同模式：server.jar + Fabric loader（用 fabric-server-launch.jar
时 loader 内嵌；此处直接复制源服已部署目录的 jar 与 fabric-api），mods 装 icbm-slave，
独立世界目录含 icbm-control.properties（指向 master 封装通道）。
"""

from __future__ import annotations

import secrets
from pathlib import Path

from ..infra import paths, shell

SLAVE_SERVER = paths.WORKSPACE / "slave-server"


def _slave_mod_jar() -> Path:
    """构建 slave-mod 并返回 remap 后 jar（build/libs/icbm-slave-*.jar）。"""
    from . import modbuild

    modbuild._run("slave-mod", "build", "-x", "test")
    jars = sorted((paths.component_root("slave-mod") / "build" / "libs").glob("icbm-slave-*.jar"))
    for jar in jars:
        if "sources" not in jar.name:
            return jar
    raise RuntimeError("slave-mod 构建产物缺失（build/libs/icbm-slave-*.jar）")


def deploy() -> None:
    """部署 slave 服务器（幂等）：复制 server.jar/fabric-api、构建并复制 icbm-slave、生成配置。"""
    if not (paths.SERVER_DIR / "server.jar").exists():
        from . import server as source_server

        source_server.deploy()
    # e2e 确定性：清空 slave 世界（快照注入语义 = 空世界 + 对象库装载）
    for d in (SLAVE_SERVER / "world",):
        if d.exists():
            import shutil

            shutil.rmtree(d)
            print(f"  [slave] 清理历史世界: {d}")
    if not (paths.SERVER_DIR / "server.jar").exists():
        from . import server as source_server

        source_server.deploy()
    SLAVE_SERVER.mkdir(parents=True, exist_ok=True)
    import shutil

    shutil.copyfile(paths.SERVER_DIR / "server.jar", SLAVE_SERVER / "server.jar")
    if (paths.SERVER_DIR / "fabric-server-launch.jar").exists():
        shutil.copyfile(paths.SERVER_DIR / "fabric-server-launch.jar", SLAVE_SERVER / "fabric-server-launch.jar")
    # loader 库与源服共享（只读，节省磁盘；勿改 server/libraries）
    lib_link = SLAVE_SERVER / "libraries"
    if not lib_link.exists():
        lib_link.symlink_to(paths.SERVER_DIR / "libraries", target_is_directory=True)
    (SLAVE_SERVER / "eula.txt").write_text("eula=true\n")

    mods = SLAVE_SERVER / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for name in ("fabric-api-0.157.1+26.3.jar",):
        src = paths.SERVER_DIR / "mods" / name
        if not src.exists():
            raise RuntimeError(f"源服 mods 缺失: {src}（先 build main.py init）")
        shutil.copyfile(src, mods / name)
    jar = _slave_mod_jar()
    shutil.copyfile(jar, mods / "icbm-slave.jar")

    (SLAVE_SERVER / "server.properties").write_text(
        "server-port=25598\n"
        "online-mode=false\n"
        "level-type=minecraft:flat\n"
        "spawn-protection=0\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "enable-command-block=true\n"
        "enable-rcon=true\n"
        f"rcon.port=25580\n"
        f"rcon.password={secrets.token_hex(8)}\n"
        # 缓存服务器必须持续 tick（快照应用/区块刷新依赖 Server thread；26.3 空服默认
        # 60s 暂停 → tick 停止 → 卸载队列不处理 → 区块刷新卡住，里程碑 7 e2e 暴露）
        "pause-when-empty-seconds=0\n"
    )
    print(f"slave 服务器就绪: {SLAVE_SERVER}（端口 25598，RCON 25580，offline-mode 测试用）")


def write_slave_control_config(master_props: dict[str, str]) -> None:
    """从 master control.properties 同步生成 slave 世界目录 icbm-control.properties。

    psk/roleBindings（slave UUID）单点来自 master 配置，防不一致。
    """
    world = SLAVE_SERVER / "world"
    world.mkdir(parents=True, exist_ok=True)
    slave_uuid = _slave_uuid_from_bindings(master_props.get("control.roleBindings", ""))
    server_name = master_props.get("control.allowedServerNames", "lobby-1").split(",")[0].strip()
    props = (
        "control.masterHost=127.0.0.1\n"
        f"control.masterPort={master_props.get('control.encapsulatedPort', '11420')}\n"
        f"control.psk={master_props.get('control.psk', '')}\n"
        f"control.uuid={slave_uuid}\n"
        f"control.serverName={server_name}\n"
        "control.storeRoot=icbm-objects\n"
        "control.dimensions=minecraft:overworld\n"
        "control.viewDistance=8\n"
        "control.readTimeoutMillis=30000\n"
        "control.reconnectBackoffMillis=3000\n"
        "control.heartbeatIntervalMillis=8000\n"
        "control.selfcheck=true\n"
    )
    (world / "icbm-control.properties").write_text(props)
    print(f"slave 控制配置: {world / 'icbm-control.properties'}（serverName={server_name} uuid={slave_uuid}）")


def _slave_uuid_from_bindings(bindings: str) -> str:
    for part in bindings.split(","):
        pair = part.strip().split("=", 1)
        if len(pair) == 2 and pair[1].strip() == "SLAVE":
            return pair[0].strip()
    raise RuntimeError("master control.roleBindings 缺少 SLAVE 绑定（先 build main.py control）")


def start_slave_server() -> int:
    """前台启动 slave 服务器（Ctrl-C 退出）；先确保已部署。"""
    if not (SLAVE_SERVER / "fabric-server-launch.jar").exists():
        deploy()
    from . import jdk

    env = jdk.java_home_env()
    print(f"启动 slave 服务器（JAVA_HOME={env['JAVA_HOME']}）...")
    proc = shell.run(
        ["java", "-Xmx256M", "-jar", "server.jar", "nogui"],
        cwd=str(SLAVE_SERVER),
        env=env,
        check=False,
    )
    return proc.returncode

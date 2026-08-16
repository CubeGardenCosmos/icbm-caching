"""debug-run：交互式调试实例（TUI；Shift+Tab 切换窗口；Ctrl-C 退出）。

实例布局（AGENTS.md「开发环境」固定：debug 实例创建于 build/workspace/server，
分为三个子文件夹和一个 jre 文件夹）：

    build/workspace/server/
      source/   真后端：正常 Minecraft 世界服务器（icbm-source 模组；内网 25599）
      master/   velocity master（ControlServiceMain；封装通道 11420 / backend 11430）
      slave/    slave 缓存后端（监听 0.0.0.0:25565；区域 icbm:lan，F3 Brand 显示）
      jre/      JDK 25 运行时（符号链接 → ../jdk-25）

配置全部由 python debug 运行时覆盖（生成即覆盖，幂等）：
  online-mode=true（正版验证；master control.auth.verifyMojang=true）
  gamemode=creative（创造模式）
  view-distance=32（32 视距）
  slave 区域 icbm:lan（icbm.brandLocation）

进程：source/slave 用 jre 直接 java 启动；master 用 gradlew installDist 产物
（application 插件打包完整 runtime classpath），避免 TUI 清理时 gradle daemon 残留。
"""

from __future__ import annotations

import os
import random
import secrets
import shutil
import signal
import subprocess
import sys
import time
import uuid
from pathlib import Path

from ..infra import paths, shell
from . import server as source_server
from . import slave_server

# --- 端口（与 e2e 一致；slave 为玩家入口 25565） ---
SOURCE_PORT = 25599  # 真后端（内网，不面向玩家）
SLAVE_PORT = 25565  # slave 玩家入口（0.0.0.0）
RCON_PORT = 25580  # slave RCON（可选调试）
MASTER_PORT = 11420  # master 封装通道
BACKEND_PORT = 11430  # master↔源服 backend 授信通道

SERVER_NAME = "icbm-lan"  # 缓存隔离键/落点白名单（source 与 slave 共用）
BRAND_LOCATION = "icbm:lan"  # slave F3 Brand 区域（SlaveConfig.icbm.brandLocation）
IDENTITY_FILE = "identity.properties"  # 持久身份（UUID/PSK，跨 run 稳定）

LOGS_DIR = paths.DEBUG_ROOT / "logs"

# 256M 堆为生产 slave 设计约束；debug 实例放宽到 1G（本地开发机，view-distance=32）
SLAVE_HEAP = "1G"
SOURCE_HEAP = "2G"


def run_debug() -> int:
    """debug-run 入口：准备实例 → 启动三进程 → TUI（无 TTY 降级 headless）→ 清理。"""
    print("=== debug-run：三进程调试实例（source/master/slave）===")
    identity = prepare()
    procs = start_processes(identity)
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    from . import tui

    panes = [
        tui.Pane("source(真后端)", procs[0], LOGS_DIR / "source.log"),
        tui.Pane("master(velocity)", procs[1], LOGS_DIR / "master.log"),
        tui.Pane("slave(icbm:lan)", procs[2], LOGS_DIR / "slave.log"),
    ]
    print(f"实例就绪: {paths.DEBUG_ROOT}")
    print(f"  slave 玩家入口: 0.0.0.0:{SLAVE_PORT}（区域 {BRAND_LOCATION}）")
    print(f"  master 封装通道: {MASTER_PORT} | backend: {BACKEND_PORT} | 源服: {SOURCE_PORT}")
    try:
        if sys.stdin.isatty():
            tui.Tui(panes).run()
        else:
            print("[headless] 无 TTY：进程已启动，日志实时写入 " + str(LOGS_DIR))
            print("[headless] Ctrl-C（SIGINT）终止全部进程")
            _drain_headless(panes)
        return 0
    finally:
        for pane in panes:
            pane.close_log()
        cleanup(procs)


def prepare() -> dict[str, str]:
    """准备 debug 实例（幂等）：迁移旧布局、部署源服、jre、构建模组、生成全部配置。"""
    source_server.deploy()  # 含旧布局迁移 + 源服基座
    _ensure_jre()
    _ensure_master_runtime()
    identity = _load_or_create_identity()
    # debug 实例每次启动重置（正常地形生成器 + 全链路确定性）：
    #   源服世界 + 快照 → 用 level-type=minecraft:normal 重新生成（seed 固定于 identity）；
    #   master 对象库/事件日志 → 全新（旧 epoch 对象与新生世界错位时不得服务陈旧基线）；
    #   slave 世界 → slave_server.deploy 已清理（空世界 + 对象库重新装载）。
    _reset_state()
    # slave 基座（server.jar/fabric-server-launch.jar/libraries/fabric-api/icbm-slave + 世界清理）
    slave_server.deploy()
    _deploy_source_mods()
    # 配置最后覆盖（python debug 运行时覆盖；含 slave_server.deploy 写入的 25598 配置）
    _write_master_config(identity)
    _write_source_config(identity)
    _write_slave_config(identity)
    return identity


def _reset_state() -> None:
    """重置三进程状态（幂等）：源服世界/快照与 master 对象库/事件日志。

    源服世界由 e2e（flat）等其它流程生成过时，level-type 对已存在世界不生效
    （26.3 DedicatedServerProperties.java:141 仅新世界使用）——debug-run 必须重置后
    以正常地形生成器（level-type=minecraft:normal + 固定 seed）重新生成。
    """
    for d in (paths.DEBUG_SOURCE / "world", paths.DEBUG_SOURCE / "icbm_snapshots"):
        if d.exists():
            shutil.rmtree(d)
            print(f"  [source] 重置世界/快照: {d}（正常地形重新生成）")
    for d in (paths.DEBUG_MASTER / "objects", paths.DEBUG_MASTER / "events.log"):
        if d.exists():
            if d.is_dir():
                shutil.rmtree(d)
            else:
                d.unlink()
            print(f"  [master] 重置状态: {d}")


# --- 目录与运行时 ---

def _ensure_jre() -> None:
    """jre 文件夹：符号链接到共享 JDK 25（606MB 不复制；失败降级复制警告）。"""
    jre = paths.DEBUG_JRE
    if jre.is_symlink() or jre.exists():
        return
    try:
        jre.symlink_to(paths.JDK_DIR, target_is_directory=True)
        print(f"jre 就绪: {jre} → {paths.JDK_DIR}")
    except OSError as exc:
        shutil.copytree(paths.JDK_DIR, jre)
        print(f"jre 符号链接失败（{exc}），已复制 JDK 到 {jre}（约 600MB，建议修复后删除重跑）")


def _ensure_master_runtime() -> None:
    """master 运行产物：gradlew installDist（application 插件完整 runtime classpath）。

    产物过期（velocity-plugin 源码/构建脚本新于产物 lib jar）时重新 installDist：
    否则提交后的协议/路由变更不会进入运行中的 master —— 真机日志实例：slave 8.2
    的 CHUNK_MATERIALIZE_REQ(0x1B) 被 8.1 master 判 unknown messageType → 断开重连
    循环，物化请求到不了源服，玩家操作全部 chunk-not-loaded 被拒。
    """
    dist = paths.VELOCITY_PLUGIN / "build" / "install" / "icbm-velocity-plugin"
    if not _master_runtime_stale(dist):
        return
    from . import jdk

    env = jdk.java_home_env()
    print("[master] 构建运行产物（gradlew installDist）...")
    shell.run(
        [str(paths.VELOCITY_PLUGIN / "gradlew"), "installDist", "--console=plain",
         *shell.jvm_proxy_args()],
        cwd=str(paths.VELOCITY_PLUGIN),
        env=env,
    )


def _master_runtime_stale(dist: Path) -> bool:
    """installDist 产物缺失或旧于 velocity-plugin 源码/构建脚本 → 需要重新构建。"""
    if not (dist / "bin" / "icbm-velocity-plugin").exists():
        return True
    jars = list((dist / "lib").glob("icbm-velocity-plugin-*.jar"))
    if not jars:
        return True
    dist_mtime = max(j.stat().st_mtime for j in jars)
    return _newest_mtime(paths.VELOCITY_PLUGIN) > dist_mtime


def _newest_mtime(root: Path) -> float:
    """递归最新 mtime（排除 build/.gradle/run——产物目录不参与过期判定）。"""
    import os as _os

    latest = 0.0
    for dirpath, dirnames, filenames in _os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", "run")]
        for fn in filenames:
            try:
                latest = max(latest, _os.path.getmtime(_os.path.join(dirpath, fn)))
            except OSError:
                pass
    return latest


def _load_or_create_identity() -> dict[str, str]:
    """持久身份（UUID/PSK/断言密钥/backend secret），跨 run 稳定便于排查。"""
    paths.DEBUG_MASTER.mkdir(parents=True, exist_ok=True)
    ident_file = paths.DEBUG_MASTER / IDENTITY_FILE
    data: dict[str, str] = {}
    if ident_file.exists():
        for line in ident_file.read_text().splitlines():
            if line and "=" in line:
                k, v = line.split("=", 1)
                data[k.strip()] = v.strip()
    needed = {
        "source_uuid": uuid.uuid4().hex,
        "slave_uuid": uuid.uuid4().hex,
        "psk": secrets.token_hex(32),
        "backend_secret": secrets.token_hex(32),
        "assertion_key": secrets.token_hex(32),
        "seed": str(random.randrange(1, 1 << 31)),  # 固定 seed：正常地形可复现
    }
    changed = False
    for key, fresh in needed.items():
        if key not in data or not data[key]:
            data[key] = fresh
            changed = True
    if changed:
        ident_file.write_text("".join(f"{k}={v}\n" for k, v in data.items()))
        try:
            os.chmod(ident_file, 0o600)  # A13：含 PSK/密钥的配置收紧权限
        except OSError:
            pass
        print(f"[identity] 已生成持久身份: {ident_file}")
    return data


# --- 配置生成（python debug 运行时覆盖；正版验证/创造模式/32 视距默认） ---

def _write_master_config(identity: dict[str, str]) -> None:
    run = paths.DEBUG_MASTER
    run.mkdir(parents=True, exist_ok=True)
    props = (
        "control.host=0.0.0.0\n"
        f"control.encapsulatedPort={MASTER_PORT}\n"
        f"control.storeRoot={run}/objects\n"
        f"control.eventLogPath={run}/events.log\n"
        "control.serverId=master-1\n"
        f"control.serverName={SERVER_NAME}\n"
        f"control.psk={identity['psk']}\n"
        f"control.roleBindings={identity['source_uuid']}=SOURCE,{identity['slave_uuid']}=SLAVE\n"
        "control.backendHost=127.0.0.1\n"
        f"control.backendPort={BACKEND_PORT}\n"
        f"control.backendForwardingSecret={identity['backend_secret']}\n"
        "control.pushWindow=64\n"
        "control.heartbeatIntervalMillis=30000\n"
        "control.lagThresholdMillis=90000\n"
        "control.auth.verifyMojang=true\n"  # 正版验证（debug 默认；运维生产必须 true）
        "control.auth.mojangTimeoutMillis=10000\n"
        f"control.auth.assertionKey={identity['assertion_key']}\n"
        # hasJoinedIp=false：复刻 26.3 prevent-proxy-connections 默认，hasJoined 不带 ip 参数
        # （slave 终止登录时看到的客户端地址在 localhost/LAN 与 Mojang 会话来源 IP 不一致）
        "control.auth.hasJoinedIp=false\n"
        f"control.allowedServerNames={SERVER_NAME}\n"
    )
    control_file = run / "control.properties"
    control_file.write_text(props)
    try:
        os.chmod(control_file, 0o600)
    except OSError:
        pass
    print(f"[master] 配置: {control_file}（auth.verifyMojang=true）")


def _write_source_config(identity: dict[str, str]) -> None:
    """源服：server.properties（正版/创造/32 视距）+ 世界 icbm-control.properties（backend 授信）。"""
    source = paths.DEBUG_SOURCE
    (source / "eula.txt").write_text("eula=true\n")
    source_props = (
        "server-port=25599\n"
        "server-ip=0.0.0.0\n"
        "online-mode=true\n"  # 正版验证（debug 默认）
        "gamemode=creative\n"  # 创造模式（debug 默认）
        "view-distance=32\n"  # 32 视距（debug 默认）
        "simulation-distance=8\n"
        "spawn-protection=0\n"
        "enable-command-block=true\n"
        "enable-rcon=true\n"
        f"rcon.port=25579\n"
        f"rcon.password={secrets.token_hex(8)}\n"
        "pause-when-empty-seconds=0\n"  # 无玩家也持续 tick（脏标记 → 快照发布依赖 Server thread）
        "white-list=false\n"  # 白名单权威在 master 后端（源服）；26.3 默认 true 是坑，显式关闭
        # 正常地形生成器（debug 要求）：level-type 仅对新世界生效（26.3
        # DedicatedServerProperties.java:141），故 _reset_state 先清世界；固定 seed 可复现
        f"level-type=minecraft:normal\n"
        "generate-structures=true\n"
        f"level-seed={identity['seed']}\n"
    )
    (source / "server.properties").write_text(source_props)
    world = source / "world"
    world.mkdir(parents=True, exist_ok=True)
    source_control = world / "icbm-control.properties"
    source_control.write_text(
        f"icbm.backend.forwardingSecret={identity['backend_secret']}\n"
        "icbm.backend.bind=0.0.0.0\n"
        f"icbm.backend.port={BACKEND_PORT}\n"
        "icbm.control.sourceServerId=source-server\n"
        f"icbm.control.serverName={SERVER_NAME}\n"
    )
    try:
        os.chmod(source_control, 0o600)
    except OSError:
        pass
    print(f"[source] 配置: {source / 'server.properties'} + {source_control}")


def _write_slave_config(identity: dict[str, str]) -> None:
    """slave：server.properties（0.0.0.0:25565，正版/创造/32 视距）+ 世界 icbm-control.properties。"""
    slave = paths.DEBUG_SLAVE
    slave.mkdir(parents=True, exist_ok=True)
    (slave / "eula.txt").write_text("eula=true\n")
    slave_props = (
        f"server-ip=0.0.0.0\n"
        f"server-port={SLAVE_PORT}\n"
        "online-mode=true\n"  # 正版验证：slave 终止登录握手 → master 唯一验证（HIERARCHY §2）
        "gamemode=creative\n"  # 创造模式（debug 默认）
        "view-distance=32\n"  # 32 视距（debug 默认）
        "simulation-distance=8\n"
        "spawn-protection=0\n"
        "enable-command-block=true\n"
        "enable-rcon=true\n"
        f"rcon.port={RCON_PORT}\n"
        f"rcon.password={secrets.token_hex(8)}\n"
        "pause-when-empty-seconds=0\n"  # 缓存服务器必须持续 tick（快照/区块刷新依赖）
        "white-list=false\n"  # slave 不设白名单（白名单权威 = master 后端源服）；26.3 默认 true
    )
    (slave / "server.properties").write_text(slave_props)
    world = slave / "world"
    world.mkdir(parents=True, exist_ok=True)
    slave_control = world / "icbm-control.properties"
    slave_control.write_text(
        "control.masterHost=127.0.0.1\n"
        f"control.masterPort={MASTER_PORT}\n"
        f"control.psk={identity['psk']}\n"
        f"control.uuid={identity['slave_uuid']}\n"
        f"control.serverName={SERVER_NAME}\n"
        "control.storeRoot=icbm-objects\n"
        "control.dimensions=minecraft:overworld\n"
        "control.viewDistance=32\n"
        "control.readTimeoutMillis=30000\n"
        "control.reconnectBackoffMillis=3000\n"
        "control.heartbeatIntervalMillis=8000\n"
        "control.selfcheck=true\n"
        f"icbm.brandLocation={BRAND_LOCATION}\n"  # F3 Brand → ICBM Caching Server (icbm:lan)
        # LLM 翻译（可选）：apiKey 为空 → 翻译功能关闭（fail closed）
        # llm.baseUrl=https://api.deepseek.com
        # llm.model=deepseek-chat
        # llm.apiKey=
        # llm.languages=zh_hk,zh_tw,en,ja,de,fr,zh_cn
    )
    try:
        os.chmod(slave_control, 0o600)
    except OSError:
        pass
    print(f"[slave] 配置: {slave / 'server.properties'} + {slave_control}"
          f"（0.0.0.0:{SLAVE_PORT}，区域 {BRAND_LOCATION}）")


def _deploy_source_mods() -> None:
    """构建并部署源服模组：icbm-source + fabric-api（slave 模组由 slave_server.deploy 处理）。"""
    from . import modbuild

    modbuild._run("fabric-source", "build", "-x", "test")
    source_mods = paths.DEBUG_SOURCE / "mods"
    source_mods.mkdir(parents=True, exist_ok=True)
    source_server.ensure_fabric_api(source_mods)
    _copy_latest_jar("fabric-source", "icbm-source", source_mods)
    print(f"[mods] source: {sorted(p.name for p in source_mods.glob('*.jar'))}")


def _copy_latest_jar(component: str, prefix: str, mods_dir: Path) -> None:
    jars = sorted((paths.component_root(component) / "build" / "libs").glob(f"{prefix}-*.jar"))
    jars = [j for j in jars if "sources" not in j.name]
    if not jars:
        raise RuntimeError(f"{component} 构建产物缺失（build/libs/{prefix}-*.jar）")
    latest = jars[-1]
    for stale in mods_dir.glob(f"{prefix}-*.jar"):
        if stale.name != latest.name:
            stale.unlink()
    shutil.copyfile(latest, mods_dir / latest.name)


# --- 进程编排 ---

def start_processes(identity: dict[str, str]) -> list[subprocess.Popen]:
    """启动三进程（source → master → 等源注册 → slave）。

    slave 必须在 master↔源服建立后启动：master 对「源尚未注册」的 HELLO 直接拒绝且
    不自动重连（协议设计：源未注册拒 slave，fail closed）；e2e 同款顺序。
    """
    java = str(paths.DEBUG_JRE / "bin" / "java")
    env = dict(shell.proxy_env())
    env["JAVA_HOME"] = str(paths.DEBUG_JRE)
    env["PATH"] = f"{paths.DEBUG_JRE}/bin:" + env["PATH"]

    procs: list[subprocess.Popen] = []
    # 1) 真后端（source；先启动，master 后连 backend 11430）
    procs.append(_popen([java, f"-Xmx{SOURCE_HEAP}", "-jar", "fabric-server-launch.jar", "nogui"],
                        cwd=paths.DEBUG_SOURCE, env=env, label="source"))
    # 2) master（installDist 产物，无 gradle daemon 残留）
    master_env = dict(env)
    master_env["ICBM_CONTROL_CONFIG"] = str(paths.DEBUG_MASTER / "control.properties")
    # JVM 代理参数（hasJoined 走 Mojang session server；AGENTS.md 联网遵循代理变量）
    proxy_args = shell.jvm_proxy_args()
    if proxy_args:
        master_env["JAVA_OPTS"] = " ".join(proxy_args)
    master_bin = (paths.VELOCITY_PLUGIN / "build" / "install" / "icbm-velocity-plugin"
                  / "bin" / "icbm-velocity-plugin")
    procs.append(_popen([str(master_bin)], cwd=paths.VELOCITY_PLUGIN, env=master_env, label="master"))
    # 3) slave（玩家入口 25565；256M 是生产约束，debug 实例放宽）——等源注册
    _wait_for_source_registration(procs[1], timeout=180)
    procs.append(_popen([java, f"-Xmx{SLAVE_HEAP}", "-jar", "fabric-server-launch.jar", "nogui"],
                        cwd=paths.DEBUG_SLAVE, env=env, label="slave"))
    return procs


def _wait_for_source_registration(master_proc: subprocess.Popen, timeout: float) -> bool:
    """轮询 master stdout 直到出现「源服 backend 已连接」（同时 tee 到日志文件）。

    超时不阻断（slave 仍启动，但 HELLO 可能被拒 → fail closed，TUI 可见）。
    """
    import selectors

    log_path = LOGS_DIR / "master.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = log_path.open("ab")
    buf = b""
    deadline = time.time() + timeout
    sel = selectors.DefaultSelector()
    sel.register(master_proc.stdout, selectors.EVENT_READ)
    try:
        while time.time() < deadline:
            for key, _mask in sel.select(timeout=1.0):
                try:
                    data = os.read(master_proc.stdout.fileno(), 1 << 15)
                except OSError:
                    data = b""
                if not data:
                    sel.unregister(master_proc.stdout)
                    break
                log.write(data)
                log.flush()
                buf += data
                if "源服 backend 已连接".encode() in buf:
                    print("[master] 源服 backend 已连接（slave 可连接）")
                    return True
            if master_proc.poll() is not None:
                break
        print("[warn] 等待源服注册超时（slave 仍启动，HELLO 可能被拒，fail closed）")
        return False
    finally:
        sel.close()
        log.close()


def _popen(cmd: list[str], *, cwd: Path, env: dict[str, str], label: str) -> subprocess.Popen:
    proc = subprocess.Popen(
        cmd, cwd=str(cwd), env=env, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    print(f"  [{label}] 启动 pid={proc.pid}（{cmd[0]} {' '.join(cmd[1:])}）")
    return proc


def _drain_headless(panes: list) -> None:
    """headless 模式：select 轮询进程输出（防管道填满阻塞），SIGINT 触发清理。

    Pane.feed 同时把输出 tee 到日志文件（与 TUI 模式同一落盘语义）。
    """
    import selectors

    sel = selectors.DefaultSelector()
    for pane in panes:
        if pane.proc.stdout is not None:
            sel.register(pane.proc.stdout, selectors.EVENT_READ, pane)
    got = False

    def _on_sigint(_sig, _frame) -> None:  # noqa: ANN001
        nonlocal got
        got = True

    old = signal.signal(signal.SIGINT, _on_sigint)
    try:
        while not got:
            for key, _mask in sel.select(timeout=0.5):
                pane = key.data
                try:
                    data = os.read(pane.proc.stdout.fileno(), 1 << 15)
                except OSError:
                    data = b""
                if data:
                    pane.feed(data)
                else:
                    try:
                        sel.unregister(pane.proc.stdout)
                    except (KeyError, ValueError):
                        pass
            if all(pane.proc.poll() is not None for pane in panes):
                print("[headless] 全部进程已退出")
                return
    finally:
        signal.signal(signal.SIGINT, old)


def cleanup(procs: list[subprocess.Popen]) -> None:
    """终止全部进程（terminate → kill），返回前确认退出。"""
    for proc in procs:
        if proc.poll() is None:
            proc.terminate()
    for proc in procs:
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)
    print("debug 实例已停止（日志保留在 " + str(LOGS_DIR) + "）")

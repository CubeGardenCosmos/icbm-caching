"""里程碑 6 端到端验证：源服 setblock → 快照 → master → slave 订阅 → 玩家经 slave 看到。

三个真实 Java 进程编排（源服 + master + slave），验证：
1. 源服 setblock → 「已提交快照」日志 → master 对象库有快照；
2. slave 出生点区块装载命中快照（「已装载快照」日志）；
3. RCON /data get block 读取注入区块 = setblock 目标方块（"玩家经 slave 看到"的无头代理）；
4. fail closed：停止 master → slave 日志「master 断开（服务闸门关闭）」。
全部成功返回 0；任何一步失败返回非 0 并打印证据。
"""

from __future__ import annotations

import socket
import struct
import subprocess
import time
from pathlib import Path

from ..infra import paths, shell
from . import control, jdk, slave_server

SOURCE_PORT = 25599
SLAVE_PORT = 25598
RCON_PORT = 25580
MASTER_PORT = 11420
BACKEND_PORT = 11430

SOURCE_LOG = paths.WORKSPACE / "e2e-source.log"
MASTER_LOG = paths.WORKSPACE / "e2e-master.log"
SLAVE_LOG = paths.WORKSPACE / "e2e-slave.log"
SETBLOCK_PREP = "execute in minecraft:overworld run forceload add 0 0"
SETBLOCK = "execute in minecraft:overworld positioned 0 64 0 run setblock 0 64 0 minecraft:diamond_block"


def _load_master_props() -> dict[str, str]:
    props: dict[str, str] = {}
    path = paths.CONTROL_RUN / "control.properties"
    if not path.exists():
        control.write_control_config()
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    # 旧格式（mTLS 时代）或缺封装通道键 → 重新生成（新 psk/backendSecret，防不一致）
    if not props.get("control.encapsulatedPort") or not props.get("control.psk"):
        print("  [master] 检测到旧/缺键配置，重新生成 control.properties")
        control.write_control_config()
        return _load_master_props()
    return props


def _write_source_control_config(master_props: dict[str, str]) -> None:
    """源服 icbm-control.properties：backend 授信模式（master 主动连源服 11430）。"""
    world = paths.SERVER_DIR / "world"
    world.mkdir(parents=True, exist_ok=True)
    secret = master_props.get("control.backendForwardingSecret", "")
    if not secret:
        raise RuntimeError("master control.properties 缺少 backendForwardingSecret")
    (world / "icbm-control.properties").write_text(
        f"icbm.backend.forwardingSecret={secret}\n"
        f"icbm.backend.bind=0.0.0.0\n"
        f"icbm.backend.port={BACKEND_PORT}\n"
        "icbm.control.sourceServerId=source-server\n"
        "icbm.control.serverName=lobby-1\n"
    )
    print(f"源服控制配置: {world / 'icbm-control.properties'}（backend 授信 {BACKEND_PORT}）")


def _wait_port(host: str, port: int, timeout: float, label: str) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1):
                print(f"  [{label}] 端口就绪: {host}:{port}")
                return
        except OSError:
            time.sleep(1)
    raise RuntimeError(f"[{label}] 端口 {host}:{port} 未在 {timeout}s 内就绪")


def _start(cmd: list[str], log: Path, label: str, cwd: Path, env: dict[str, str]) -> subprocess.Popen:
    with log.open("ab") as fh:
        proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT, text=False,
                                cwd=str(cwd), env=env, stdin=subprocess.PIPE)
    print(f"  [{label}] 启动 pid={proc.pid}（日志 {log}）")
    return proc


def _wait_log(log: Path, needle: str, timeout: float, label: str) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if log.exists():
            text = log.read_text(errors="replace")
            if needle in text:
                print(f"  [{label}] 命中日志: {needle}")
                return True
        time.sleep(2)
    tail = log.read_text(errors="replace")[-2000:] if log.exists() else "(无日志)"
    raise RuntimeError(f"[{label}] 超时未命中: {needle}\n--- 日志尾部 ---\n{tail}")


def _send_source_command(proc: subprocess.Popen, command: str) -> None:
    """向源服控制台 stdin 注入命令（DedicatedServer 从 stdin 读控制台命令）。"""
    if proc.stdin is None:
        raise RuntimeError("源服进程无 stdin（无法注入命令）")
    proc.stdin.write((command + "\n").encode())
    proc.stdin.flush()


class RconClient:
    """Minecraft RCON 协议（4 字节小端长度 + requestId + type + payload + \\0\\0）。"""

    def __init__(self, host: str, port: int, password: str):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.request_id = 1
        rid, rkind, _ = self._send(3, password.encode())
        # 26.3 官方 RconClient.java:84：登录成功 send(requestid, 2, "")；失败 sendAuthFailure → requestId=-1
        if rid != 1 or rkind != 2:
            raise RuntimeError(f"RCON 登录失败（requestId={rid} type={rkind}）")

    def command(self, cmd: str) -> str:
        _, rkind, data = self._send(2, cmd.encode())
        # 26.3 官方 RconClient.java:19/125：命令响应 type=0（SERVERDATA_RESPONSE_VALUE）
        if rkind != 0:
            raise RuntimeError(f"RCON 命令响应 type 异常: {rkind}")
        return data.decode(errors="replace")

    def _send(self, kind: int, payload: bytes) -> tuple[int, int, bytes]:
        req_id = self.request_id
        self.request_id += 1
        body = struct.pack("<ii", req_id, kind) + payload + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(body)) + body)
        header = self._read_exact(4)
        (length,) = struct.unpack("<i", header)
        data = self._read_exact(length)
        rid, rkind = struct.unpack("<ii", data[:8])
        if rid != req_id:
            raise RuntimeError(f"RCON 响应 requestId 不匹配: {rid} != {req_id}")
        return rid, rkind, data[8:-2]

    def _read_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON 连接中断")
            buf += chunk
        return buf

    def close(self) -> None:
        self.sock.close()


def _rcon_password() -> str:
    for line in (slave_server.SLAVE_SERVER / "server.properties").read_text().splitlines():
        if line.startswith("rcon.password="):
            return line.split("=", 1)[1]
    raise RuntimeError("slave server.properties 缺少 rcon.password")


def run_e2e() -> int:
    print("=== 里程碑 6 端到端：源服 setblock → 快照 → master → slave → 玩家视角 ===")
    master_props = _load_master_props()
    env = jdk.java_home_env()

    procs: list[subprocess.Popen] = []
    try:
        # 0) 重建 fabric-source 并部署新 jar 到源服 mods；清空源服世界（全新 epoch，确定性）
        from . import modbuild

        modbuild._run("fabric-source", "build", "-x", "test")
        import shutil

        source_jar = sorted((paths.component_root("fabric-source") / "build" / "libs").glob("icbm-source-*.jar"))
        source_jar = [j for j in source_jar if "sources" not in j.name][-1]
        shutil.copyfile(source_jar, paths.SERVER_DIR / "mods" / source_jar.name)
        for stale in (paths.SERVER_DIR / "mods").glob("icbm-source-*.jar"):
            if stale.name != source_jar.name:
                stale.unlink()
        for d in (paths.SERVER_DIR / "world", paths.SERVER_DIR / "icbm_snapshots"):
            if d.exists():
                shutil.rmtree(d)
                print(f"  [源服] 清理历史世界: {d}（全新 epoch）")
        # master 状态与源服世界同步重置（全新 epoch 从 1 起；events.log 截断容错也在此验证）
        for d in (paths.CONTROL_RUN / "objects", paths.CONTROL_RUN / "events.log"):
            if d.exists():
                if d.is_dir():
                    shutil.rmtree(d)
                else:
                    d.unlink()
                print(f"  [master] 清理历史状态: {d}")

        # 1) 源服先启动（backend 监听 11430；master 后连）
        _write_source_control_config(master_props)
        procs.append(_start(["java", "-Xmx1G", "-jar", "fabric-server-launch.jar", "nogui"],
                            SOURCE_LOG, "源服", paths.SERVER_DIR, env))
        _wait_port("127.0.0.1", SOURCE_PORT, 180, "源服")
        _wait_port("127.0.0.1", BACKEND_PORT, 60, "源服 backend")
        _wait_log(SOURCE_LOG, "Done (", 60, "源服就绪")

        # 2) master（独立进程）
        master_env = dict(env)
        master_env["ICBM_CONTROL_CONFIG"] = str(paths.CONTROL_RUN / "control.properties")
        procs.append(_start([str(paths.VELOCITY_PLUGIN / "gradlew"), "run", "--console=plain"],
                            MASTER_LOG, "master", paths.VELOCITY_PLUGIN, master_env))
        _wait_port("127.0.0.1", MASTER_PORT, 120, "master")
        _wait_log(MASTER_LOG, "源服 backend 已连接", 60, "master↔源服")

        # 3) forceload 目标区块 → setblock → 快照发布 → master 对象库
        _send_source_command(procs[0], SETBLOCK_PREP)
        _send_source_command(procs[0], SETBLOCK)
        _wait_log(SOURCE_LOG, "已提交快照", 60, "源服快照")
        objects = paths.CONTROL_RUN / "objects"
        if not any(objects.rglob("snapshot.nbt.gz")):
            raise RuntimeError(f"master 对象库无快照: {objects}")
        print(f"  [master] 对象库快照存在: {objects}")

        # 4) slave（连接 master 封装通道；出生点区块装载命中快照）
        slave_server.deploy()
        slave_server.write_slave_control_config(master_props)
        procs.append(_start(["java", "-Xmx256M", "-jar", "fabric-server-launch.jar", "nogui"],
                            SLAVE_LOG, "slave", slave_server.SLAVE_SERVER, env))
        _wait_port("127.0.0.1", SLAVE_PORT, 180, "slave")
        _wait_log(SLAVE_LOG, "master 已连接", 60, "slave↔master")
        _wait_log(SLAVE_LOG, "已装载快照", 60, "slave 快照注入")

        # 5) RCON：玩家视角 = /data get block 读取注入区块
        _wait_port("127.0.0.1", RCON_PORT, 60, "slave RCON")
        rcon = RconClient("127.0.0.1", RCON_PORT, _rcon_password())
        try:
            result = rcon.command("execute if block 0 64 0 minecraft:diamond_block")
            print(f"  [RCON] execute if block 0 64 0 diamond_block → {result!r}")
            if "Test passed" not in result and "Success" not in result:
                raise RuntimeError(f"注入区块方块不是 diamond_block: {result!r}")
            print("  [RCON] 玩家经 slave 看到的方块 = 源服 setblock 目标（端到端命中）")
        finally:
            rcon.close()

        # 6) fail closed：停止 master → slave 服务闸门关闭
        proc = procs.pop(1)  # master
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        _wait_log(SLAVE_LOG, "master 断开", 60, "fail closed")

        print("=== 端到端验证通过 ===")
        return 0
    finally:
        for proc in procs:
            proc.terminate()
        for proc in procs:
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()

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
    world = paths.DEBUG_SOURCE / "world"
    world.mkdir(parents=True, exist_ok=True)
    secret = master_props.get("control.backendForwardingSecret", "")
    if not secret:
        raise RuntimeError("master control.properties 缺少 backendForwardingSecret")
    source_control = world / "icbm-control.properties"
    source_control.write_text(
        f"icbm.backend.forwardingSecret={secret}\n"
        f"icbm.backend.bind=0.0.0.0\n"
        f"icbm.backend.port={BACKEND_PORT}\n"
        "icbm.control.sourceServerId=source-server\n"
        "icbm.control.serverName=lobby-1\n"
    )
    # A13 审计修复：含 backend secret 的配置权限收紧
    import os as _os
    try:
        _os.chmod(source_control, 0o600)
    except OSError:
        pass
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


def _probe_source_login(port: int) -> str:
    """python 模拟 26.3 客户端登录握手（Handshake + Login Start），读 8 秒看服务端是否回包。
    协议常量仅作探测（帧 VarInt + 包 id 0x00）；26.3 服务端正确回 LoginCompression。"""
    import hashlib

    def varint(n: int) -> bytes:
        out = b""
        while True:
            b = n & 0x7F
            n >>= 7
            if n:
                out += bytes([b | 0x80])
            else:
                out += bytes([b])
                return out

    def string(s: str) -> bytes:
        b = s.encode()
        return varint(len(b)) + b

    def frame(payload: bytes) -> bytes:
        return varint(len(payload)) + payload

    handshake = frame(b"\x00" + varint(1073742154) + string("127.0.0.1")
                      + struct.pack(">H", port) + varint(2))
    hello = frame(b"\x00" + string("probe-player") + hashlib.md5(b"probe").digest())
    with socket.create_connection(("127.0.0.1", port), timeout=5) as s:
        s.settimeout(8)
        s.sendall(handshake)
        s.sendall(hello)
        chunks = []
        try:
            while len(chunks) < 3:
                data = s.recv(4096)
                if not data:
                    break
                chunks.append(data)
        except socket.timeout:
            pass
        total = sum(len(c) for c in chunks)
        first = chunks[0][:12].hex() if chunks else ""
        return f"收到 {total} 字节（{len(chunks)} 段，首段前 12B={first}）"


def _send_source_command(proc: subprocess.Popen, command: str) -> None:
    """向源服控制台 stdin 注入命令（DedicatedServer 从 stdin 读控制台命令）。"""
    if proc.stdin is None:
        raise RuntimeError("源服进程无 stdin（无法注入命令）")
    proc.stdin.write((command + "\n").encode())
    proc.stdin.flush()


def _wait_snapshot_settle(log: Path, timeout: float = 90, settle: float = 15) -> None:
    """等快照洪水平息（slave 已装载快照条数 settle 秒内不再增长）。源服 normal 世界初始
    物化（80 区块）持续发布多 rev 快照，slave 主线程逐 tick 刷新已装载区块——物化期
    RCON 延迟大（e2e 暴露：主线程忙导致 execute if block 超时）；等平稳后再做操作验证。"""
    deadline = time.time() + timeout
    prev = -1
    stable_since = None
    while time.time() < deadline:
        count = log.read_text(errors="replace").count("已装载快照") if log.exists() else 0
        if count == prev:
            if stable_since is None:
                stable_since = time.time()
            elif time.time() - stable_since >= settle:
                print(f"  [快照平稳] 已装载快照 {count} 条，{settle}s 未增长（物化期结束）")
                return
        else:
            stable_since = None
            prev = count
        time.sleep(2)
    raise RuntimeError(f"[快照平稳] {timeout}s 内未稳定（快照持续增长: {prev}）")


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
    # 截断历史日志：_wait_log 按内容匹配，残留的旧运行行（如旧的「Done (」「已提交快照」）
    # 会令等待瞬间命中，导致后续检查与真实运行错位（debug-run 重启暴露）
    for log in (SOURCE_LOG, MASTER_LOG, SLAVE_LOG):
        log.write_text("")
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
        shutil.copyfile(source_jar, paths.DEBUG_SOURCE / "mods" / source_jar.name)
        for stale in (paths.DEBUG_SOURCE / "mods").glob("icbm-source-*.jar"):
            if stale.name != source_jar.name:
                stale.unlink()
        for d in (paths.DEBUG_SOURCE / "world", paths.DEBUG_SOURCE / "icbm_snapshots"):
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

        # 1) 源服先启动（backend 监听 11430；master 后连）。测试配置幂等写入（含
        #    online-mode=false——源服身份验证在 master（auth-gateway），虚拟玩家回环连接
        #    免加密；debug-run 可能留下 online-mode=true 的 server.properties，e2e 必须覆盖）
        from . import server as _server_module
        _server_module._ensure_test_config()
        _write_source_control_config(master_props)
        procs.append(_start(["java", "-Xmx1G", "-jar", "fabric-server-launch.jar", "nogui"],
                            SOURCE_LOG, "源服", paths.DEBUG_SOURCE, env))
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

        slave_server.deploy()
        slave_server.write_slave_control_config(master_props)
        # 堆 256M 是架构约束（ARCHITECTURE §1.2/§3.1）；9.4 e2e 观察：master 重放 500+ 快照
        # 事件（源服物化 80 区块 + 初始 dirty）时 256M 临界 OOM（9.5 有 256M 压测项）。此处用
        # 环境变量 ICBM_SLAVE_XMX 覆盖（默认 256M），9.5 回归默认值并优化快照装载内存。
        import os as _os
        slave_xmx = _os.environ.get("ICBM_SLAVE_XMX", "256M")
        procs.append(_start(["java", f"-Xmx{slave_xmx}", "-jar", "fabric-server-launch.jar", "nogui"],
                            SLAVE_LOG, "slave", slave_server.SLAVE_SERVER, env))
        _wait_port("127.0.0.1", SLAVE_PORT, 180, "slave")
        _wait_log(SLAVE_LOG, "master 已连接", 60, "slave↔master")
        _wait_log(SLAVE_LOG, "已装载快照", 60, "slave 快照注入")

        # 5) RCON：玩家视角 = execute if block 读取注入区块（里程碑 6 端到端命中）
        _wait_port("127.0.0.1", RCON_PORT, 60, "slave RCON")
        # 源服 normal 世界初始物化（80 区块）产生快照洪水：等平稳后再执行 RCON 命令（物化期
        # slave 主线程刷新已装载区块，command 排队 → RCON 5s 超时——e2e 暴露 94g/94k）
        _wait_snapshot_settle(SLAVE_LOG)
        rcon = RconClient("127.0.0.1", RCON_PORT, _rcon_password())
        try:
            # 源服 normal 世界 seed 随机 → spawn 不保证在 chunk 0,0；execute if block 对未装载
            # 区块读空气——先 slave 强制装载 0,0。注意 0,0 可能已被生成 EMPTY（forceload 不
            # 重装，快照 refresh 已消费）→ forceload 后破坏→恢复产生两次新 rev 强制 slave 刷新
            rcon.command("forceload add 0 0")
            _send_source_command(procs[0],
                    "execute in minecraft:overworld positioned 0 64 0 run setblock 0 64 0 minecraft:air")
            _send_source_command(procs[0], SETBLOCK)
            # force 装载出生点区块是异步的：轮询等 diamond 出现（注入/刷新完成）
            result = ""
            deadline = time.time() + 90
            while time.time() < deadline:
                result = rcon.command("execute if block 0 64 0 minecraft:diamond_block")
                if "Test passed" in result or "Success" in result:
                    break
                time.sleep(2)
            print(f"  [RCON] execute if block 0 64 0 diamond_block → {result!r}")
            if "Test passed" not in result and "Success" not in result:
                raise RuntimeError(f"注入区块方块不是 diamond_block: {result!r}")
            print("  [RCON] 玩家经 slave 看到的方块 = 源服 setblock 目标（端到端命中）")
        finally:
            rcon.close()

        # 源服 normal 世界初始物化（80 区块）产生快照洪水：等平稳后再做操作验证（物化期
        # slave 主线程刷新已装载区块，RCON 延迟大——e2e 暴露 94g）
        _wait_snapshot_settle(SLAVE_LOG)

        # 6) 乐观操作闭环 + 阶段 9.2 快路径时延度量：slave 经 RCON 触发 BREAK → master →
        #    源服主线程裁决 → OPERATION_COMMITTED → F1 就地应用 canonicalState（玩家视角立即
        #    air，不再等快照往返）→ 快照管道确认（refreshLoaded 差异为空幂等）
        rcon = RconClient("127.0.0.1", RCON_PORT, _rcon_password())
        try:
            def operate_and_measure(x: int, label: str, limit: float) -> float:
                """提交操作并高频轮询玩家视角一致；返回时延秒数（热 ≤200ms）。"""
                t0 = time.monotonic()
                result = rcon.command(f"icbmslave operate {x} 64 0 break")
                if "已提交操作" not in result:
                    raise RuntimeError(f"slave 操作提交失败: {result!r}")
                deadline = time.monotonic() + 10
                hit = None
                while time.monotonic() < deadline:
                    verify = rcon.command(f"execute if block {x} 64 0 minecraft:air")
                    if "Test passed" in verify or "Success" in verify:
                        hit = time.monotonic()
                        break
                    time.sleep(0.05)
                if hit is None:
                    raise RuntimeError(f"[{label}] 操作后 {x},64,0 不是 air: {verify!r}")
                elapsed = hit - t0
                print(f"  [RCON] [{label}] 操作 → 玩家视角一致: {elapsed * 1000:.0f}ms（limit {limit * 1000:.0f}ms）")
                if elapsed > limit:
                    raise RuntimeError(f"[{label}] 时延超标: {elapsed * 1000:.0f}ms > {limit * 1000:.0f}ms")
                return elapsed

            # 冷/热路径：首次操作目标 0,0 已装载（出生点 + warm ticket）
            first = operate_and_measure(0, "首次-管线热身", 1.0)
            # 恢复目标方块（源服 setblock → 快照 → slave；0,0 warm ticket 内不卸载）
            _send_source_command(procs[0],
                    "execute in minecraft:overworld positioned 0 64 0 run setblock 0 64 0 minecraft:diamond_block")
            # 等 slave 视角恢复 diamond（快照往返 + refreshLoaded；避免 warm 复操作 break 空气竞态）
            deadline = time.monotonic() + 30
            restored = None
            while time.monotonic() < deadline:
                restored = rcon.command("execute if block 0 64 0 minecraft:diamond_block")
                if "Test passed" in restored or "Success" in restored:
                    break
                time.sleep(0.05)
            if "Test passed" not in restored and "Success" not in restored:
                raise RuntimeError(f"恢复快照未达 slave（0,64,0 非 diamond）: {restored!r}")
            # 同 chunk 第二次操作（F2 warm ticket 30s 内不再重载）：热路径 ≤200ms
            second = operate_and_measure(0, "热路径-warm", 0.2)
            print(f"  [度量] 首次 {first * 1000:.0f}ms / warm 复操作 {second * 1000:.0f}ms（9.2 验收 热≤200ms）")
            # F1 直接证据：COMMITTED 就地应用日志（不再依赖快照往返）
            _wait_log(SLAVE_LOG, "操作已提交", 60, "slave 操作终态")
            _wait_log(SLAVE_LOG, "已装载快照", 60, "快照确认（refreshLoaded 幂等）")
            print("  [RCON] 玩家经 slave 看到的操作效果 = 源服权威裁决结果（乐观操作闭环 + F1 快路径）")
        finally:
            rcon.close()

        # 6b) 拒绝路径即时回显回归：源服造 bedrock（不可破坏）→ slave BREAK → REJECTED →
        #     RESYNC 落回 committed（bedrock 仍展示），不产生幽灵回显。位置选 17,64,1
        #     （chunk 1,0，与 6 步操作 chunk 0,0 不同——单 pending 约束按 chunk，同 chunk
        #     未决会拒绝）
        _send_source_command(procs[0], "execute in minecraft:overworld run forceload add 1 0")
        _send_source_command(procs[0], "execute in minecraft:overworld positioned 17 64 1 run setblock 17 64 1 minecraft:bedrock")
        _wait_log(SLAVE_LOG, "已装载快照", 60, "bedrock 快照到达 slave")
        rcon = RconClient("127.0.0.1", RCON_PORT, _rcon_password())
        try:
            # slave 侧装载 1,0（RCON execute if block 对未装载区块读空气，无法验证）
            result = rcon.command("forceload add 1 0")
            print(f"  [RCON] slave forceload 1 0 → {result!r}")
            result = rcon.command("icbmslave operate 17 64 1 break")
            print(f"  [RCON] icbmslave operate 17 64 1 break → {result!r}")
            if "已提交操作" not in result:
                raise RuntimeError(f"slave 操作提交失败: {result!r}")
            _wait_log(SLAVE_LOG, "操作被拒", 60, "slave 拒绝终态")
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                verify = rcon.command("execute if block 17 64 1 minecraft:bedrock")
                if "Test passed" in verify or "Success" in verify:
                    break
                time.sleep(0.05)
            print(f"  [RCON] 拒绝路径回显 bedrock → {verify!r}")
            if "Test passed" not in verify and "Success" not in verify:
                raise RuntimeError(f"拒绝后 17,64,1 不是 bedrock（RESYNC 未回显）: {verify!r}")
            print("  [RCON] 拒绝路径即时回显（源服不可破坏裁决 → slave 落回 committed，无幽灵方块）")
        finally:
            rcon.close()

        # 6c) 9.3 聊天直传闭环：slave RCON 模拟玩家聊天 → CHAT_RELAY → master → 源服权威
        #     广播（控制台可见）→ CHAT_BROADCAST 下行 → slave 送达（本地不再广播，权威副本）
        rcon = RconClient("127.0.0.1", RCON_PORT, _rcon_password())
        try:
            chat_marker = f"e2e-9.3-chat-{int(time.time())}"
            result = rcon.command(f"icbmslave chat {chat_marker}")
            print(f"  [RCON] icbmslave chat → {result!r}")
            if "已上行聊天" not in result:
                raise RuntimeError(f"slave 聊天上行失败: {result!r}")
            # 源服控制台可见（权威广播日志）
            _wait_log(SOURCE_LOG, f"权威聊天广播: rcon-chatter: {chat_marker}", 30, "源服控制台可见")
            # master 分发所有 slave 日志
            _wait_log(MASTER_LOG, f"权威聊天广播: rcon-chatter: {chat_marker}", 30, "master 分发")
            # slave 下行送达（本地广播权威副本 + 送达日志）
            _wait_log(SLAVE_LOG, f"下行权威广播送达: rcon-chatter: {chat_marker}", 30, "slave 下行送达")
            print("  [RCON] 聊天直传闭环：slave 上行 → 源服权威广播（控制台可见）→ 下行分发 → slave 送达")
        finally:
            rcon.close()

        # 6d) 9.4 权威会话 v2 背包/互动直传：slave 模拟玩家 use-item（右键放置）→
        #     MC_PASSTHROUGH 上行 → master → 源服虚拟玩家（回环连接源服游戏端口，26.3 标准
        #     登录/配置/play）vanilla 权威处理 → 快照管道 → slave 视角验证放置结果来自源服
        # 首个 passthrough 报文触发虚拟玩家创建（VirtualPlayerSession 由 uuid 首报文物化）
        rcon = RconClient("127.0.0.1", RCON_PORT, _rcon_password())
        try:
            result = rcon.command("icbmslave passthrough useitem 1 64 0 up")
            print(f"  [RCON] icbmslave passthrough useitem 1 64 0 up → {result!r}")
            if "已直传 use-item" not in result:
                raise RuntimeError(f"slave 直传上行失败: {result!r}")
        finally:
            rcon.close()
        # 对照实验：python 模拟 26.3 登录源服（Handshake + Login Start），验证源服对标准
        # 客户端握手是否回 LoginCompression（若源服正常而虚拟玩家收不到 → 客户端侧问题）
        time.sleep(2)
        try:
            probe = _probe_source_login(SOURCE_PORT)
            print(f"  [对照] python 模拟登录源服 → {probe}")
        except Exception as exc:
            print(f"  [对照] python 模拟登录源服异常: {exc}")
        # 虚拟玩家登录完成（回环连接源服游戏端口 + 26.3 登录/配置/play）
        _wait_log(SOURCE_LOG, "虚拟玩家已就绪", 60, "源服虚拟玩家就绪")
        # 虚拟玩家名从源服日志解析（icbm-<uuid前8>，uuid 与 operate 命令一致）
        import re as _re
        source_text = SOURCE_LOG.read_text(errors="replace")
        m = _re.search(r"虚拟玩家已就绪: (icbm-[0-9a-f]{8})", source_text)
        if not m:
            raise RuntimeError(f"源服日志未找到虚拟玩家名: {source_text[-2000:]}")
        vp_name = m.group(1)
        # 源服 give 虚拟玩家方块（主手）；tp 到目标附近（虚拟玩家位置 = 源服 spawn，
        # normal 世界 spawn 随机——use-item 互动距离 4.5 格内才可达）
        _send_source_command(procs[0], f"tp {vp_name} 1 64 1")
        _send_source_command(procs[0], f"give {vp_name} minecraft:dirt 1")
        # 诊断：give 后虚拟玩家主手物品（确认 give 生效）
        time.sleep(2)
        _send_source_command(procs[0], f"data get entity {vp_name} Inventory")
        time.sleep(1)
        rcon = RconClient("127.0.0.1", RCON_PORT, _rcon_password())
        try:
            # 点击 (1,64,0)（flat 世界草地）方向 UP → 放置到 (1,65,0)
            result = rcon.command("icbmslave passthrough useitem 1 64 0 up")
            print(f"  [RCON] icbmslave passthrough useitem 1 64 0 up（已就绪，give 后）→ {result!r}")
            if "已直传 use-item" not in result:
                raise RuntimeError(f"slave 直传上行失败: {result!r}")
            # 诊断：use-item 后源服实际方块（确认虚拟玩家 vanilla 放置）
            # 源服侧断言放置位置（flat 世界 y=64 是空气：点击空气 replaceClicked=true →
            # 放置替换点击方块 (1,64,0)；点击实体方块则放置到 direction 邻位 (1,65,0)）
            _send_source_command(procs[0], "execute if block 1 64 0 minecraft:dirt run say PLACED_A")
            _send_source_command(procs[0], "execute if block 1 65 0 minecraft:dirt run say PLACED_B")
            time.sleep(4)
            placed_source = SOURCE_LOG.read_text(errors="replace")
            placed_at = None
            if "PLACED_A" in placed_source:
                placed_at = (1, 64, 0)
            elif "PLACED_B" in placed_source:
                placed_at = (1, 65, 0)
            if placed_at:
                print(f"  [源服] 权威放置确认: {placed_at} = dirt")
            else:
                print("  [源服] 放置未确认（1,64,0/1,65,0 均非 dirt）")
            # 源服虚拟玩家 vanilla 放置 → 区块 dirty → 快照管道发布
            _wait_log(SOURCE_LOG, "已提交快照", 60, "源服放置后快照发布")
            _wait_log(SLAVE_LOG, "已装载快照", 60, "slave 装载放置结果快照")
            # slave 视角验证（快照回传 = 使用物品结果来自源服）：放置位置 = 源服实际落点
            # （1,64,0 或 1,65,0，见上方源服断言）
            check_pos = placed_at if placed_at else (1, 65, 0)
            deadline = time.monotonic() + 30
            placed = None
            while time.monotonic() < deadline:
                placed = rcon.command(f"execute if block {check_pos[0]} {check_pos[1]} {check_pos[2]} minecraft:dirt")
                if "Test passed" in placed or "Success" in placed:
                    break
                time.sleep(0.05)
            print(f"  [RCON] 权威放置回显 dirt@{check_pos} → {placed!r}")
            if "Test passed" not in placed and "Success" not in placed:
                raise RuntimeError(f"源服权威放置结果未达 slave（{check_pos} 非 dirt）: {placed!r}")
            print("  [RCON] 背包/互动直传闭环：slave use-item 上行 → 源服虚拟玩家权威放置 → 快照回传 → slave 视角一致")
        finally:
            rcon.close()

        # 7) fail closed：停止 master → slave 服务闸门关闭
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
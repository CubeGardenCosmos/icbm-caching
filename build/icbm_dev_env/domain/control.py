"""master 控制服务启停（阶段2：control-service 并入 master 后的独立进程形态）。

配置（build/workspace/control-run/control.properties，Git 忽略）：
  control.host=0.0.0.0
  control.encapsulatedPort=11420        单端口封装通道（magic + AUTH 公共密码 + ChaCha20-Poly1305）
  control.psk=<生成随机 PSK>             运维手册禁止弱公共密码
  control.roleBindings=<uuid>=SOURCE,<uuid>=SLAVE,...  按 AUTH UUID 绑定角色

不使用 TLS/mTLS 证书；畸形/原版握手由 master 直接 RST。
"""

from __future__ import annotations

import secrets

from ..infra import paths, shell


def write_control_config() -> None:
    """生成 master 运行配置（build/workspace/control-run/control.properties）。"""
    run_dir = paths.CONTROL_RUN
    run_dir.mkdir(parents=True, exist_ok=True)
    psk = secrets.token_hex(32)
    assertion_key = secrets.token_hex(32)  # Auth Gateway 断言签名密钥（离线保护）
    backend_secret = secrets.token_hex(32)  # velocity forwarding secret（与源服 icbm-control.properties 一致）
    # 固定示例对等方（UUID 为 AUTH 身份；source/slave 的正式 UUID 由部署方决定）
    source_uuid = "0102030405060708090a0b0c0d0e0f10"
    slave_uuid = "1112131415161718191a1b1c1d1e1f20"
    props = (
        "control.host=0.0.0.0\n"
        "control.encapsulatedPort=11420\n"
        "control.storeRoot={run}/objects\n"
        "control.eventLogPath={run}/events.log\n"
        "control.serverId=master-1\n"
        "control.serverName=lobby-1\n"
        "control.psk={psk}\n"
        "control.roleBindings={source_uuid}=SOURCE,{slave_uuid}=SLAVE\n"
        "control.backendHost=127.0.0.1\n"
        "control.backendPort=11430\n"
        "control.backendForwardingSecret={backend_secret}\n"
        "control.pushWindow=64\n"
        "control.heartbeatIntervalMillis=30000\n"
        "control.lagThresholdMillis=90000\n"
        "control.auth.verifyMojang=false\n"
        "control.auth.mojangTimeoutMillis=10000\n"
        "control.auth.assertionKey={assertion_key}\n"
        "control.allowedServerNames=lobby-1\n"
    ).format(run=run_dir, psk=psk, assertion_key=assertion_key,
             source_uuid=source_uuid, slave_uuid=slave_uuid, backend_secret=backend_secret)
    (run_dir / "control.properties").write_text(props)
    print(f"master 配置: {run_dir / 'control.properties'}")
    print(f"  PSK={psk}（仅本次生成；部署时按运维手册安全分发）")
    print(f"  backendForwardingSecret={backend_secret}（需同步到源服 icbm-control.properties）")
    print(f"  角色绑定: SOURCE={source_uuid} SLAVE={slave_uuid}")
    print(f"  auth.verifyMojang=false（开发/测试；生产按运维手册开 true）")
    print(f"  slave 端 icbm-control.properties 可加 icbm.brandLocation=HongKong:Kowloon（可选，")
    print(f"    F3 Brand → ICBM Caching Server (HongKong:Kowloon)；默认空不显示地段）")


def start_control_service() -> int:
    """前台启动 master 控制服务（velocity-plugin gradlew run；Ctrl-C 退出）。"""
    write_control_config()
    from . import jdk

    env = jdk.java_home_env()
    env["ICBM_CONTROL_CONFIG"] = str(paths.CONTROL_RUN / "control.properties")
    print("启动 master（build/workspace/control-run，封装通道端口 11420）...")
    proc = shell.run(
        [str(paths.VELOCITY_PLUGIN / "gradlew"), "run", "--console=plain"],
        cwd=str(paths.VELOCITY_PLUGIN),
        env=env,
        check=False,
    )
    return proc.returncode

"""mTLS 证书生成与控制服务启停（阶段2 后台控制平面 / 阶段0 隔离实验环境）。

证书布局（build/workspace/certs/，Git 忽略）：
  ca-trust.p12            CA 信任库（所有对端共用）
  master-control.p12      控制服务（master）私钥证书（CN=master-control）
  source-server.p12       Fabric 源模组客户端（CN=source-server → SOURCE 角色）
  slave-a.p12 / slave-b.p12  地区 slave 客户端（CN=slave-a/b → VELOCITY 角色）

角色绑定（control.properties control.roleBindings）：
  source-server=SOURCE,slave-a=VELOCITY,slave-b=VELOCITY
"""

from __future__ import annotations

import shutil
import subprocess

from ..infra import paths, shell


def gen_certs() -> None:
    """生成 CA 与全部角色证书（PKCS12，密码 changeit；已存在则跳过）。"""
    certs = paths.CERTS_DIR
    if (certs / "ca-trust.p12").exists():
        print(f"证书已存在: {certs}（删除后重新生成）")
        return
    if not shutil.which("openssl") or not shutil.which("keytool"):
        raise RuntimeError("生成 mTLS 证书需要 openssl 与 keytool")
    certs.mkdir(parents=True, exist_ok=True)
    _shell(certs, "openssl req -x509 -newkey rsa:2048 -keyout ca.key -out ca.crt "
                  "-days 3650 -nodes -subj /CN=icbm-test-ca")
    for cn in ("master-control", "source-server", "slave-a", "slave-b"):
        _shell(certs, f"openssl req -newkey rsa:2048 -keyout {cn}.key -out {cn}.csr "
                      f"-nodes -subj /CN={cn}")
        _shell(certs, f"openssl x509 -req -in {cn}.csr -CA ca.crt -CAkey ca.key "
                      f"-CAcreateserial -out {cn}.crt -days 3650")
        _shell(certs, f"openssl pkcs12 -export -inkey {cn}.key -in {cn}.crt "
                      f"-out {cn}.p12 -passout pass:changeit -name icbm-{cn}")
    _shell(certs, "keytool -importcert -noprompt -alias ca -file ca.crt "
                  "-keystore ca-trust.p12 -storetype PKCS12 -storepass changeit")
    print(f"证书就绪: {certs}")


def write_control_config() -> None:
    """生成控制服务运行配置（build/workspace/control-run/control.properties）。"""
    run_dir = paths.CONTROL_RUN
    run_dir.mkdir(parents=True, exist_ok=True)
    certs = paths.CERTS_DIR
    props = (
        "control.host=0.0.0.0\n"
        "control.port=11420\n"
        "control.keyStore={certs}/master-control.p12\n"
        "control.keyStorePassword=changeit\n"
        "control.trustStore={certs}/ca-trust.p12\n"
        "control.trustStorePassword=changeit\n"
        "control.requireClientCert=true\n"
        "control.storeRoot={run}/objects\n"
        "control.eventLogPath={run}/events.log\n"
        "control.serverId=master-1\n"
        "control.roleBindings=source-server=SOURCE,slave-a=VELOCITY,slave-b=VELOCITY\n"
        "control.pushWindow=64\n"
        "control.heartbeatIntervalMillis=30000\n"
        "control.lagThresholdMillis=90000\n"
    ).format(certs=certs, run=run_dir)
    (run_dir / "control.properties").write_text(props)
    print(f"控制服务配置: {run_dir / 'control.properties'}")


def start_control_service() -> int:
    """前台启动控制服务（gradlew run；Ctrl-C 退出）。"""
    gen_certs()
    write_control_config()
    from . import jdk

    env = jdk.java_home_env()
    env["ICBM_CONTROL_CONFIG"] = str(paths.CONTROL_RUN / "control.properties")
    print("启动控制服务（build/workspace/control-run，端口 11420）...")
    proc = shell.run(
        [str(paths.CONTROL_SERVICE / "gradlew"), "run", "--console=plain"],
        cwd=str(paths.CONTROL_SERVICE),
        env=env,
        check=False,
    )
    return proc.returncode


def _shell(cwd, command: str) -> None:
    subprocess.run(command, shell=True, cwd=cwd, check=True)

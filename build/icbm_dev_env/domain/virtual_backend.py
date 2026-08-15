"""虚拟后端启停（阶段3 只读变体参考；连接层已迁封装通道，里程碑 4 迁入 slave-mod）。

配置布局（build/workspace/virtual-backend-run/）：
  virtual-backend.properties  运行配置（master 封装通道地址/PSK/UUID/本地对象库/维度/视距/指纹）
封装通道：公共密码 + UUID → SLAVE 角色（master control.roleBindings 绑定）。
"""

from __future__ import annotations

from ..infra import paths, shell


def write_virtual_backend_config() -> None:
    """生成虚拟后端运行配置（build/workspace/virtual-backend-run/）。"""
    run_dir = paths.WORKSPACE / "virtual-backend-run"
    run_dir.mkdir(parents=True, exist_ok=True)
    fingerprints = _load_fingerprints()

    props = (
        "virtualBackend.gameHost=0.0.0.0\n"
        "virtualBackend.gamePort=25575\n"
        "virtualBackend.serverId=virtual-backend-1\n"
        "virtualBackend.storeRoot={run}/objects\n"
        "virtualBackend.viewDistance=8\n"
        "virtualBackend.dimensionNamespace=minecraft\n"
        "virtualBackend.dimensionPath=overworld\n"
        "virtualBackend.keepAliveIntervalMillis=15000\n"
        "control.host=127.0.0.1\n"
        "control.port=11420\n"
        "control.psk=请填写与 master control.properties 一致的 PSK\n"
        "control.uuid=22222222222222222222222222222222\n"
        "fingerprint.minecraftVersion={mc}\n"
        "fingerprint.runtimeProtocol={protocol}\n"
        "fingerprint.fabricLoaderVersion=0.19.3\n"
        "fingerprint.fabricApiVersion=0.157.1+26.3\n"
        "fingerprint.modpackFingerprint={modpack}\n"
        "fingerprint.registryAndDatapackFingerprint={registry}\n"
    ).format(
        run=run_dir,
        mc=fingerprints.get("minecraftVersion", "26.3-snapshot-8"),
        protocol=fingerprints.get("runtimeProtocol", "1073742154"),
        modpack=fingerprints.get("modpackFingerprint", ""),
        registry=fingerprints.get("registryAndDatapackFingerprint", ""),
    )
    (run_dir / "virtual-backend.properties").write_text(props)
    print(f"虚拟后端配置: {run_dir / 'virtual-backend.properties'}")
    print("  注意：control.psk 需与 master（control.properties）一致；UUID 需在 master 的")
    print("        control.roleBindings 中绑定为 SLAVE。")


def _load_fingerprints() -> dict:
    import json

    path = paths.FINGERPRINTS
    if path.exists():
        return json.loads(path.read_text("utf-8"))
    print("[warn] fingerprints.json 缺失（请先运行 capture-registry）；指纹留空将导致启动校验失败")
    return {}


def start_virtual_backend() -> int:
    """前台启动虚拟后端（gradlew run；Ctrl-C 退出）。"""
    write_virtual_backend_config()
    from . import jdk

    env = jdk.java_home_env()
    env["ICBM_VIRTUAL_BACKEND_CONFIG"] = str(
        paths.WORKSPACE / "virtual-backend-run" / "virtual-backend.properties"
    )
    print("启动虚拟后端（build/workspace/virtual-backend-run，端口 25575）...")
    proc = shell.run(
        [str(paths.VIRTUAL_BACKEND / "gradlew"), "run", "--console=plain"],
        cwd=str(paths.VIRTUAL_BACKEND),
        env=env,
        check=False,
    )
    return proc.returncode

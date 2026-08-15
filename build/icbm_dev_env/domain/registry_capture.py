"""注册表转储（阶段3 虚拟后端 golden 资源）：构建期在源服内运行转储模组，
产出 blockstates/registries/tags/features/world 等确定性数据并打包为
virtual-backend 资源（GZIP JSON）。转储模组不注册内容、不随运行时部署。
"""

from __future__ import annotations

import gzip
import json
import shutil
import subprocess
import time
from pathlib import Path

from ..infra import paths, shell


def capture_registry() -> int:
    """构建期捕获源服注册表数据 → virtual-backend/src/main/resources/icbm/registry/。

    流程：构建 tools/registry-dump → 临时装入测试服务器 mods → 启动服务器
    （-Dicbm.dump.dir）→ 等待 DONE → 停止 → 移出转储模组 → gzip 打包为资源。
    """
    dump_root = paths.WORKSPACE / "registry-dump"
    dump_root.mkdir(parents=True, exist_ok=True)
    resources = paths.VIRTUAL_BACKEND / "src" / "main" / "resources" / "icbm" / "registry"
    resources.mkdir(parents=True, exist_ok=True)

    _build_dump_mod()
    jar = paths.REGISTRY_DUMP / "build" / "libs" / "icbm-registry-dump-1.0.0.jar"
    if not jar.exists():
        raise RuntimeError("转储模组构建产物缺失: " + str(jar))

    mods = paths.DEBUG_SOURCE / "mods"
    deployed = mods / jar.name
    try:
        shutil.copyfile(jar, deployed)
        print(f"[capture] 临时装入转储模组: {deployed}")
        _run_server_with_dump(dump_root)
        _validate_dump(dump_root)
        _pack_resources(dump_root, resources)
        _write_fingerprints(dump_root, paths.WORKSPACE / "fingerprints.json")
        print(f"[capture] 完成：资源已写入 {resources}")
        return 0
    finally:
        deployed.unlink(missing_ok=True)
        print(f"[capture] 已移出转储模组: {mods}")


def _build_dump_mod() -> None:
    from . import jdk

    env = jdk.java_home_env()
    shell.run(
        [str(paths.REGISTRY_DUMP / "gradlew"), "build", "--console=plain", *shell.jvm_proxy_args()],
        cwd=str(paths.REGISTRY_DUMP),
        env=env,
    )


def _run_server_with_dump(dump_root: Path) -> None:
    from . import server

    jar = server._server_jar()
    server._ensure_test_config()
    from . import jdk

    env = jdk.java_home_env()
    env["ICBM_DUMP_DIR"] = str(dump_root)
    # 启动服务器（后台），等待转储 DONE 后停止
    proc = subprocess.Popen(
        ["java", "-Xmx3G", "-jar", jar.name, "nogui"],
        cwd=str(paths.DEBUG_SOURCE),
        env=env,
        stdout=open(paths.DEBUG_SOURCE / "capture-server.log", "w"),
        stderr=subprocess.STDOUT,
    )
    try:
        deadline = time.time() + 300
        while time.time() < deadline:
            if (dump_root / "DONE").exists():
                print("[capture] 转储完成")
                return
            if proc.poll() is not None:
                tail = (paths.DEBUG_SOURCE / "capture-server.log").read_text()[-2000:]
                raise RuntimeError(f"服务器提前退出（exit={proc.returncode}）:\n{tail}")
            time.sleep(1)
        raise RuntimeError("转储超时（300s）")
    finally:
        _stop_server(proc)


def _stop_server(proc: subprocess.Popen) -> None:
    if proc.poll() is None:
        # 通过控制台命令 stop 优雅停止；失败则 kill
        try:
            with open(paths.DEBUG_SOURCE / "capture-console.in", "w") as fh:
                pass  # 控制台输入走 stdin，这里仅兜底
        except OSError:
            pass
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)


def _validate_dump(dump_root: Path) -> None:
    required = [
        "manifest.json", "blockstates.json", "registries.json", "tags.json",
        "features.json", "world.json", "blockentitytypes.json", "biomes.json",
    ]
    missing = [name for name in required if not (dump_root / name).exists()]
    if missing:
        raise RuntimeError(f"转储缺文件: {missing}")


def _pack_resources(dump_root: Path, resources: Path) -> None:
    for name in dump_root.glob("*.json"):
        with open(name, "rb") as src, gzip.open(resources / (name.name + ".gz"), "wb") as dst:
            shutil.copyfileobj(src, dst)
    # 记录转储来源（便于核对指纹）
    with open(resources / "manifest.json.gz", "rb") as fh:
        manifest = json.loads(gzip.decompress(fh.read()).decode("utf-8"))
    (resources / ".." / "README.md").resolve().parent.mkdir(parents=True, exist_ok=True)
    print(f"[capture] manifest: registryFingerprint={manifest.get('registryAndDatapackFingerprint', '')[:16]}...")


def _write_fingerprints(dump_root: Path, target: Path) -> None:
    """记录部署指纹（与 VERSION_FREEZE.md 约定：结果记录于 fingerprints.json，Git 忽略）。"""
    manifest = json.loads((dump_root / "manifest.json").read_text("utf-8"))
    data = {
        "minecraftVersion": manifest.get("minecraftVersion"),
        "runtimeProtocol": manifest.get("runtimeProtocol"),
        "dataVersion": manifest.get("dataVersion"),
        "modpackFingerprint": manifest.get("modpackFingerprint"),
        "registryAndDatapackFingerprint": manifest.get("registryAndDatapackFingerprint"),
        "generatedAt": manifest.get("generatedAt"),
        "registryResources": str(paths.VIRTUAL_BACKEND / "src" / "main" / "resources" / "icbm" / "registry"),
    }
    target.write_text(json.dumps(data, indent=2) + "\n")
    print(f"[capture] 指纹记录: {target}")

"""构建/测试 fabric-source 模组（Gradle wrapper + JDK 25 + JVM 代理参数）。"""

from __future__ import annotations

from ..infra import paths, shell

_WRAPPER = paths.FABRIC_SOURCE / "gradlew"


def _require_component() -> None:
    if not _WRAPPER.exists():
        raise RuntimeError("fabric-source 组件缺失（gradlew 未找到），请先提交组件骨架")


def run_build() -> None:
    _require_component()
    from . import jdk

    env = jdk.java_home_env()
    print(f"构建 fabric-source（JAVA_HOME={env['JAVA_HOME']}）...")
    shell.run(
        [str(_WRAPPER), "build", "--console=plain", "--stacktrace", *shell.jvm_proxy_args()],
        cwd=str(paths.FABRIC_SOURCE),
        env=env,
    )


def run_tests() -> None:
    _require_component()
    from . import jdk

    env = jdk.java_home_env()
    print("运行 fabric-source 测试（gradlew test）...")
    shell.run(
        [str(_WRAPPER), "test", "--console=plain", *shell.jvm_proxy_args()],
        cwd=str(paths.FABRIC_SOURCE),
        env=env,
    )


def clean_artifacts() -> None:
    """删除各组件构建产物（保留源码）。"""
    import shutil

    for d in (paths.FABRIC_SOURCE / "build", paths.FABRIC_SOURCE / ".gradle"):
        shutil.rmtree(d, ignore_errors=True)

"""构建/测试各 Java 组件（Gradle wrapper + JDK 25 + JVM 代理参数）。

fabric-source 为 Loom 模组；protocol/velocity-plugin（master）/virtual-backend 为纯 Java
（protocol 经复合构建被其余组件消费）。
"""

from __future__ import annotations

from ..infra import paths, shell

_ALL = ("protocol", "velocity-plugin", "virtual-backend", "fabric-source")


def _gradlew(component: str) -> str:
    wrapper = paths.component_root(component) / "gradlew"
    if not wrapper.exists():
        raise RuntimeError(f"组件 {component} 缺失（gradlew 未找到），请先提交组件骨架")
    return str(wrapper)


def _run(component: str, task: str, *args: str) -> None:
    from . import jdk

    env = jdk.java_home_env()
    print(f"[{component}] {task} ...")
    shell.run(
        [_gradlew(component), task, "--console=plain", *args, *shell.jvm_proxy_args()],
        cwd=str(paths.component_root(component)),
        env=env,
    )


def run_build(components: tuple[str, ...] = _ALL) -> None:
    for component in components:
        _run(component, "build")


def run_tests(components: tuple[str, ...] = _ALL) -> None:
    for component in components:
        _run(component, "test")


def clean_artifacts() -> None:
    """删除各组件构建产物（保留源码）。"""
    import shutil

    for component in _ALL:
        root = paths.component_root(component)
        for d in (root / "build", root / ".gradle"):
            shutil.rmtree(d, ignore_errors=True)

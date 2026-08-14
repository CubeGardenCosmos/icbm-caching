#!/usr/bin/env python3
"""ICBM Caching 开发环境入口。

只做 uv 自举与 CLI 分发：若 build/.venv 尚未就绪则执行 uv sync，
随后用 venv 内 python 运行 icbm_dev_env.cli。禁止向本文件堆业务逻辑。
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VENV_PY = ROOT / ".venv" / "bin" / "python"


def _bootstrap() -> Path:
    if not VENV_PY.exists():
        subprocess.run(
            ["uv", "sync", "--project", str(ROOT)],
            check=True,
            env={**os.environ, "UV_PROJECT_ENVIRONMENT": str(ROOT / ".venv")},
        )
    return VENV_PY


def main() -> int:
    py = _bootstrap()
    proc = subprocess.run(
        [str(py), "-m", "icbm_dev_env.cli", *sys.argv[1:]],
        cwd=ROOT,
    )
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())

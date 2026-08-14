"""代理感知 HTTP 下载（urllib，尊重 HTTPS_PROXY/HTTP_PROXY 环境变量）。"""

from __future__ import annotations

import shutil
import sys
import urllib.request
from pathlib import Path
from typing import Callable

from .progress import Progress

ProgressFn = Callable[[int, int | None], None]  # (downloaded, total|None)


def download(url: str, dest: Path, progress: ProgressFn | None = None) -> Path:
    """下载 url 到 dest，带 cargo 风格进度显示。断点续传不做（环境一次性产物）。"""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "icbm-dev-env/1.0"})
    with urllib.request.urlopen(req) as resp, open(tmp, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        bar = Progress(total) if progress is not None else None
        downloaded = 0
        while True:
            chunk = resp.read(1 << 16)
            if not chunk:
                break
            out.write(chunk)
            downloaded += len(chunk)
            if bar is not None:
                bar.update(downloaded)
    tmp.replace(dest)
    if bar is not None:
        bar.finish()
    return dest


def download_checked(url: str, dest: Path, description: str) -> Path:
    """带进度与标签的下载。"""
    print(f"下载 {description}: {url}")
    return download(url, dest, progress=lambda d, t: _progress_tick(description, d, t))


def _progress_tick(label: str, downloaded: int, total: int | None) -> None:
    sys.stdout.write(f"\r  {label}: {downloaded // 1024} KiB")
    if total:
        pct = downloaded * 100 // total
        sys.stdout.write(f" / {total // 1024} KiB ({pct}%)")
    sys.stdout.flush()
    if downloaded == total:
        sys.stdout.write("\n")


def extract_zip(archive: Path, dest: Path) -> None:
    """解压 zip 到 dest。"""
    import zipfile

    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(dest)

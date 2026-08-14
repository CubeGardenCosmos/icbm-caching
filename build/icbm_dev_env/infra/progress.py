"""cargo 风格进度条（仅终端，非 TTY 时退化为空实现）。"""

from __future__ import annotations

import sys


class Progress:
    def __init__(self, total: int | None, width: int = 30) -> None:
        self.total = total
        self.width = width
        self._tty = sys.stdout.isatty()

    def update(self, done: int) -> None:
        if not self._tty:
            return
        if not self.total:
            return
        pct = min(done * 100 // self.total, 100)
        filled = pct * self.width // 100
        bar = "=" * filled + "-" * (self.width - filled)
        sys.stdout.write(f"\r[{bar}] {pct:3d}% {done // (1 << 20)} MiB / {self.total // (1 << 20)} MiB")
        sys.stdout.flush()

    def finish(self) -> None:
        if self._tty:
            sys.stdout.write("\n")

"""简单多窗口 TUI（curses，stdlib）：debug-run 三进程窗口 + 状态栏。

- Shift+Tab（KEY_BTAB）→ 下一个窗口；Tab → 上一个窗口（用户要求：shift+tab 切换窗口）；
- 焦点窗口键盘输入按行转发到对应进程 stdin（与官方服务器控制台 stdin 语义一致，
  可在焦点窗口输入 stop / /say 等命令）；
- Ctrl-C 退出（终止全部进程，进程由调用方清理）；
- 进程输出实时 tee 到调用方提供的日志文件（退出后仍可排查）。
"""

from __future__ import annotations

import curses
import locale
import os
import re
import selectors
import subprocess
from pathlib import Path

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


class Pane:
    """单个进程窗口：滚动行缓冲 + 未提交命令行 + 日志落盘。"""

    def __init__(self, label: str, proc: subprocess.Popen, log_path: Path, max_lines: int = 800):
        self.label = label
        self.proc = proc
        self.log_path = log_path
        self.max_lines = max_lines
        self.lines: list[str] = []
        self.pending = ""
        self.input = ""
        self._log = log_path.open("ab")

    def feed(self, data: bytes) -> None:
        """追加进程输出（已按 fd 可读调用，不阻塞）；行尾未完成部分保留到下次。"""
        if not data:
            return
        self._log.write(data)
        self._log.flush()
        text = _ANSI_RE.sub("", data.decode("utf-8", errors="replace"))
        self.pending += text
        parts = self.pending.split("\n")
        self.pending = parts.pop()
        for part in parts:
            if part.endswith("\r"):
                part = part[:-1]
            self.lines.append(part)
        if len(self.lines) > self.max_lines:
            del self.lines[: len(self.lines) - self.max_lines]

    def send_line(self) -> bool:
        """提交当前命令行到进程 stdin；进程已死/管道破裂返回 False。"""
        line = self.input
        self.input = ""
        if not line or self.proc.stdin is None or self.proc.poll() is not None:
            return False
        try:
            self.proc.stdin.write((line + "\n").encode())
            self.proc.stdin.flush()
            return True
        except OSError:
            return False

    def alive(self) -> bool:
        return self.proc.poll() is None

    def close_log(self) -> None:
        try:
            self._log.close()
        except OSError:
            pass


class Tui:
    """curses 主循环：select 轮询进程输出 + nodelay 读键 + 三窗格渲染。"""

    def __init__(self, panes: list[Pane]):
        self.panes = panes
        self.focus = 0
        self._wins: list[curses.window | None] = [None] * len(panes)

    def run(self) -> None:
        locale.setlocale(locale.LC_ALL, "")  # UTF-8 宽字符（中文状态行）
        stdscr = curses.initscr()
        curses.noecho()
        curses.cbreak()
        stdscr.keypad(True)
        stdscr.nodelay(True)
        try:
            curses.curs_set(0)
        except curses.error:
            pass
        sel = selectors.DefaultSelector()
        try:
            for pane in self.panes:
                if pane.proc.stdout is not None:
                    sel.register(pane.proc.stdout, selectors.EVENT_READ, pane)
            running = True
            while running:
                for key, _mask in sel.select(timeout=0.15):
                    pane = key.data
                    try:
                        data = os.read(pane.proc.stdout.fileno(), 1 << 15)
                    except OSError:
                        data = b""
                    if data:
                        pane.feed(data)
                    else:
                        # EOF（进程退出）：停止轮询该 fd
                        try:
                            sel.unregister(pane.proc.stdout)
                        except (KeyError, ValueError):
                            pass
                ch = stdscr.getch()
                if ch == curses.KEY_BTAB:  # Shift+Tab → 下一窗口
                    self.focus = (self.focus + 1) % len(self.panes)
                elif ch == ord("\t"):  # Tab → 上一窗口
                    self.focus = (self.focus - 1) % len(self.panes)
                elif ch == 3:  # Ctrl-C → 退出（调用方清理进程）
                    running = False
                elif ch in (10, 13, curses.KEY_ENTER):
                    self.panes[self.focus].send_line()
                elif ch in (8, 127, curses.KEY_BACKSPACE):
                    self.panes[self.focus].input = self.panes[self.focus].input[:-1]
                elif 32 <= ch < 127:
                    self.panes[self.focus].input += chr(ch)
                elif ch == curses.KEY_RESIZE:
                    self._wins = [None] * len(self.panes)
                self._draw(stdscr)
                curses.doupdate()
        finally:
            sel.close()
            curses.nocbreak()
            stdscr.keypad(False)
            curses.echo()
            curses.endwin()

    def _draw(self, stdscr: curses.window) -> None:
        height, width = stdscr.getmaxyx()
        status = ("[Shift+Tab 切换窗口] [Tab 上一个] [Ctrl-C 退出] | "
                  f"焦点: {self.panes[self.focus].label}")
        try:
            stdscr.addnstr(0, 0, status, width - 1, curses.A_REVERSE)
        except curses.error:
            pass
        body = height - 1
        n = len(self.panes)
        pane_h = body // n
        for i, pane in enumerate(self.panes):
            top = 1 + i * pane_h
            h = pane_h if i < n - 1 else body - (n - 1) * pane_h
            if h < 2:
                continue
            win = self._wins[i]
            if win is None:
                win = curses.newwin(h, width, top, 0)
                self._wins[i] = win
            self._draw_pane(win, pane, h, width, focused=i == self.focus)
            win.noutrefresh()

    def _draw_pane(self, win: curses.window, pane: Pane, h: int, w: int, focused: bool) -> None:
        try:
            win.erase()
            state = "运行中" if pane.alive() else f"已退出({pane.proc.returncode})"
            title = f" {pane.label} "
            if focused:
                win.addnstr(0, 0, title, w - 1, curses.A_REVERSE | curses.A_BOLD)
            else:
                win.addnstr(0, 0, title, w - 1)
            tail = f" pid={pane.proc.pid} {state}"
            win.addnstr(0, max(1, w - len(tail) - 1), tail, w - 1)
            inner = h - 1
            if focused:
                inner -= 1  # 预留命令行
            lines = pane.lines[-(inner - 1):] if inner > 1 else []
            for idx, line in enumerate(lines):
                win.addnstr(1 + idx, 0, line, w - 1)
            if focused:
                win.addnstr(h - 1, 0, "> " + pane.input, w - 1, curses.A_BOLD)
        except curses.error:
            pass  # 终端尺寸变化等：本帧裁剪即可，不崩溃

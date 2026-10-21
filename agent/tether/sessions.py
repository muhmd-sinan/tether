"""ConPTY-backed shell sessions that outlive their viewers.

A Session owns one shell. Any number of viewers (phone tabs, `rt attach`
windows) can come and go; each one gets the scrollback replayed and then the
live stream. Everything here runs on the asyncio loop thread except the
blocking PTY reader, which hands data back with call_soon_threadsafe.
"""

from __future__ import annotations

import asyncio
import collections
import json
import logging
import os
import secrets
import shutil
import subprocess
import threading
import time

from websockets.exceptions import ConnectionClosed
from winpty import PTY, Backend

from . import config

log = logging.getLogger(__name__)

SHELLS = ("powershell", "cmd")
SHELL_LABEL = {"powershell": "PowerShell", "cmd": "cmd"}

# Coalesced output frames are capped so a big replay streams progressively.
MAX_FRAME = 256 * 1024
# A viewer that falls this far behind is dropped rather than buffered forever.
MAX_PENDING = 8 * 1024 * 1024


def shell_command(shell: str) -> tuple[str, str]:
    """Return (exe, args). Both shells are forced into UTF-8; ConPTY otherwise
    hands us the OEM code page and anything outside ASCII turns into U+FFFD."""
    system = os.environ.get("SystemRoot", r"C:\Windows")
    if shell == "cmd":
        return os.path.join(system, "System32", "cmd.exe"), "/k chcp 65001>nul"
    if shell == "powershell":
        exe = shutil.which("pwsh") or os.path.join(
            system, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        return exe, ('-NoLogo -NoExit -Command "[Console]::InputEncoding='
                     '[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)"')
    raise ValueError(f"unknown shell {shell!r}")


class Scrollback:
    """Byte-capped list of output chunks. Whole chunks are dropped from the
    front, so a UTF-8 sequence is never split by trimming."""

    def __init__(self, limit: int = config.SCROLLBACK_BYTES):
        self.limit = limit
        self.chunks: collections.deque[bytes] = collections.deque()
        self.size = 0

    def append(self, data: bytes) -> None:
        self.chunks.append(data)
        self.size += len(data)
        while self.size > self.limit and len(self.chunks) > 1:
            self.size -= len(self.chunks.popleft())

    def snapshot(self) -> list[bytes]:
        return list(self.chunks)


class Viewer:
    """One attached websocket. A single pump task drains the queue so output
    stays ordered, and consecutive output chunks go out as one frame."""

    _CLOSE = object()

    def __init__(self, ws, role: str):
        self.ws = ws
        self.role = role
        self.queue: collections.deque = collections.deque()
        self.pending = 0
        self.wake = asyncio.Event()
        self.closed = False

    def send_bytes(self, data: bytes) -> None:
        if self.closed:
            return
        self.queue.append(data)
        self.pending += len(data)
        if self.pending > MAX_PENDING:
            log.warning("viewer fell %d bytes behind, dropping it", self.pending)
            self.close()
        self.wake.set()

    def send_json(self, msg: dict) -> None:
        if not self.closed:
            self.queue.append(msg)
            self.wake.set()

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            self.queue.append(self._CLOSE)
            self.wake.set()

    async def pump(self) -> None:
        try:
            while True:
                await self.wake.wait()
                self.wake.clear()
                while self.queue:
                    item = self.queue.popleft()
                    if item is self._CLOSE:
                        await self.ws.close()
                        return
                    if isinstance(item, bytes):
                        buf = bytearray(item)
                        while (self.queue and isinstance(self.queue[0], bytes)
                               and len(buf) < MAX_FRAME):
                            buf += self.queue.popleft()
                        self.pending -= len(buf)
                        await self.ws.send(bytes(buf))
                    else:
                        await self.ws.send(json.dumps(item))
        except ConnectionClosed:
            pass


class Session:
    def __init__(self, manager: "SessionManager", sid: str, name: str, shell: str,
                 cols: int = config.DEFAULT_COLS, rows: int = config.DEFAULT_ROWS):
        self.manager = manager
        self.loop = asyncio.get_running_loop()
        self.id = sid
        self.name = name
        self.shell = shell
        self.cols, self.rows = cols, rows
        self.created = time.time()
        self.viewers: set[Viewer] = set()
        self.scrollback = Scrollback()
        self.exit_code: int | None = None
        self.finished = False

        exe, args = shell_command(shell)
        self.pty = PTY(cols, rows, backend=Backend.ConPTY)
        self.pty.spawn(exe, args, os.path.expanduser("~"))
        self.pid = self.pty.pid
        threading.Thread(target=self._read_loop, name=f"pty-{sid}", daemon=True).start()
        log.info("session %s (%s) started, pid %s", sid, shell, self.pid)

    def info(self) -> dict:
        roles = [v.role for v in self.viewers]
        return {"id": self.id, "name": self.name, "shell": self.shell,
                "cols": self.cols, "rows": self.rows, "created": self.created,
                "viewers": {"phone": roles.count("phone"), "pc": roles.count("pc")}}

    def attach(self, viewer: Viewer) -> None:
        # Hello, replay and the live marker are queued in one synchronous step,
        # so no live output can slip in between the replay and the marker.
        viewer.send_json({"type": "hello", **self.info()})
        for chunk in self.scrollback.snapshot():
            viewer.send_bytes(chunk)
        viewer.send_json({"type": "live"})
        self.viewers.add(viewer)
        self.manager.changed()

    def detach(self, viewer: Viewer) -> None:
        if viewer in self.viewers:
            self.viewers.discard(viewer)
            self.manager.changed()

    def write(self, text: str) -> None:
        if not self.finished and text:
            self.pty.write(text)

    def resize(self, cols: int, rows: int) -> None:
        cols, rows = max(20, min(int(cols), 500)), max(5, min(int(rows), 200))
        if self.finished or (cols, rows) == (self.cols, self.rows):
            return
        self.cols, self.rows = cols, rows
        self.pty.set_size(cols, rows)
        for v in self.viewers:
            v.send_json({"type": "size", "cols": cols, "rows": rows})
        self.manager.changed()

    async def kill(self) -> None:
        # Kill the whole tree; the reader then sees EOF and _finish runs.
        await asyncio.to_thread(
            subprocess.run, ["taskkill", "/T", "/F", "/PID", str(self.pid)],
            capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)

    def _read_loop(self) -> None:
        while True:
            try:
                data = self.pty.read(blocking=True)
            except Exception:
                break
            if data:
                self._post(self._output, data.encode("utf-8", "replace"))
            elif not self.pty.isalive():
                break
        code = self.pty.get_exitstatus()
        self._post(self._finish, code)

    def _post(self, fn, *args) -> None:
        try:
            self.loop.call_soon_threadsafe(fn, *args)
        except RuntimeError:
            pass  # loop already closed during shutdown

    def _output(self, data: bytes) -> None:
        self.scrollback.append(data)
        for v in self.viewers:
            v.send_bytes(data)

    def _finish(self, code) -> None:
        if self.finished:
            return
        self.finished = True
        self.exit_code = code
        for v in self.viewers:
            v.send_json({"type": "exit", "code": code})
            v.close()
        self.viewers.clear()
        self.pty = None
        log.info("session %s exited with %s", self.id, code)
        self.manager.remove(self.id)


class SessionManager:
    def __init__(self):
        self.sessions: dict[str, Session] = {}
        self.listeners: list = []
        # Immutable copy for other threads (tray) to read without locking.
        self.snapshot: tuple[dict, ...] = ()

    def create(self, shell: str, name: str | None = None) -> Session:
        if shell not in SHELLS:
            raise ValueError(f"shell must be one of {', '.join(SHELLS)}")
        name = (name or "").strip()[:40] or self._default_name(shell)
        sid = secrets.token_hex(4)
        while sid in self.sessions:
            sid = secrets.token_hex(4)
        session = Session(self, sid, name, shell)
        self.sessions[sid] = session
        self.changed()
        return session

    def _default_name(self, shell: str) -> str:
        label = SHELL_LABEL[shell]
        taken = {s.name for s in self.sessions.values()}
        n = 1
        while f"{label} {n}" in taken:
            n += 1
        return f"{label} {n}"

    def get(self, sid: str) -> Session | None:
        return self.sessions.get(sid)

    def rename(self, sid: str, name: str) -> None:
        session = self.sessions.get(sid)
        name = (name or "").strip()[:40]
        if session and name:
            session.name = name
            for v in session.viewers:
                v.send_json({"type": "renamed", "name": name})
            self.changed()

    def remove(self, sid: str) -> None:
        if self.sessions.pop(sid, None):
            self.changed()

    def list(self) -> list[dict]:
        return [s.info() for s in sorted(self.sessions.values(), key=lambda s: s.created)]

    async def close_all(self) -> None:
        await asyncio.gather(*(s.kill() for s in list(self.sessions.values())))

    def changed(self) -> None:
        self.snapshot = tuple(self.list())
        for fn in list(self.listeners):
            try:
                fn()
            except Exception:
                log.exception("session listener failed")

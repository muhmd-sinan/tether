"""ConPTY-backed shell sessions that outlive their viewers.

A Session owns one shell. Any number of viewers (phone tabs, `rt attach`
windows) can come and go; each one gets the scrollback replayed and then the
live stream. The PTY takes the size of whichever viewer typed last, the way
tmux does, so a phone gets a phone-width layout while it is being used. Everything here runs on the asyncio loop thread except the
blocking PTY reader, which hands data back with call_soon_threadsafe.
"""

from __future__ import annotations

import asyncio
import collections
import json
import logging
import os
import re
import secrets
import shutil
import subprocess
import threading
import time

from websockets.exceptions import ConnectionClosed
from winpty import PTY, Backend

from . import claude, config

log = logging.getLogger(__name__)

SHELLS = ("powershell", "cmd", "claude")
SHELL_LABEL = {"powershell": "PowerShell", "cmd": "cmd", "claude": "Claude"}

# Input that is a terminal's automatic answer (focus in/out, device attributes,
# cursor and version reports) rather than typing. It must not move the size to
# another device, and only the driving viewer's answers reach the shell, so an
# app querying the terminal doesn't get one reply per attached device.
REPLY = re.compile(r"(?:\x1b\[[IO]|\x1b\[\?[\d;]*[cu]|\x1b\[>[\d;]*c|\x1b\[\d+;\d+R"
                   r"|\x1bP[^\x1b]*\x1b\\|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\))+")

# Coalesced output frames are capped so a big replay streams progressively.
MAX_FRAME = 256 * 1024
# A viewer that falls this far behind is dropped rather than buffered forever.
MAX_PENDING = 8 * 1024 * 1024


def shell_command(shell: str, settings: str | None = None) -> tuple[str, str]:
    """Return (exe, args). Both shells are forced into UTF-8; ConPTY otherwise
    hands us the OEM code page and anything outside ASCII turns into U+FFFD.
    "claude" is PowerShell running Claude Code; the prompt stays when it exits."""
    system = os.environ.get("SystemRoot", r"C:\Windows")
    if shell == "cmd":
        return os.path.join(system, "System32", "cmd.exe"), "/k chcp 65001>nul"
    if shell in ("powershell", "claude"):
        exe = shutil.which("pwsh") or os.path.join(
            system, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        script = "[Console]::InputEncoding=[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)"
        if shell == "claude":
            script += "; claude" + (f" --settings '{settings}'" if settings else "")
        return exe, f'-NoLogo -NoExit -Command "{script}"'
    raise ValueError(f"unknown shell {shell!r}")


def user_environment() -> str | None:
    """The user's environment as Windows builds it for a fresh sign-in (registry
    PATH etc.), in pywinpty's "k=v\\0k=v\\0" form. Shells get this rather than
    the agent's own environment, so nothing from whatever launched the agent
    (an IDE, another CLI and its tokens) leaks into them. None means inherit."""
    import ctypes
    from ctypes import wintypes
    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
    userenv = ctypes.WinDLL("userenv", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.GetCurrentProcess.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    advapi32.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
    userenv.CreateEnvironmentBlock.argtypes = [ctypes.POINTER(ctypes.c_void_p), wintypes.HANDLE, wintypes.BOOL]
    userenv.DestroyEnvironmentBlock.argtypes = [ctypes.c_void_p]
    token = wintypes.HANDLE()
    block = ctypes.c_void_p()
    TOKEN_QUERY, TOKEN_DUPLICATE = 0x0008, 0x0002
    if not advapi32.OpenProcessToken(kernel32.GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE,
                                     ctypes.byref(token)):
        return None
    try:
        if not userenv.CreateEnvironmentBlock(ctypes.byref(block), token, False):
            return None
        try:
            pairs, offset = [], 0
            while True:
                entry = ctypes.wstring_at(block.value + offset * 2)
                if not entry:
                    break
                pairs.append(entry)
                offset += len(entry) + 1
        finally:
            userenv.DestroyEnvironmentBlock(block)
    finally:
        kernel32.CloseHandle(token)
    return "\0".join(pairs) + "\0" if pairs else None


def clamp_size(cols, rows) -> tuple[int, int]:
    return max(20, min(int(cols), 500)), max(5, min(int(rows), 200))


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
        self.size: tuple[int, int] | None = None  # this viewer's own window, once reported
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
                 cols: int = config.DEFAULT_COLS, rows: int = config.DEFAULT_ROWS,
                 cwd: str | None = None):
        self.manager = manager
        self.loop = asyncio.get_running_loop()
        self.id = sid
        self.name = name
        self.shell = shell
        self.cols, self.rows = clamp_size(cols, rows)
        self.cwd = cwd or os.path.expanduser("~")
        self.driver: Viewer | None = None  # viewer whose size the PTY has
        self.created = time.time()
        self.viewers: set[Viewer] = set()
        self.scrollback = Scrollback()
        self.exit_code: int | None = None
        self.finished = False

        settings = claude.write_settings(sid, manager.port) if shell == "claude" else None
        exe, args = shell_command(shell, settings)
        self.pty = PTY(self.cols, self.rows, backend=Backend.ConPTY)
        self.pty.spawn(exe, args, self.cwd, user_environment())
        self.pid = self.pty.pid
        threading.Thread(target=self._read_loop, name=f"pty-{sid}", daemon=True).start()
        log.info("session %s (%s) started, pid %s", sid, shell, self.pid)

    def info(self) -> dict:
        roles = [v.role for v in self.viewers]
        return {"id": self.id, "name": self.name, "shell": self.shell, "cwd": self.cwd,
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
            if self.driver is viewer:
                self.driver = None
            self.manager.changed()

    def write(self, text: str) -> None:
        if not self.finished and text:
            self.pty.write(text)

    def viewer_size(self, viewer: Viewer, cols, rows) -> None:
        """A viewer reports its own window size. It applies right away when that
        viewer drives the session, or when nobody does yet."""
        viewer.size = clamp_size(cols, rows)
        if self.driver is None:
            self.driver = viewer
        if self.driver is viewer:
            self.resize(*viewer.size)

    def input_from(self, viewer: Viewer, text: str) -> None:
        """Typing makes a viewer the driver; automatic terminal replies don't,
        and are dropped unless they come from the driver."""
        if not text:
            return
        if REPLY.fullmatch(text):
            if self.driver in (None, viewer):
                self.write(text)
            return
        if self.driver is not viewer:
            self.driver = viewer
            if viewer.size:
                self.resize(*viewer.size)
        self.write(text)

    def resize(self, cols: int, rows: int) -> None:
        cols, rows = clamp_size(cols, rows)
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
        self.driver = None
        if self.shell == "claude":
            claude.remove_settings(self.id)
        log.info("session %s exited with %s", self.id, code)
        self.manager.remove(self.id)


class SessionManager:
    def __init__(self):
        self.sessions: dict[str, Session] = {}
        self.listeners: list = []
        self.alert_listeners: list = []  # fn(session_info, message)
        self.port = config.PORT  # where Claude hooks reach the agent; set by the server
        # Immutable copy for other threads (tray) to read without locking.
        self.snapshot: tuple[dict, ...] = ()

    def create(self, shell: str, name: str | None = None, cols=None, rows=None,
               cwd: str | None = None) -> Session:
        if shell not in SHELLS:
            raise ValueError(f"shell must be one of {', '.join(SHELLS)}")
        if cwd is not None:
            cwd = os.path.normpath(str(cwd))
            if not os.path.isabs(cwd) or not os.path.isdir(cwd):
                raise ValueError(f"folder not found: {cwd}")
        label = (os.path.basename(cwd) if shell == "claude" and cwd else "") or SHELL_LABEL[shell]
        name = (name or "").strip()[:40] or self._default_name(label)
        sid = secrets.token_hex(4)
        while sid in self.sessions:
            sid = secrets.token_hex(4)
        size = (cols, rows) if cols and rows else (config.DEFAULT_COLS, config.DEFAULT_ROWS)
        session = Session(self, sid, name, shell, *size, cwd=cwd)
        self.sessions[sid] = session
        self.changed()
        return session

    def _default_name(self, label: str) -> str:
        """Shells get "PowerShell 1"; Claude sessions get the bare folder name
        unless it is already taken."""
        label = label[:36]
        taken = {s.name for s in self.sessions.values()}
        if label not in taken and label not in SHELL_LABEL.values():
            return label
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

    def alert(self, sid: str, message: str) -> None:
        session = self.sessions.get(sid)
        if not session:
            return
        info = session.info()
        for fn in list(self.alert_listeners):
            try:
                fn(info, message)
            except Exception:
                log.exception("alert listener failed")

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

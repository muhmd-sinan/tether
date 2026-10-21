"""rt: list phone-started sessions and attach to them from a PC console.

`rt attach` puts the console into raw VT mode and bridges it to the session.
The PC window size becomes the session size. Ctrl+] or closing the tab
detaches; the session keeps running.
"""

from __future__ import annotations

import argparse
import codecs
import ctypes
import json
import shutil
import sys
import threading
import time
from ctypes import wintypes

from websockets.exceptions import ConnectionClosed, InvalidStatus
from websockets.sync.client import connect

from . import config

DETACH = "\x1d"  # Ctrl+]

STD_INPUT_HANDLE, STD_OUTPUT_HANDLE = -10, -11
ENABLE_PROCESSED_INPUT = 0x0001
ENABLE_LINE_INPUT = 0x0002
ENABLE_ECHO_INPUT = 0x0004
ENABLE_QUICK_EDIT_MODE = 0x0040
ENABLE_EXTENDED_FLAGS = 0x0080
ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200
ENABLE_PROCESSED_OUTPUT = 0x0001
ENABLE_WRAP_AT_EOL_OUTPUT = 0x0002
ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
DISABLE_NEWLINE_AUTO_RETURN = 0x0008

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)


class RawConsole:
    """Raw VT input/output on the current console; restores modes on exit."""

    def __init__(self):
        self.hin = kernel32.GetStdHandle(STD_INPUT_HANDLE)
        self.hout = kernel32.GetStdHandle(STD_OUTPUT_HANDLE)
        self.saved: list[tuple[int, int]] = []
        self.saved_cp = (kernel32.GetConsoleCP(), kernel32.GetConsoleOutputCP())

    def __enter__(self):
        mode = wintypes.DWORD()
        if not kernel32.GetConsoleMode(self.hin, ctypes.byref(mode)):
            raise SystemExit("rt attach needs a console window (run it in Windows Terminal).")
        self.saved.append((self.hin, mode.value))
        new = (mode.value | ENABLE_VIRTUAL_TERMINAL_INPUT | ENABLE_EXTENDED_FLAGS) & ~(
            ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT | ENABLE_PROCESSED_INPUT | ENABLE_QUICK_EDIT_MODE)
        kernel32.SetConsoleMode(self.hin, new)

        kernel32.GetConsoleMode(self.hout, ctypes.byref(mode))
        self.saved.append((self.hout, mode.value))
        kernel32.SetConsoleMode(self.hout, mode.value | ENABLE_PROCESSED_OUTPUT | ENABLE_WRAP_AT_EOL_OUTPUT
                                | ENABLE_VIRTUAL_TERMINAL_PROCESSING | DISABLE_NEWLINE_AUTO_RETURN)
        kernel32.SetConsoleCP(65001)
        kernel32.SetConsoleOutputCP(65001)
        return self

    def __exit__(self, *exc):
        for handle, mode in self.saved:
            kernel32.SetConsoleMode(handle, mode)
        kernel32.SetConsoleCP(self.saved_cp[0])
        kernel32.SetConsoleOutputCP(self.saved_cp[1])

    def read(self) -> str:
        """Block until keyboard input arrives; returns it as text (VT sequences included)."""
        buf = ctypes.create_unicode_buffer(1024)
        n = wintypes.DWORD()
        if not kernel32.ReadConsoleW(self.hin, buf, len(buf), ctypes.byref(n), None):
            raise OSError(ctypes.get_last_error(), "ReadConsoleW failed")
        return buf[: n.value]

    def write(self, text: str) -> None:
        sys.stdout.write(text)
        sys.stdout.flush()


def fetch_sessions(port: int) -> list[dict]:
    try:
        with connect(f"ws://127.0.0.1:{port}/ctl", open_timeout=3) as ws:
            return json.loads(ws.recv(timeout=5))["sessions"]
    except (OSError, InvalidStatus, TimeoutError):
        raise SystemExit("Tether agent isn't running. Start it with: tether-agent")


def match_session(sessions: list[dict], key: str | None) -> dict:
    if not sessions:
        raise SystemExit("No sessions. Start one from the phone.")
    if key is None:
        if len(sessions) == 1:
            return sessions[0]
        raise SystemExit("Several sessions are running; name one:\n" + format_table(sessions))
    for s in sessions:
        if s["id"] == key:
            return s
    by_name = [s for s in sessions if s["name"].lower() == key.lower()]
    if len(by_name) == 1:
        return by_name[0]
    by_prefix = [s for s in sessions if s["id"].startswith(key) or s["name"].lower().startswith(key.lower())]
    if len(by_prefix) == 1:
        return by_prefix[0]
    raise SystemExit(f"No single session matches {key!r}:\n" + format_table(sessions))


def format_table(sessions: list[dict]) -> str:
    rows = [("ID", "NAME", "SHELL", "SIZE", "VIEWERS")]
    for s in sessions:
        v = s["viewers"]
        rows.append((s["id"], s["name"], s["shell"], f"{s['cols']}x{s['rows']}",
                     f"{v['phone']} phone, {v['pc']} pc"))
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    return "\n".join("  ".join(c.ljust(w) for c, w in zip(r, widths)).rstrip() for r in rows)


def attach(port: int, key: str | None) -> int:
    session = match_session(fetch_sessions(port), key)
    url = f"ws://127.0.0.1:{port}/s/{session['id']}?role=pc"
    ended: dict | None = None
    with RawConsole() as con, connect(url, max_size=None, compression="deflate") as ws:
        size = shutil.get_terminal_size()
        ws.send(json.dumps({"type": "resize", "cols": size.columns, "rows": size.lines}))
        stop = threading.Event()

        def pump_input():
            try:
                while not stop.is_set():
                    text = con.read()
                    if DETACH in text:
                        before = text.split(DETACH, 1)[0]
                        if before:
                            ws.send(before.encode("utf-8", "surrogatepass"))
                        break
                    if text:
                        ws.send(text.encode("utf-8", "surrogatepass"))
            except (OSError, ConnectionClosed):
                pass
            stop.set()
            ws.close()

        def watch_size():
            last = size
            while not stop.wait(0.25):
                now = shutil.get_terminal_size()
                if now != last:
                    last = now
                    try:
                        ws.send(json.dumps({"type": "resize", "cols": now.columns, "rows": now.lines}))
                    except ConnectionClosed:
                        return

        threading.Thread(target=pump_input, daemon=True).start()
        threading.Thread(target=watch_size, daemon=True).start()
        con.write("\x1b[H\x1b[2J\x1b[3J")
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        try:
            for msg in ws:
                if isinstance(msg, bytes):
                    con.write(decoder.decode(msg))
                else:
                    data = json.loads(msg)
                    if data["type"] == "exit":
                        ended = data
        except ConnectionClosed:
            pass
        stop.set()
        # Leave the remote app's alt screen / colours / hidden cursor behind.
        con.write("\x1b[?1049l\x1b[0m\x1b[?25h\r\n")
    if ended is not None:
        print(f"[session ended, exit code {ended.get('code')}]")
        time.sleep(0.8)
    else:
        print(f"[detached from {session['name']}; it keeps running]")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="rt", description="Tether sessions on this PC")
    p.add_argument("--port", type=int, default=config.PORT)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="show running sessions")
    a = sub.add_parser("attach", help="open a session in this console (Ctrl+] detaches)")
    a.add_argument("session", nargs="?", help="session id or name; optional if only one runs")
    ag = sub.add_parser("agent", help="run the host agent")
    ag.add_argument("--no-tray", action="store_true")
    au = sub.add_parser("autostart", help="start the agent at sign-in")
    au.add_argument("state", choices=["on", "off"])
    args = p.parse_args(argv)

    if args.cmd == "list":
        sessions = fetch_sessions(args.port)
        print(format_table(sessions) if sessions else "No sessions. Start one from the phone.")
        return 0
    if args.cmd == "attach":
        return attach(args.port, args.session)
    if args.cmd == "agent":
        from .agent import main as agent_main
        return agent_main(["--port", str(args.port)] + (["--no-tray"] if args.no_tray else []))
    if args.cmd == "autostart":
        from .agent import set_autostart
        set_autostart(args.state == "on")
        print(f"Autostart {args.state}.")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())

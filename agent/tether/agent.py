"""tether-agent: hosts the sessions, serves localhost + the Tailscale IP, shows the tray."""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import shutil
import subprocess
import sys
import threading

from . import config
from .server import TetherServer
from .sessions import SessionManager

log = logging.getLogger("tether")

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def tailscale_ip() -> str | None:
    exe = shutil.which("tailscale") or r"C:\Program Files\Tailscale\tailscale.exe"
    try:
        out = subprocess.run([exe, "ip", "-4"], capture_output=True, text=True, timeout=10,
                             creationflags=subprocess.CREATE_NO_WINDOW).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    for line in out.split():
        if line.startswith("100."):
            return line
    return None


class Agent:
    def __init__(self, port: int):
        self.port = port
        self.manager = SessionManager()
        self.server = TetherServer(self.manager)
        self.address: str | None = None  # tailnet address once bound
        self.loop: asyncio.AbstractEventLoop | None = None
        self.ready = threading.Event()
        self.error: BaseException | None = None
        self._stop: asyncio.Event | None = None

    def run_loop(self) -> None:
        try:
            asyncio.run(self._main())
        except BaseException as e:  # surfaced to the main thread
            self.error = e
            self.ready.set()

    async def _main(self) -> None:
        self.loop = asyncio.get_running_loop()
        self._stop = asyncio.Event()
        # Localhost always (for `rt attach`); the tailnet address when Tailscale is up.
        local = await self.server.serve("127.0.0.1", self.port)
        log.info("listening on 127.0.0.1:%d", self.port)
        self.ready.set()
        tailnet = asyncio.create_task(self._bind_tailnet())
        try:
            await self._stop.wait()
        finally:
            tailnet.cancel()
            await self.manager.close_all()
            local.close()

    async def _bind_tailnet(self) -> None:
        while True:
            ip = await asyncio.to_thread(tailscale_ip)
            if ip:
                try:
                    await self.server.serve(ip, self.port)
                    self.address = f"{ip}:{self.port}"
                    log.info("listening on %s", self.address)
                    self.manager.changed()
                    return
                except OSError as e:
                    log.warning("could not bind %s: %s", ip, e)
            await asyncio.sleep(10)

    def stop(self) -> None:
        if self.loop and self._stop:
            self.loop.call_soon_threadsafe(self._stop.set)


def set_autostart(enable: bool) -> None:
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enable:
            pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
            winreg.SetValueEx(key, "Tether", 0, winreg.REG_SZ, f'"{pyw}" -m tether agent')
        else:
            try:
                winreg.DeleteValue(key, "Tether")
            except FileNotFoundError:
                pass


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="tether-agent")
    p.add_argument("--port", type=int, default=config.PORT)
    p.add_argument("--no-tray", action="store_true", help="run in the console without a tray icon")
    args = p.parse_args(argv)

    log_dir = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "Tether")
    os.makedirs(log_dir, exist_ok=True)
    handlers = [logging.FileHandler(os.path.join(log_dir, "agent.log"), encoding="utf-8")]
    if sys.stderr:  # None under pythonw
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.INFO, handlers=handlers,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    agent = Agent(args.port)
    thread = threading.Thread(target=agent.run_loop, name="tether-loop", daemon=True)
    thread.start()
    agent.ready.wait()
    if agent.error:
        if isinstance(agent.error, OSError):
            log.error("port %d is busy; is tether-agent already running?", args.port)
            return 1
        raise agent.error

    if args.no_tray:
        try:
            thread.join()
        except KeyboardInterrupt:
            agent.stop()
            thread.join(10)
    else:
        from .tray import run_tray
        run_tray(agent)
        agent.stop()
        thread.join(10)
    return 0

"""Tray icon: lists live sessions, click one to open it in a Windows Terminal tab."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys

import pystray
from PIL import Image, ImageDraw

BG = (21, 19, 17, 255)       # #151311
ACCENT = (240, 116, 62, 255)  # #F0743E


def make_icon(size: int = 64) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / 64
    d.rounded_rectangle((2 * s, 2 * s, 62 * s, 62 * s), radius=14 * s, fill=BG)
    w = max(2, round(6 * s))
    d.line([(16 * s, 20 * s), (30 * s, 32 * s), (16 * s, 44 * s)], fill=ACCENT, width=w, joint="curve")
    d.line([(34 * s, 44 * s), (48 * s, 44 * s)], fill=ACCENT, width=w)
    return img


def console_python() -> str:
    """python.exe next to the running interpreter (the agent may run under pythonw)."""
    exe = sys.executable
    if os.path.basename(exe).lower() == "pythonw.exe":
        exe = os.path.join(os.path.dirname(exe), "python.exe")
    return exe


def open_attach(session_id: str, title: str) -> None:
    cmd = [console_python(), "-m", "tether", "attach", session_id]
    wt = shutil.which("wt")
    if wt:
        # ';' separates commands in wt's command line.
        title = title.replace(";", " ")
        subprocess.Popen([wt, "-w", "0", "new-tab", "--title", title, "--suppressApplicationTitle", *cmd])
    else:
        subprocess.Popen(cmd, creationflags=subprocess.CREATE_NEW_CONSOLE)


def run_tray(agent) -> None:
    def session_item(info):
        label = f"{info['name']}  ({info['shell']})"
        return pystray.MenuItem(label, lambda: open_attach(info["id"], info["name"]))

    def items():
        status = f"Listening on {agent.address}" if agent.address else "Waiting for Tailscale"
        yield pystray.MenuItem(status, None, enabled=False)
        yield pystray.Menu.SEPARATOR
        sessions = agent.manager.snapshot
        if sessions:
            for info in sessions:
                yield session_item(info)
        else:
            yield pystray.MenuItem("No sessions. Start one from the phone.", None, enabled=False)
        yield pystray.Menu.SEPARATOR
        yield pystray.MenuItem("Quit Tether", lambda icon: icon.stop())

    icon = pystray.Icon("tether", make_icon(), "Tether", menu=pystray.Menu(items))

    def refresh():
        n = len(agent.manager.snapshot)
        icon.title = f"Tether: {n} session{'s' if n != 1 else ''}"
        icon.update_menu()

    agent.manager.listeners.append(refresh)
    icon.run()

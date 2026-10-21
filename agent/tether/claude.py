"""Claude Code helpers: recent project folders and the alert hooks.

Sessions started as kind "claude" run `claude` inside PowerShell with an extra
--settings file. That file adds Notification and Stop hooks which call
`python -m tether notify`, which tells the agent over the localhost control
channel, which forwards an alert to every connected phone.
"""

from __future__ import annotations

import json
import os
import re
import sys

from . import config

MAX_PROJECTS = 12


def _slug(path: str) -> str:
    # How Claude Code names a project's folder under ~/.claude/projects.
    return re.sub(r"[^A-Za-z0-9]", "-", path)


def recent_projects(home: str | None = None, limit: int = MAX_PROJECTS) -> list[dict]:
    """Project folders Claude Code knows about, most recently used first.
    Only folders that still exist are returned."""
    home = home or os.path.expanduser("~")
    try:
        with open(os.path.join(home, ".claude.json"), encoding="utf-8") as f:
            known = json.load(f).get("projects", {})
    except (OSError, ValueError, AttributeError):
        known = {}
    history = os.path.join(home, ".claude", "projects")
    seen: dict[str, dict] = {}
    for raw in known:
        path = os.path.normpath(raw)
        key = os.path.normcase(path)
        if key in seen or not os.path.isdir(path):
            continue
        try:
            used = os.path.getmtime(os.path.join(history, _slug(path)))
        except OSError:
            used = 0.0
        seen[key] = {"path": path, "name": os.path.basename(path) or path, "used": used}
    return sorted(seen.values(), key=lambda p: p["used"], reverse=True)[:limit]


def _python() -> str:
    # Hooks pipe JSON on stdin, so use the console interpreter, not pythonw.
    exe = sys.executable
    console = os.path.join(os.path.dirname(exe), "python.exe")
    return (console if os.path.exists(console) else exe).replace("\\", "/")


def hook_settings(sid: str, port: int) -> dict:
    # Exit code 2 from a Stop hook tells Claude to keep working, and argparse
    # exits 2 on bad arguments, so keep this in the canonical order.
    cmd = f'"{_python()}" -m tether --port {port} notify --session {sid}'
    hook = [{"hooks": [{"type": "command", "command": cmd, "timeout": 10}]}]
    return {"hooks": {"Notification": hook, "Stop": hook}}


def settings_path(sid: str) -> str:
    return os.path.join(config.data_dir(), f"claude-{sid}.json")


def write_settings(sid: str, port: int) -> str:
    path = settings_path(sid)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(hook_settings(sid, port), f)
    return path


def remove_settings(sid: str) -> None:
    try:
        os.remove(settings_path(sid))
    except OSError:
        pass


def alert_text(event: dict) -> str:
    """One line for the phone notification, from a hook's stdin JSON."""
    if event.get("hook_event_name") == "Stop":
        return "Finished. Your turn."
    msg = str(event.get("message") or "").strip()
    return msg[:200] or "Claude needs your attention."


def notify(port: int, sid: str, stdin=None) -> int:
    """Entry point for the hook. Never fails loudly: a broken alert must not
    get in Claude's way."""
    from websockets.sync.client import connect
    try:
        event = json.loads((stdin or sys.stdin).read() or "{}")
    except (ValueError, OSError):
        event = {}
    try:
        with connect(f"ws://127.0.0.1:{port}/ctl", open_timeout=3) as ws:
            ws.recv(timeout=3)  # initial session list
            ws.send(json.dumps({"type": "notify", "id": sid, "message": alert_text(event)}))
    except Exception:
        pass
    return 0

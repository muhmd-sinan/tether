"""WebSocket protocol.

Control channel, ws://<host>:8722/ctl (JSON text frames)
  server -> {"type": "sessions", "sessions": [info, ...]}   on connect and on every change
  client -> {"type": "new", "shell": "powershell"|"cmd"|"claude", "name"?: str,
             "cols"?: int, "rows"?: int, "cwd"?: str}
  server -> {"type": "created", "id": str}
  client -> {"type": "close", "id": str}
  client -> {"type": "rename", "id": str, "name": str}
  client -> {"type": "projects"}
  server -> {"type": "projects", "projects": [{"path", "name", "used"}, ...]}
  client -> {"type": "notify", "id": str, "message": str}   localhost only (Claude hooks)
  server -> {"type": "alert", "id": str, "name": str, "message": str}
  server -> {"type": "error", "message": str}

Session channel, ws://<host>:8722/s/<id>?role=phone|pc
  server -> {"type": "hello", ...info}   then binary scrollback replay
  server -> {"type": "live"}             everything after this is live output
  server -> binary frames                raw UTF-8 terminal output
  server -> {"type": "size", "cols", "rows"} | {"type": "renamed", "name"} | {"type": "exit", "code"}
  client -> binary frames                raw UTF-8 keyboard input
  client -> {"type": "resize", "cols", "rows"}   this viewer's own window size

The PTY follows the size of whichever viewer typed last. Automatic terminal
replies (focus, device attributes) don't count as typing.

Unknown session ids are closed with code 4404. Requests carrying an Origin
header (i.e. from a browser page) are refused, so a web page on the PC or
the tailnet cannot open a shell.
"""

from __future__ import annotations

import asyncio
import codecs
import json
import logging
from urllib.parse import parse_qs, urlsplit

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

from . import claude
from .sessions import SessionManager, Viewer

log = logging.getLogger(__name__)

LOOPBACK = ("127.0.0.1", "::1")


class TetherServer:
    def __init__(self, manager: SessionManager):
        self.manager = manager

    async def handler(self, ws) -> None:
        url = urlsplit(ws.request.path)
        query = parse_qs(url.query)
        if url.path == "/ctl":
            await self.control(ws)
        elif url.path.startswith("/s/"):
            role = query.get("role", ["phone"])[0]
            await self.attach(ws, url.path[3:], "pc" if role == "pc" else "phone")
        else:
            await ws.close(4404, "not found")

    async def control(self, ws) -> None:
        viewer = Viewer(ws, "ctl")
        pump = asyncio.create_task(viewer.pump())

        def push():
            viewer.send_json({"type": "sessions", "sessions": self.manager.list()})

        def alert(info: dict, message: str):
            viewer.send_json({"type": "alert", "id": info["id"], "name": info["name"], "message": message})

        push()
        self.manager.listeners.append(push)
        self.manager.alert_listeners.append(alert)
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                    await self.command(viewer, msg)
                except (ValueError, TypeError, KeyError) as e:
                    viewer.send_json({"type": "error", "message": str(e)})
                except OSError as e:
                    log.exception("command failed")
                    viewer.send_json({"type": "error", "message": f"could not start shell: {e}"})
        except ConnectionClosed:
            pass
        finally:
            self.manager.listeners.remove(push)
            self.manager.alert_listeners.remove(alert)
            pump.cancel()

    async def command(self, viewer: Viewer, msg: dict) -> None:
        kind = msg.get("type")
        if kind == "new":
            session = self.manager.create(msg.get("shell", "powershell"), msg.get("name"),
                                          msg.get("cols"), msg.get("rows"), msg.get("cwd"))
            viewer.send_json({"type": "created", "id": session.id})
        elif kind == "projects":
            projects = await asyncio.to_thread(claude.recent_projects)
            viewer.send_json({"type": "projects", "projects": projects})
        elif kind == "notify":
            # Only hooks running on this PC may raise alerts on the phone.
            if (viewer.ws.remote_address or ("",))[0] not in LOOPBACK:
                raise ValueError("notify is only accepted from this PC")
            self.manager.alert(str(msg["id"]), str(msg.get("message", ""))[:200])
        elif kind == "close":
            session = self.manager.get(msg["id"])
            if session:
                await session.kill()
        elif kind == "rename":
            self.manager.rename(msg["id"], msg["name"])
        elif kind == "list":
            viewer.send_json({"type": "sessions", "sessions": self.manager.list()})
        else:
            raise ValueError(f"unknown command {kind!r}")

    async def attach(self, ws, sid: str, role: str) -> None:
        session = self.manager.get(sid)
        if session is None:
            await ws.close(4404, "no such session")
            return
        viewer = Viewer(ws, role)
        pump = asyncio.create_task(viewer.pump())
        session.attach(viewer)
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        try:
            async for msg in ws:
                if isinstance(msg, bytes):
                    session.input_from(viewer, decoder.decode(msg))
                    continue
                try:
                    ctl = json.loads(msg)
                except ValueError:
                    continue
                if ctl.get("type") == "resize":
                    try:
                        session.viewer_size(viewer, ctl["cols"], ctl["rows"])
                    except (KeyError, TypeError, ValueError):
                        pass
        except ConnectionClosed:
            pass
        finally:
            session.detach(viewer)
            if not viewer.closed:
                pump.cancel()
            else:
                # Let the exit message and close frame flush.
                try:
                    await asyncio.wait_for(pump, 2)
                except (asyncio.TimeoutError, asyncio.CancelledError):
                    pass

    def serve(self, host, port: int):
        # origins=[None] accepts only clients that send no Origin header.
        if port:
            self.manager.port = port
        return serve(self.handler, host, port, origins=[None],
                     max_size=1 << 20, ping_interval=20, ping_timeout=20)

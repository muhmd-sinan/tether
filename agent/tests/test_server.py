"""End-to-end tests against real ConPTY shells over a localhost websocket."""

import asyncio
import json

import pytest
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from tether.server import TetherServer
from tether.sessions import Scrollback, SessionManager

MARK = "TETHER_ü€_OK"


def test_scrollback_drops_whole_chunks():
    sb = Scrollback(limit=10)
    for chunk in (b"aaaa", b"bbbb", b"cccc"):
        sb.append(chunk)
    assert sb.snapshot() == [b"bbbb", b"cccc"]
    assert sb.size == 8


def test_scrollback_keeps_single_oversized_chunk():
    sb = Scrollback(limit=4)
    sb.append(b"0123456789")
    assert sb.snapshot() == [b"0123456789"]


async def recv_json(ws, kind, timeout=10):
    """Read frames until a JSON message of the given type arrives."""
    async with asyncio.timeout(timeout):
        while True:
            msg = await ws.recv()
            if isinstance(msg, str):
                data = json.loads(msg)
                if data["type"] == kind:
                    return data


async def read_until(ws, needle: str, timeout=15) -> str:
    buf = ""
    async with asyncio.timeout(timeout):
        while needle not in buf:
            msg = await ws.recv()
            if isinstance(msg, bytes):
                # ConPTY toggles cursor visibility around repaints.
                buf += msg.decode("utf-8", "replace")
                buf = buf.replace("\x1b[?25l", "").replace("\x1b[?25h", "")
    return buf


@pytest.fixture
def run():
    """Run an async test body with a live server; passes (url, manager)."""
    def runner(body):
        async def main():
            manager = SessionManager()
            async with TetherServer(manager).serve("127.0.0.1", 0) as server:
                port = server.sockets[0].getsockname()[1]
                try:
                    await body(f"ws://127.0.0.1:{port}", manager)
                finally:
                    await manager.close_all()
                    await asyncio.sleep(0.5)
        asyncio.run(main())
    return runner


@pytest.mark.parametrize("shell", ["cmd", "powershell"])
def test_session_roundtrip(run, shell):
    async def body(url, manager):
        async with connect(url + "/ctl") as ctl:
            first = await recv_json(ctl, "sessions")
            assert first["sessions"] == []

            await ctl.send(json.dumps({"type": "new", "shell": shell}))
            sid = (await recv_json(ctl, "created"))["id"]

            async with connect(f"{url}/s/{sid}?role=phone") as phone:
                hello = await recv_json(phone, "hello")
                assert (hello["cols"], hello["rows"]) == (120, 30)
                await recv_json(phone, "live")
                await asyncio.sleep(2.5 if shell == "powershell" else 1)
                await phone.send(f"echo {MARK}\r".encode())
                await read_until(phone, MARK)

            # A late viewer gets the earlier output replayed.
            async with connect(f"{url}/s/{sid}?role=pc") as pc:
                await recv_json(pc, "hello")
                replay = await read_until(pc, MARK)
                assert MARK in replay

            await ctl.send(json.dumps({"type": "close", "id": sid}))
            async with asyncio.timeout(10):
                while (await recv_json(ctl, "sessions"))["sessions"]:
                    pass
    run(body)


def test_only_pc_can_resize(run):
    async def body(url, manager):
        async with connect(url + "/ctl") as ctl:
            await recv_json(ctl, "sessions")
            await ctl.send(json.dumps({"type": "new", "shell": "cmd"}))
            sid = (await recv_json(ctl, "created"))["id"]

        async with connect(f"{url}/s/{sid}?role=phone") as phone, \
                connect(f"{url}/s/{sid}?role=pc") as pc:
            await recv_json(phone, "live")
            await recv_json(pc, "live")

            await phone.send(json.dumps({"type": "resize", "cols": 40, "rows": 20}))
            await asyncio.sleep(0.3)
            assert (manager.get(sid).cols, manager.get(sid).rows) == (120, 30)

            await pc.send(json.dumps({"type": "resize", "cols": 100, "rows": 25}))
            size = await recv_json(phone, "size")
            assert (size["cols"], size["rows"]) == (100, 25)
    run(body)


def test_exit_notifies_viewers(run):
    async def body(url, manager):
        session = manager.create("cmd")
        async with connect(f"{url}/s/{session.id}?role=phone") as phone:
            await recv_json(phone, "live")
            await asyncio.sleep(1)
            await phone.send(b"exit\r")
            done = await recv_json(phone, "exit")
            assert done["code"] == 0
        assert manager.list() == []
    run(body)


def test_unknown_session_closes_4404(run):
    async def body(url, manager):
        async with connect(f"{url}/s/nope") as ws:
            await ws.wait_closed()
            assert ws.close_code == 4404
    run(body)


def test_browser_origin_refused(run):
    async def body(url, manager):
        with pytest.raises(InvalidStatus):
            async with connect(url + "/ctl", origin="http://evil.example"):
                pass
    run(body)


def test_bad_shell_reports_error(run):
    async def body(url, manager):
        async with connect(url + "/ctl") as ctl:
            await recv_json(ctl, "sessions")
            await ctl.send(json.dumps({"type": "new", "shell": "bash"}))
            err = await recv_json(ctl, "error")
            assert "shell must be" in err["message"]
    run(body)

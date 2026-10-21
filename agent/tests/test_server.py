"""End-to-end tests against real ConPTY shells over a localhost websocket."""

import asyncio
import json
import os

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


async def recv_size(ws, cols, rows, timeout=10):
    """Read until the session reports the given size (earlier sizes are skipped)."""
    async with asyncio.timeout(timeout):
        while True:
            size = await recv_json(ws, "size")
            if (size["cols"], size["rows"]) == (cols, rows):
                return


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


def test_last_typer_sets_size(run):
    async def body(url, manager):
        async with connect(url + "/ctl") as ctl:
            await recv_json(ctl, "sessions")
            await ctl.send(json.dumps({"type": "new", "shell": "cmd", "cols": 46, "rows": 40}))
            sid = (await recv_json(ctl, "created"))["id"]
        session = manager.get(sid)
        assert (session.cols, session.rows) == (46, 40)

        async with connect(f"{url}/s/{sid}?role=phone") as phone, \
                connect(f"{url}/s/{sid}?role=pc") as pc:
            await recv_json(phone, "live")
            await recv_json(pc, "live")

            # The first viewer to report a size drives; the second is only remembered.
            await phone.send(json.dumps({"type": "resize", "cols": 46, "rows": 38}))
            await recv_json(pc, "size")
            await pc.send(json.dumps({"type": "resize", "cols": 120, "rows": 30}))
            await asyncio.sleep(0.3)
            assert (session.cols, session.rows) == (46, 38)

            # Focus reports and device-attribute answers are not typing.
            await pc.send(b"\x1b[I")
            await pc.send(b"\x1b[?61;4c")
            await asyncio.sleep(0.3)
            assert (session.cols, session.rows) == (46, 38)

            # Typing on the PC hands it the size; typing on the phone takes it back.
            await pc.send(b"rem\r")
            await recv_size(phone, 120, 30)
            await phone.send(b"rem\r")
            await recv_size(pc, 46, 38)
    run(body)


def test_reply_pattern():
    from tether.sessions import REPLY
    for reply in ["\x1b[I", "\x1b[O", "\x1b[?62;22c", "\x1b[>0;10;1c", "\x1b[12;40R",
                  "\x1b[?0u", "\x1bP>|WindowsTerminal\x1b\\", "\x1b]11;rgb:1515/1313/1111\x07",
                  "\x1b[I\x1b[?1;2c"]:
        assert REPLY.fullmatch(reply), repr(reply)
    for typing in ["a", "\r", "\x03", "\x1b", "\x1b[A", "\x1b[Z", "\x1b[200~hi\x1b[201~", "\x1b[I x"]:
        assert not REPLY.fullmatch(typing), repr(typing)


def test_claude_session_runs_in_folder(run, tmp_path):
    async def body(url, manager):
        async with connect(url + "/ctl") as ctl:
            await recv_json(ctl, "sessions")
            await ctl.send(json.dumps({"type": "new", "shell": "claude", "cwd": "C:\\no\\such\\dir"}))
            assert "folder not found" in (await recv_json(ctl, "error"))["message"]

        from tether import claude
        from tether.sessions import shell_command
        exe, args = shell_command("claude", "C:\\x\\s.json")
        assert "; claude --settings 'C:\\x\\s.json'" in args and "-NoExit" in args
        hooks = claude.hook_settings("abcd1234", 9999)["hooks"]
        cmd = hooks["Stop"][0]["hooks"][0]["command"]
        assert "-m tether --port 9999 notify --session abcd1234" in cmd and "\\" not in cmd
        from tether.cli import main as rt_main
        assert rt_main(["--port", "1", "notify", "--session", "x"]) == 0  # no agent: still exits 0
        assert set(hooks) == {"Notification", "Stop"}
        assert claude.alert_text({"hook_event_name": "Stop"}).startswith("Finished")
        assert claude.alert_text({"hook_event_name": "Notification",
                                  "message": "Claude needs your permission to use Bash"}) \
            == "Claude needs your permission to use Bash"
    run(body)


def test_notify_reaches_phone_only_from_localhost(run):
    async def body(url, manager):
        session = manager.create("cmd", "build")
        async with connect(url + "/ctl") as phone, connect(url + "/ctl") as hook:
            await recv_json(phone, "sessions")
            await recv_json(hook, "sessions")
            await hook.send(json.dumps({"type": "notify", "id": session.id, "message": "Finished. Your turn."}))
            alert = await recv_json(phone, "alert")
            assert alert == {"type": "alert", "id": session.id, "name": "build",
                             "message": "Finished. Your turn."}
    run(body)


def test_recent_projects(tmp_path):
    from tether.claude import _slug, recent_projects
    home = tmp_path
    old, new, gone = tmp_path / "old proj", tmp_path / "new.proj", tmp_path / "gone"
    old.mkdir()
    new.mkdir()
    hist = home / ".claude" / "projects"
    for d, t in ((old, 1000), (new, 2000)):
        (hist / _slug(str(d))).mkdir(parents=True)
        os.utime(hist / _slug(str(d)), (t, t))
    data = {"projects": {str(old): {}, str(old).replace("\\", "/"): {}, str(new): {}, str(gone): {}}}
    (home / ".claude.json").write_text(json.dumps(data), encoding="utf-8")
    got = recent_projects(str(home))
    assert [p["name"] for p in got] == ["new.proj", "old proj"]
    assert got[0]["path"] == str(new)


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

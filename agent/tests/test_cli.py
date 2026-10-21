import pytest

from tether.cli import format_table, match_session


def info(sid, name, shell="cmd"):
    return {"id": sid, "name": name, "shell": shell, "cols": 120, "rows": 30,
            "viewers": {"phone": 1, "pc": 0}}


SESSIONS = [info("a1b2c3d4", "PowerShell 1", "powershell"), info("ffee0011", "cmd 1"),
            info("ffee9922", "build")]


def test_match_by_id_name_and_prefix():
    assert match_session(SESSIONS, "a1b2c3d4")["name"] == "PowerShell 1"
    assert match_session(SESSIONS, "BUILD")["id"] == "ffee9922"
    assert match_session(SESSIONS, "power")["id"] == "a1b2c3d4"


def test_ambiguous_prefix_fails():
    with pytest.raises(SystemExit):
        match_session(SESSIONS, "ffee")


def test_single_session_needs_no_key():
    assert match_session(SESSIONS[:1], None)["id"] == "a1b2c3d4"
    with pytest.raises(SystemExit):
        match_session(SESSIONS, None)


def test_no_sessions():
    with pytest.raises(SystemExit, match="Start one from the phone"):
        match_session([], "x")


def test_table_columns():
    lines = format_table(SESSIONS).splitlines()
    assert lines[0].split() == ["ID", "NAME", "SHELL", "SIZE", "VIEWERS"]
    assert "120x30" in lines[1] and "1 phone, 0 pc" in lines[1]

import os

PORT = 8722

# Windows Terminal's default window size. Sessions start here unless the
# device that creates them reports its own size. After that the size follows
# whichever viewer typed last (see Session.input_from).
DEFAULT_COLS = 120
DEFAULT_ROWS = 30

# Raw output kept per session for replay to late or reconnecting viewers.
SCROLLBACK_BYTES = 2 * 1024 * 1024


def data_dir() -> str:
    """%LOCALAPPDATA%\\Tether: log file and per-session Claude settings."""
    path = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "Tether")
    os.makedirs(path, exist_ok=True)
    return path

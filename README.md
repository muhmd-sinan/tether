# Tether

Drive your Windows PC's terminal from your Android phone over Tailscale.
Start PowerShell, cmd or Claude Code sessions from the phone; they keep
running on the PC when the phone disconnects, and the PC can open the same
session at the same time.

- Sessions are started from the phone and live on the PC until closed (or the PC reboots).
- Both devices can type. The terminal takes the size of whoever typed last.
- Claude Code runs as your real `claude`: same login, settings, profile and history.
  The phone gets a notification when Claude finishes or needs input.
- No accounts or pairing: anything on your tailnet can connect, so only use
  this on a tailnet you control.

## What you need

| PC (Windows 10/11) | Phone (Android 8+) |
| --- | --- |
| [Tailscale](https://tailscale.com/download/windows), signed in | [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn), same account |
| Python 3.11+ ([python.org](https://www.python.org/downloads/), tick "Add to PATH") | |
| Git | |
| Optional: [Claude Code](https://docs.anthropic.com/en/docs/claude-code) (`npm i -g @anthropic-ai/claude-code`) | |
| To build the app: JDK 17 and the Android SDK (easiest: [Android Studio](https://developer.android.com/studio)) | |

## 1. Set up the PC

```bat
git clone https://github.com/muhmd-sinan/tether.git
cd tether
pip install -e agent
```

Then double-click **`Start Tether.bat`**. It starts Tailscale if needed, starts
the Tether agent (a `>_` icon in the tray), and prints the PC's Tailscale
address, e.g. `On the phone, connect to 100.83.213.64 port 8722.`

To start the agent automatically at sign-in:

```bat
rt autostart on
```

If Windows Firewall asks about Python, allow it on private networks. The agent
only listens on `127.0.0.1` and the Tailscale address, never your LAN.

## 2. Install the phone app

Easiest: on the phone, download `tether-<version>.apk` from the
[Releases](https://github.com/muhmd-sinan/tether/releases) page and open it
(allow installing from your browser or file manager when asked).

Or build it yourself (Android Studio: open the `android` folder and press Run,
or from a terminal). If you switch between the release APK and your own build,
uninstall the old one first: they are signed with different keys.

```bat
cd android
gradlew assembleDebug
```

From a terminal, `JAVA_HOME` must point at a JDK 17 and the SDK must be found:
set `ANDROID_HOME`, or create `android/local.properties` containing
`sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk` (Android Studio does this for you).

The APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. Copy it to
the phone and open it, or with USB debugging on:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Open Tether, enter the PC's Tailscale address (or its Tailscale machine name)
and port `8722`, and tap **Connect to PC**. Allow notifications when asked.

## Using it

**On the phone**
- **+** starts a session: Claude Code (pick one of your recent Claude folders or
  type a path), PowerShell or cmd. Swipe or tap tabs to switch; long-press a tab
  to rename or close it.
- Tap the terminal for the keyboard; long-press to paste or copy output;
  pinch to change the text size.
- The extra-keys row scrolls sideways: ESC, ^C, TAB, ⇧TAB (cycles Claude's
  modes), CTRL, ALT, arrows, ⇧↵ (new line in Claude's prompt without sending).
- ⋮ → **Background alerts** keeps a quiet connection open so Claude alerts arrive
  while the app is closed. Turn it off there or from its notification.

**On the PC**
- Click the tray icon and pick a session to open it in a console window, or:
  ```bat
  rt list
  rt attach "PowerShell 1"
  ```
- `Ctrl+]` detaches; the session keeps running.

## Troubleshooting

- **"Can't reach the PC"**: check Tailscale is connected on both devices, and
  that the tray icon is there (run `Start Tether.bat` again; it skips what's
  already running). The agent log is `%LOCALAPPDATA%\Tether\agent.log`.
- **Alerts stop after a while**: some phones (realme, Xiaomi, OnePlus, Samsung)
  kill background apps. Set Tether's battery usage to *Unrestricted* /
  *No restrictions* in the phone's app settings.
- **Claude says it isn't logged in**: run `claude` once in a normal PC terminal
  and log in; phone sessions use the same login.
- **Updating**: `git pull`, then quit Tether from the tray and run
  `Start Tether.bat`. Restarting the agent ends running sessions.

## Development

```bat
cd agent
pip install -e .[test]
python -m pytest

cd ..\android
gradlew testDebugUnitTest
```

- `agent/` - Python host agent: ConPTY sessions (pywinpty), WebSocket server,
  tray icon, `rt` CLI. The protocol is described at the top of `tether/server.py`.
- `android/` - Kotlin/Compose app with a vendored Termux terminal emulator.

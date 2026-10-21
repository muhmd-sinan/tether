@echo off
setlocal EnableDelayedExpansion
title Start Tether
rem Starts everything the phone needs: Tailscale (service + tray app, connected)
rem and the Tether agent (tray icon). Safe to run again; running parts are skipped.

rem Windows tools first, so Git/MSYS find.exe and timeout.exe can't shadow them.
set "PATH=%SystemRoot%\System32;%SystemRoot%;%SystemRoot%\System32\WindowsPowerShell\v1.0;%PATH%"

set "TS_DIR=%ProgramFiles%\Tailscale"
set "TS=%TS_DIR%\tailscale.exe"
set "PORT=8722"

if not exist "%TS%" (
    echo Tailscale is not installed. Get it from https://tailscale.com/download/windows
    goto :fail
)

rem ---- 1. Tailscale service (needs admin to start; you'll get a UAC prompt) ----
sc query Tailscale | find "RUNNING" >nul
if errorlevel 1 (
    echo Starting the Tailscale service...
    powershell -NoProfile -Command "Start-Process sc.exe -ArgumentList 'start','Tailscale' -Verb RunAs -WindowStyle Hidden -Wait"
    timeout /t 3 /nobreak >nul
    sc query Tailscale | find "RUNNING" >nul
    if errorlevel 1 (
        echo Could not start the Tailscale service.
        goto :fail
    )
)
echo [ok] Tailscale service running

rem ---- 2. Tailscale tray app ----
tasklist /FI "IMAGENAME eq tailscale-ipn.exe" | find /I "tailscale-ipn.exe" >nul
if errorlevel 1 start "" "%TS_DIR%\tailscale-ipn.exe"

rem ---- 3. Connected to the tailnet ----
"%TS%" status >nul 2>&1
if errorlevel 1 (
    echo Connecting to Tailscale. Sign in in the browser if asked...
    "%TS%" up
)
set "IP="
for /L %%n in (1,1,30) do (
    if not defined IP (
        for /f "usebackq delims=" %%i in (`""%TS%" ip -4 2>nul"`) do set "IP=%%i"
        if not defined IP timeout /t 2 /nobreak >nul
    )
)
if defined IP (
    echo [ok] Tailscale connected: !IP!
) else (
    echo [warn] No Tailscale address yet. The agent will bind to it once Tailscale connects.
)

rem ---- 4. Tether agent ----
netstat -ano | findstr /R /C:"127\.0\.0\.1:%PORT% .*LISTENING" >nul
if not errorlevel 1 (
    echo [ok] Tether agent already running
    goto :done
)

rem Find the Python that has the tether package (skip the Microsoft Store stub).
set "PY="
for /f "delims=" %%p in ('where python 2^>nul') do (
    if not defined PY (
        echo %%p | find /I "WindowsApps" >nul
        if errorlevel 1 (
            "%%p" -c "import tether" >nul 2>&1 && set "PY=%%p"
        )
    )
)
if not defined PY (
    echo Could not find a Python with Tether installed. Run:  pip install -e agent
    goto :fail
)
for %%d in ("%PY%") do set "PYW=%%~dpdpythonw.exe"
if not exist "%PYW%" set "PYW=%PY%"

start "" "%PYW%" -m tether agent
for /L %%n in (1,1,10) do (
    netstat -ano | findstr /R /C:"127\.0\.0\.1:%PORT% .*LISTENING" >nul
    if errorlevel 1 timeout /t 1 /nobreak >nul
)
netstat -ano | findstr /R /C:"127\.0\.0\.1:%PORT% .*LISTENING" >nul
if errorlevel 1 (
    echo Tether agent did not start. See %LOCALAPPDATA%\Tether\agent.log
    goto :fail
)
echo [ok] Tether agent started (tray icon)

:done
echo.
if defined IP echo On the phone, connect to !IP! port %PORT%.
timeout /t 6 >nul
exit /b 0

:fail
echo.
pause
exit /b 1

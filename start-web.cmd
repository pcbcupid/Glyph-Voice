@echo off
setlocal
cd /d "%~dp0"
if exist ".tools\web-venv\Scripts\python.exe" (
  ".tools\web-venv\Scripts\python.exe" tools\start_web.py %*
  goto finished
)
if exist ".tools\local-stt-venv\Scripts\python.exe" (
  ".tools\local-stt-venv\Scripts\python.exe" tools\start_web.py %*
  goto finished
)
where py >nul 2>nul
if errorlevel 1 (
  python tools\start_web.py %*
) else (
  py -3 tools\start_web.py %*
)
:finished
if errorlevel 1 (
  echo.
  echo Glyph Voice could not start. Read the message above.
  pause
)

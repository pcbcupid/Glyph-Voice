@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" %*
set "glyph_exit=%ERRORLEVEL%"
if not "%glyph_exit%"=="0" (
  echo.
  echo Installation failed. Read the error above, fix it, then run this file again.
  if not defined CI pause
)
exit /b %glyph_exit%

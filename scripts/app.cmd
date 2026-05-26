@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0app.ps1" %*
exit /b %ERRORLEVEL%

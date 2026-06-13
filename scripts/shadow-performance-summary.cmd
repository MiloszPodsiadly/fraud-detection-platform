@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0shadow-performance-summary.ps1" %*
exit /b %ERRORLEVEL%

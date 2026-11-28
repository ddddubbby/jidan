@echo off
rem Double-click to start the backend on Windows: runs run-backend.ps1 next to this file
rem (bypasses the script execution policy for this run only).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-backend.ps1"
pause

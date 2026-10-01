@echo off
title Whisprompt Bot
echo Starting Whisprompt key bot...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_bot.ps1"
echo.
echo Bot stopped. Press any key to close.
pause >nul

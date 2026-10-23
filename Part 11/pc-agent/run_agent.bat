@echo off
title JARVIS PC Agent
where python >nul 2>nul
if errorlevel 1 (
  echo Python is not installed. Install it from https://python.org  or use JARVIS-PC-Agent.exe instead.
  pause
  exit /b 1
)
python -m pip install --quiet cryptography
python "%~dp0jarvis_pc_agent.py" %*
pause

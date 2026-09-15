@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0backend"
"../.venv/Scripts/python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000

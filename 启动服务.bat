@echo off
chcp 65001 >nul 2>&1
title 装修采购清单 - 正在启动...
cd /d "%~dp0backend"
echo 正在启动服务...
ping -n 2 127.0.0.1 >nul
start "" http://127.0.0.1:8000
"../.venv/Scripts/python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000

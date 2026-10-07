"""把后端打包成桌面端用的 sidecar exe。

用法（仓库根目录）：
    .venv/Scripts/python backend/packaging/build_server.py

产物：
    src-tauri/binaries/caizhidao-server-<target-triple>.exe

Tauri 的 externalBin 要求文件名带 target triple 后缀，所以最后一步是改个名。
"""

import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
OUT_DIR = ROOT / "src-tauri" / "binaries"

# uvicorn 的子模块是运行时动态 import 的，静态分析扫不到，得显式列出来。
# pydantic-core / httptools 是带二进制的依赖，由 pyinstaller-hooks-contrib 收集。
HIDDEN_IMPORTS = [
    "app.main",
    "uvicorn.logging",
    "uvicorn.loops.auto",
    "uvicorn.loops.asyncio",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.http.httptools_impl",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan.on",
]

# 这些只在开发/测试时用得上，打进安装包纯属浪费体积
EXCLUDES = ["playwright", "pytest", "pytest_asyncio"]


def main() -> int:
    import PyInstaller.__main__

    build_dir = BACKEND / "build" / "pyinstaller"
    dist_dir = BACKEND / "dist" / "pyinstaller"

    PyInstaller.__main__.run([
        str(BACKEND / "desktop.py"),
        "--name", "caizhidao-server",
        "--onefile",
        "--noconfirm",
        "--clean",
        "--distpath", str(dist_dir),
        "--workpath", str(build_dir),
        "--specpath", str(build_dir),
        "--paths", str(BACKEND),
        *[f"--exclude-module={name}" for name in EXCLUDES],
        *[f"--hidden-import={name}" for name in HIDDEN_IMPORTS],
    ])

    suffix = os.environ.get("TAURI_TARGET_TRIPLE", "x86_64-pc-windows-msvc")
    built = dist_dir / ("caizhidao-server.exe" if os.name == "nt" else "caizhidao-server")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    target = OUT_DIR / f"caizhidao-server-{suffix}.exe"
    shutil.copy2(built, target)
    print(f"已生成 {target}（{target.stat().st_size // 1024 // 1024} MB）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

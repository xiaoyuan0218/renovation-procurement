"""把后端打包成桌面端用的 sidecar exe。

用法（仓库根目录）：
    .venv/Scripts/python backend/packaging/build_server.py

产物：
    src-tauri/binaries/caizhidao-server-<target-triple>.exe

Tauri 的 externalBin 要求文件名带 target triple 后缀，所以最后一步是改个名。
"""

import json
import os
import shutil
import sys
from pathlib import Path


def _utf8_output() -> None:
    """让标准输出能打印中文。

    Windows 上 Python 按系统代码页编码 stdout：英文系统是 cp1252，打印中文
    直接 UnicodeEncodeError（CI 的 runner 就是英文系统，本地中文系统看不出来）。
    """
    for stream in (sys.stdout, sys.stderr):
        if stream is not None and hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


_utf8_output()

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

    # 版本与构建时间固化进 exe：「关于」页显示它、检查更新拿它比对。sidecar
    # 由 Tauri 拉起时只传固定几个环境变量，运行时读不到版本号，只能打包时
    # 塞进去（desktop.py 启动时从 _MEIPASS 里读）。没传就落 dev，检查更新
    # 会一直说「本地是开发版」。
    version = os.environ.get("RENOVATION_VERSION") or (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    built_at = os.environ.get("RENOVATION_BUILT_AT") or ""
    version_file = build_dir / "version.json"
    version_file.parent.mkdir(parents=True, exist_ok=True)
    version_file.write_text(
        json.dumps({"version": version, "built_at": built_at}, ensure_ascii=False),
        encoding="utf-8")

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
        f"--add-data={version_file};.",
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

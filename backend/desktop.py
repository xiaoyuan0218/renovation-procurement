"""桌面端启动入口（Windows 桌面版专用）。

和 `main.py` 的区别：`main.py` 是给「服务器部署」用的 —— 由 uvicorn 命令行
拉起、监听 0.0.0.0、数据放在仓库的 data/ 目录。这里是给桌面版用的：

  - 数据放用户目录（%APPDATA%\\采知道）。安装目录可能只读，PyInstaller 解压
    出来的 _MEIPASS 更是每次启动都换路径，数据库、签名密钥、迁移备份写在那里
    要么直接失败、要么下次启动就找不到了。
  - 本机免登录（见 auth.LOCAL_NO_AUTH），打开就是自己的数据。
  - **默认只监听 127.0.0.1**：桌面端和手机端一样是「客户端」角色 —— 数据在
    本机、需要时同步到自己的服务器，不把自己暴露给局域网。真想让同局域网的
    设备连它，把 RENOVATION_HOST 设成 0.0.0.0（那时局域网请求需要账号密码，
    本机仍免登录）。
  - 端口被占用时自动往后找一个，避免和电脑上已有的服务（比如 Docker 里那个）
    打架。
  - 起来之后把实际端口写进数据目录的 `port` 文件。外壳程序（Tauri）读它决定
    窗口该指向哪 —— 比读 stdout 可靠：Windows 上打包成无控制台的 exe 时
    stdout 是空的。

用法：`python desktop.py`（在 backend/ 目录下），或打包成 exe 后由 Tauri 拉起。
环境变量：RENOVATION_DATA_DIR / RENOVATION_HOST / RENOVATION_PORT / RENOVATION_DIST
"""

import os
import socket
import sys
from pathlib import Path


def _utf8_output() -> None:
    """让标准输出能打印中文。

    Windows 上 Python 按系统代码页编码 stdout：英文系统是 cp1252，下面那几行
    中文启动日志会直接 UnicodeEncodeError、服务起不来。英文版 Windows 和 CI
    的 runner 都会踩，中文系统看不出来。
    """
    for stream in (sys.stdout, sys.stderr):
        if stream is not None and hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


_utf8_output()


def _default_data_dir() -> Path:
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    return Path(base) / "采知道"


def _bundled_dist() -> Path | None:
    """打包后的前端产物在哪。

    两种放法都认：PyInstaller 解出来的 _MEIPASS 里，或者 exe 同级的
    frontend_dist 目录（Tauri 把 dist 作为 resource 放在那里）。
    """
    candidates = []
    if getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).resolve().parent
        meipass = getattr(sys, "_MEIPASS", None)
        if meipass:
            candidates.append(Path(meipass) / "frontend_dist")
        candidates.append(exe_dir / "frontend_dist")
    else:
        candidates.append(Path(__file__).resolve().parent.parent / "frontend" / "dist")
    for candidate in candidates:
        if candidate.is_dir():
            return candidate
    return None


def _prepare_env() -> Path:
    """把环境变量摆好。**必须在 import app 之前调用** —— db.py 和 auth.py 在
    模块加载时就读这些值，晚一步设就白设了。"""
    data_dir = Path(os.environ.get("RENOVATION_DATA_DIR") or _default_data_dir())
    data_dir.mkdir(parents=True, exist_ok=True)
    os.environ["RENOVATION_DATA_DIR"] = str(data_dir)
    os.environ.setdefault("RENOVATION_LOCAL_NO_AUTH", "1")
    if not os.environ.get("RENOVATION_DIST"):
        dist = _bundled_dist()
        if dist:
            os.environ["RENOVATION_DIST"] = str(dist)
    return data_dir


def _pick_port(preferred: int, host: str) -> int:
    """从 preferred 往后找第一个能绑上的端口。

    在 Windows 上刻意不设 SO_REUSEADDR：它的语义和 Linux 不同，允许绑到已被
    占用的端口上，那样"探测"就永远成功、冲突反而漏过去了。不设时绑占用端口
    会直接报错，正是我们要的判断。
    """
    for port in range(preferred, preferred + 20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind((host, port))
                return port
            except OSError:
                continue
    raise SystemExit(f"从 {preferred} 起连续 20 个端口都被占用了，请先关掉占用它们的程序")


def main() -> None:
    data_dir = _prepare_env()
    # 默认只服务本机：桌面端是客户端，不该一装好就把端口开给整个局域网。
    # 想给同局域网的设备连，把 RENOVATION_HOST 设成 0.0.0.0。
    host = os.environ.get("RENOVATION_HOST", "127.0.0.1")
    try:
        preferred = int(os.environ.get("RENOVATION_PORT", "") or 8000)
    except ValueError:
        preferred = 8000
    port = _pick_port(preferred, host)

    # 先写端口再起服务：外壳程序读到就能去连，连不上会自己重试，不用等信号
    (data_dir / "port").write_text(str(port), encoding="utf-8")

    import uvicorn

    from app.main import app

    print(f"[桌面端] 数据目录：{data_dir}", flush=True)
    print(f"[桌面端] 前端产物：{os.environ.get('RENOVATION_DIST', '(未设置)')}", flush=True)
    print(f"[桌面端] 监听 http://{host}:{port}", flush=True)
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()

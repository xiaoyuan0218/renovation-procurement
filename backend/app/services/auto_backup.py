"""服务端每日自动快照（轮转保留）。

整库备份原本只有手动「下载」与「恢复」两个入口，坏盘、误恢复、清空回收站
之后能不能救回来，全看用户记不记得每天手动下 .db。这里在服务端补一道兜底：
每天第一次被用到时把库快照进数据目录（renovation.db.auto-<日期>），
保留最近 KEEP 份，更旧的自动删掉。docker 部署与桌面版 sidecar 用的是同一份
后端代码，桌面版的数据目录也会照此拿到每日快照。

快照走 VACUUM INTO（与手动下载同一通道）：单个事务写出完整副本，不会拷到
写了一半的中间状态。先写临时名再原子改名（os.replace），写一半崩掉也不会
留下一个看着像备份、其实已损坏的文件。

触发时机有两处（见 main.py）：启动时一次，之后靠请求惰性触发 —— 服务长期
不重启也没有定时线程，每个请求进来时比一下日期，跨天后的第一个请求顺手把
快照做了。没人访问的日子里不会产生新数据，不备也不亏。
"""

import logging
import os
import sqlite3
import threading
from datetime import date
from pathlib import Path

from ..db import DATA_DIR, DB_PATH

log = logging.getLogger(__name__)

# 家用库几百 KB，每天一份留一周绰绰有余；真要翻旧账，手动下载的那份还在
KEEP = 7
PREFIX = "renovation.db.auto-"

_lock = threading.Lock()
# 进程内记住"今天备过了"，让高频请求路径只做一次字符串比较；
# 磁盘上的快照文件才是权威判断（服务重启后照样正确）
_last_date = None


def _today() -> str:
    return date.today().isoformat()


def _snapshot(target: Path) -> None:
    """把当前库一致性快照到 target（VACUUM INTO），已存在则覆盖不了 —— 调用方保证不存在。"""
    tmp = Path(f"{target}.{os.getpid()}.tmp")
    try:
        tmp.unlink()
    except FileNotFoundError:
        pass
    conn = sqlite3.connect(DB_PATH, timeout=30)
    try:
        conn.execute("VACUUM INTO ?", (str(tmp),))
    finally:
        conn.close()
    os.replace(tmp, target)


def _rotate() -> None:
    snaps = sorted(Path(DATA_DIR).glob(PREFIX + "*"))
    for old in snaps[:-KEEP]:
        try:
            old.unlink()
        except OSError as e:
            # 删不掉（被占用、只读卷）就先留着，下次轮转再试，不影响今天的快照
            log.warning("自动备份轮转删除失败 %s：%s", old, e)


def needs_run() -> bool:
    """今天还没触发过快照检查吗。给请求路径当快闸：绝大多数时候只比一个字符串。"""
    return _last_date != _today()


def run_once(today: str | None = None) -> str:
    """确保今天有一份快照，返回快照文件路径。

    本进程今天已经备过就复用（同一天反复覆盖会把上午的状态弄丢）；进程刚
    启动的当天首次触发则重新快照 —— 哪怕磁盘上已有当天的同名文件（上次进程
    留下的）也盖掉重做，保证它一定是本进程用 VACUUM 做出的一致性副本，绝不
    把外部拷来、半截损坏的文件当成今天的备份。
    """
    global _last_date
    today = today or _today()
    target = Path(DB_PATH).with_name(f"{PREFIX}{today}")
    if _last_date == today and target.exists():
        return str(target)
    with _lock:
        if _last_date != today or not target.exists():
            _snapshot(target)
            _rotate()
            log.info("已生成每日自动快照 %s", target)
    _last_date = today
    return str(target)

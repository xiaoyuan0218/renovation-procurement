"""整库备份的下载与恢复。

单管理员、单文件的场景，备份就是 data/renovation.db 本身。这里不走 ORM：
把数据库文件整个带走、整个换回来，账号、清单结构一并保留，换台机器立刻能用。

恢复用 SQLite 的 backup API 把上传的内容灌进现有文件，而不是删掉文件再改名 ——
Windows 上文件被占着删不掉，正在跑的连接也会变成指向已被删掉的旧文件。
"""

import os
import sqlite3
import tempfile
from datetime import datetime
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, File, HTTPException, Response, UploadFile

from .. import migrations
from ..db import DB_PATH, engine
from ..seed import init_db

router = APIRouter(prefix="/api/backup", tags=["backup"])

# 家用库几百 KB，512M 已经离谱地宽松，只是别让超大文件把内存吃光
MAX_BACKUP_BYTES = 512 * 1024 * 1024
SQLITE_MAGIC = b"SQLite format 3\x00"


def _temp_path(path: str) -> Path:
    """把路径收窄成「系统临时目录 + 文件名」，返回 Path。

    本模块的临时文件都是自己用 tempfile.mkstemp 建的，本来就没有外部输入；
    这里取 basename 重新拼一遍，路径里不可能出现 ../，也不可能指到临时目录
    之外 —— 纵深防御，顺便让"这个路径从哪来"在代码里一眼可见。
    """
    return Path(tempfile.gettempdir()) / os.path.basename(path)


def _count(conn: sqlite3.Connection, table: str) -> int:
    """数一张表的行数。

    表名不能参数化，所以这里按名字取**调用点写死的整句语句**，不做拼接。
    调用方只会传下面这五个字面量，别的一律抛错。
    """
    if table == "items":
        sql = "SELECT COUNT(*) FROM items"
    elif table == "rooms":
        sql = "SELECT COUNT(*) FROM rooms"
    elif table == "categories":
        sql = "SELECT COUNT(*) FROM categories"
    elif table == "lists":
        sql = "SELECT COUNT(*) FROM lists"
    elif table == "users":
        sql = "SELECT COUNT(*) FROM users"
    else:
        raise ValueError(f"不支持统计的表：{table}")
    try:
        return conn.execute(sql).fetchone()[0]
    except sqlite3.Error:
        return 0


def _validate(path: str) -> dict:
    """确认这确实是本工具能用的备份，顺便读出可展示的统计。

    路径先过 _temp_path：本模块只会传自己用 mkstemp 建的文件，这里
    再收窄一次，保证读的确实是临时目录里的那一个。
    """
    safe = _temp_path(path)
    if safe.read_bytes()[:16] != SQLITE_MAGIC:
        raise HTTPException(400, "这不是 SQLite 数据库文件，请选本工具下载的备份（.db）")
    conn = sqlite3.connect(safe)
    try:
        verdict = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if verdict != "ok":
            raise HTTPException(400, f"备份文件已损坏（{verdict}），请换一个再试")
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        if "items" not in tables:
            raise HTTPException(400, "这个文件里没有清单数据表，不像是本工具的备份")
        return {"items": _count(conn, "items"), "rooms": _count(conn, "rooms"),
                "categories": _count(conn, "categories"),
                "lists": _count(conn, "lists") if "lists" in tables else 0,
                "users": _count(conn, "users") if "users" in tables else 0}
    finally:
        conn.close()


@router.get("")
def download_backup():
    """下载当前数据库的一致性快照。

    用 VACUUM INTO 而不是直接读文件：它在单个事务里写出完整副本，不会拷到
    写了一半的中间状态，顺带把碎片整理掉。
    """
    fd, tmp = tempfile.mkstemp(prefix="renovation-backup-", suffix=".db")
    os.close(fd)
    tmp = _temp_path(tmp)
    os.unlink(tmp)  # VACUUM INTO 要求目标文件不存在
    try:
        conn = sqlite3.connect(DB_PATH, timeout=30)
        try:
            conn.execute("VACUUM INTO ?", (str(tmp),))
        finally:
            conn.close()
        data = tmp.read_bytes()
    finally:
        if os.path.exists(tmp):
            try:
                os.unlink(tmp)
            except OSError:
                pass
    name = f"清单备份_{datetime.now():%Y%m%d_%H%M%S}.db"
    return Response(
        content=data,
        media_type="application/octet-stream",
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(name)}"},
    )


@router.post("/restore")
async def restore_backup(file: UploadFile = File(...)):
    """用上传的备份整体替换当前数据。

    替换前会把现状另存一份，所以传错文件也能救回来。备份可能来自更老的版本，
    灌回去之后再走一遍 init_db：补列、多清单迁移都会自动完成。
    """
    data = await file.read()
    if not data:
        raise HTTPException(400, "文件是空的")
    if len(data) > MAX_BACKUP_BYTES:
        raise HTTPException(400, "文件太大，本工具的备份只有几百 KB")

    fd, tmp = tempfile.mkstemp(prefix="renovation-restore-", suffix=".db")
    os.close(fd)
    tmp = _temp_path(tmp)
    try:
        tmp.write_bytes(data)
        incoming = _validate(str(tmp))
        previous = migrations.backup_db_file()
        # 先断开池里的空闲连接，再整个灌进去；backup API 不经过 ORM，
        # 也不触发外键检查，灌完的库由 init_db 再确认一遍结构
        engine.dispose()
        dst = sqlite3.connect(DB_PATH, timeout=30)
        src = sqlite3.connect(tmp, timeout=30)
        try:
            src.backup(dst)
        finally:
            dst.close()
            src.close()
    finally:
        try:
            os.unlink(tmp)
        except OSError:
            pass

    init_db()
    engine.dispose()

    conn = sqlite3.connect(DB_PATH, timeout=30)
    try:
        current = {"items": _count(conn, "items"), "rooms": _count(conn, "rooms"),
                   "categories": _count(conn, "categories"),
                   "lists": _count(conn, "lists")}
    finally:
        conn.close()
    return {"ok": True, "previous_backup": previous, "incoming": incoming,
            "current": current}

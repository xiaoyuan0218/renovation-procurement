"""每日自动快照：当天生成一份、同天复用不重写、跨天出新份、超量轮转。"""

import sqlite3
from pathlib import Path

from app.db import DATA_DIR
from app.seed import init_db
from app.services import auto_backup

# 这个文件不拉起 app.main，得自己把库结构建出来（幂等），快照里才有表可查
init_db()

PREFIX = auto_backup.PREFIX


def _clean_auto_files():
    for p in Path(DATA_DIR).glob(PREFIX + "*"):
        p.unlink()


def teardown_function():
    # 清掉临时数据目录里的快照，并把"今天已备过"的进程内记忆复位，
    # 不然测试之间会互相当成"同一天"走上复用路径
    _clean_auto_files()
    auto_backup._last_date = None


def test_snapshot_is_a_valid_sqlite_file():
    _clean_auto_files()
    path = auto_backup.run_once(today="2026-01-01")
    assert Path(path).name == f"{PREFIX}2026-01-01"
    assert Path(path).parent == Path(DATA_DIR)
    assert Path(path).read_bytes()[:16] == b"SQLite format 3\x00"
    # 快照能当库打开，结构完整（items 表在，查得动）
    conn = sqlite3.connect(path)
    try:
        assert conn.execute("SELECT COUNT(*) FROM items").fetchone()[0] >= 0
    finally:
        conn.close()


def test_same_day_reuses_snapshot():
    _clean_auto_files()
    first = Path(auto_backup.run_once(today="2026-01-01"))
    mtime = first.stat().st_mtime_ns
    # 同一天再触发：复用已有快照，不重写（重写会把上午的状态弄丢）
    assert auto_backup.run_once(today="2026-01-01") == str(first)
    assert first.stat().st_mtime_ns == mtime
    # 跨天：生成新的一份
    second = Path(auto_backup.run_once(today="2026-01-02"))
    assert second.name == f"{PREFIX}2026-01-02"
    assert first.exists()


def test_rotation_keeps_recent():
    _clean_auto_files()
    for i in range(1, 10):
        Path(DATA_DIR, f"{PREFIX}2025-12-{i:02d}").write_bytes(b"stale")
    auto_backup.run_once(today="2026-01-01")
    snaps = sorted(p.name for p in Path(DATA_DIR).glob(PREFIX + "*"))
    assert len(snaps) == auto_backup.KEEP
    assert f"{PREFIX}2026-01-01" in snaps
    # 最旧的被清掉，最近 KEEP-1 份历史 + 今天这份留下
    assert f"{PREFIX}2025-12-01" not in snaps
    assert f"{PREFIX}2025-12-{9:02d}" in snaps


def test_corrupt_leftover_target_is_replaced():
    """残留的同名目标（比如外部拷了一半的文件）会被重新快照盖掉。"""
    _clean_auto_files()
    stale = Path(DATA_DIR, f"{PREFIX}2026-01-01")
    stale.write_bytes(b"not a database")
    path = Path(auto_backup.run_once(today="2026-01-01"))
    assert path.read_bytes()[:16] == b"SQLite format 3\x00"

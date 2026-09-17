"""数据库迁移：把「整个库只有一份装修采购清单」的老库升成多清单结构。

老库升级只做三件事，别的一律不动：

  1. 建默认清单（老数据全部归它）；
  2. 给 items / rooms / categories 补 list_id 列并回填；
  3. 重建 categories —— 原来 name 是全局唯一，多清单后要放成 (list_id, name) 唯一。

为什么要重建 categories 而不是删唯一索引：SQLite 不能 DROP CONSTRAINT，
而这个唯一约束是内联在建表语句里的，只能整表重建（保留原 id，items.category_id
指向的仍是同一行）。重建期间必须关掉外键检查 —— items 引用 categories，
DROP TABLE 会当场报违规。

安全措施，缺一不可：
  - 动手前把整个 .db 复制成 .bak-<时间戳>，出问题直接换回来；
  - 全程一个事务（BEGIN IMMEDIATE，先拿写锁），任何一步失败整体回滚；
  - 事务内做前后自检：每张表逐行比对，条数、数量、金额必须一字不差；
  - 迁移后断言没有任何 list_id 为空的残留；
  - 按 items 有没有 list_id 判断是否已迁移，重复启动不会重复执行。
"""

import shutil
import sqlite3
import time
from datetime import datetime

from .db import DB_PATH

DEFAULT_LIST_NAME = "采购清单"

# 需要挂到清单名下的表。purchase_records / allocations 不直接挂：
# 它们只通过 item_id 关联，物料属于哪份清单就跟着到哪份清单。
_TABLES_WITH_LIST = ("items", "rooms", "categories")


def _columns(cur, table):
    try:
        return {row[1] for row in cur.execute(f"PRAGMA table_info({table})")}
    except sqlite3.OperationalError:
        return set()


def _tables(cur):
    return {r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}


def _unique_indexes(cur, table):
    """返回该表所有唯一约束/索引的列组合，例如 [["list_id", "name"]]。"""
    out = []
    for row in cur.execute(f"PRAGMA index_list({table})"):
        if row[2]:  # unique 标志
            out.append([r[2] for r in cur.execute(f"PRAGMA index_info({row[1]})")])
    return out


def needs_migration(cur) -> bool:
    """库里已经有条目、结构却还是单清单的老样子 —— 才需要迁移。

    全新的空库不算：那是 create_all 直接按新结构建表的事，没有数据要搬。
    """
    tables = _tables(cur)
    if "items" not in tables:
        return False
    if "lists" not in tables:
        return True
    for table in _TABLES_WITH_LIST:
        if table in tables and "list_id" not in _columns(cur, table):
            return True
    return False


def backup_if_needed():
    """要迁移就先按原样备份，返回备份路径；不需要迁移返回 None。

    必须在 create_all 之前调用 —— 备份里得是升级前原封不动的库
    （连空的 lists 表都不该多出来），回滚时直接换回去就行。
    """
    conn = sqlite3.connect(DB_PATH, isolation_level=None, timeout=30)
    try:
        if not needs_migration(conn.cursor()):
            return None
    finally:
        conn.close()
    return backup_db_file()


def _snapshot(cur):
    """迁移前后要比对的东西。只取确定存在的列，老库缺列也不影响。"""
    wanted = (
        ("items", ("id", "name", "qty_total", "price", "discount_price")),
        ("rooms", ("id", "name", "sort")),
        ("categories", ("id", "name", "sort")),
        ("allocations", ("id", "item_id", "room_id", "qty", "price_override")),
        ("purchase_records", ("id", "item_id", "qty", "amount", "date")),
    )
    snap = {}
    for table, cols in wanted:
        have = _columns(cur, table)
        if not have:
            continue
        sel = ", ".join(c for c in cols if c in have)
        snap[table] = cur.execute(
            f"SELECT {sel} FROM {table} ORDER BY id").fetchall()
    return snap


def backup_db_file() -> str:
    """把库文件整份复制走，返回备份路径。传错也不怕：换回来就行。

    迁移和「恢复备份」都先用它把现状存一份。"""
    stamp = time.strftime("%Y%m%d-%H%M%S")
    for n in range(100):
        suffix = "" if n == 0 else f"-{n}"
        path = f"{DB_PATH}.bak-{stamp}{suffix}"
        try:
            # 独占创建：同一秒内重复迁移也不覆盖已有备份
            with open(path, "xb"):
                pass
        except FileExistsError:
            continue
        shutil.copy2(DB_PATH, path)
        return path
    raise RuntimeError("备份文件名连续冲突，放弃迁移")


def _ensure_default_list(cur, name: str) -> int:
    row = cur.execute("SELECT id FROM lists ORDER BY sort, id LIMIT 1").fetchone()
    if row:
        return row[0]
    cur.execute(
        "INSERT INTO lists (name, note, sort, created_at) VALUES (?, '', 0, ?)",
        (name, datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")))
    return cur.lastrowid


def _rebuild_categories(cur, default_id: int) -> None:
    """按 (list_id, name) 唯一重建 categories，保留原 id。"""
    cur.execute("""
        CREATE TABLE categories_new (
            id INTEGER NOT NULL,
            list_id INTEGER,
            name VARCHAR(50) NOT NULL,
            sort INTEGER,
            PRIMARY KEY (id),
            CONSTRAINT uq_category_list_name UNIQUE (list_id, name),
            FOREIGN KEY(list_id) REFERENCES lists (id) ON DELETE CASCADE
        )
    """)
    cur.execute("INSERT INTO categories_new (id, list_id, name, sort) "
                "SELECT id, ?, name, sort FROM categories", (default_id,))
    cur.execute("DROP TABLE categories")
    cur.execute("ALTER TABLE categories_new RENAME TO categories")


def _apply(cur, default_list_name: str) -> dict:
    if "lists" not in _tables(cur):
        raise RuntimeError("lists 表不存在：init_db 应先在 create_all 里建好它")
    default_id = _ensure_default_list(cur, default_list_name)

    added = []
    for table in _TABLES_WITH_LIST:
        if "list_id" not in _columns(cur, table):
            cur.execute(f"ALTER TABLE {table} ADD COLUMN list_id INTEGER "
                        f"REFERENCES lists(id) ON DELETE CASCADE")
            added.append(table)
        cur.execute(f"UPDATE {table} SET list_id = ? WHERE list_id IS NULL",
                    (default_id,))
        cur.execute(f"CREATE INDEX IF NOT EXISTS ix_{table}_list_id "
                    f"ON {table} (list_id)")

    rebuilt = False
    if ["name"] in _unique_indexes(cur, "categories"):
        _rebuild_categories(cur, default_id)
        rebuilt = True
        cur.execute("CREATE INDEX IF NOT EXISTS ix_categories_list_id "
                    "ON categories (list_id)")

    return {"default_list_id": default_id, "default_list_name": default_list_name,
            "columns_added": added, "categories_rebuilt": rebuilt}


def _verify(cur, before: dict) -> None:
    after = _snapshot(cur)
    problems = []
    for key, rows in before.items():
        now = after.get(key)
        if now is None:
            problems.append(f"{key} 表不见了")
        elif len(now) != len(rows):
            problems.append(f"{key} 行数变了：{len(rows)} → {len(now)}")
        elif now != rows:
            idx = next(i for i, (a, b) in enumerate(zip(rows, now)) if a != b)
            problems.append(f"{key} 第 {idx + 1} 行内容变了：{rows[idx]!r} → {now[idx]!r}")
    for table in _TABLES_WITH_LIST:
        if _columns(cur, table):
            left = cur.execute(
                f"SELECT COUNT(*) FROM {table} WHERE list_id IS NULL").fetchone()[0]
            if left:
                problems.append(f"{table} 还有 {left} 行没归到清单")
    if problems:
        raise RuntimeError("迁移自检未通过：" + "；".join(problems))


def migrate_to_multi_list(default_list_name: str = DEFAULT_LIST_NAME,
                          backup_path: str = None):
    """需要就迁移，返回报告；库已经是对的新结构时返回 None。

    backup_path 是调用方（init_db）提前做好的备份：备份必须早于
    create_all，否则备份里会多出一张空的 lists 表。没传就自己补一份。

    抛异常时数据已经整体回滚，库仍停在迁移前的样子，备份文件也还在。
    """
    conn = sqlite3.connect(DB_PATH, isolation_level=None, timeout=30)
    try:
        cur = conn.cursor()
        if not needs_migration(cur):
            return None
        before = _snapshot(cur)
        backup = backup_path or backup_db_file()
        # 重建 categories 要 DROP 掉被 items 引用的旧表，外键检查必须关；
        # 而 PRAGMA foreign_keys 在事务内是空操作，只能放在 BEGIN 之前。
        # 每条连接各自持有这个开关，这里是刚开的独立连接，不影响应用连接池。
        cur.execute("PRAGMA foreign_keys=OFF")
        cur.execute("BEGIN IMMEDIATE")
        try:
            report = _apply(cur, default_list_name)
            _verify(cur, before)
            violations = cur.execute("PRAGMA foreign_key_check").fetchall()
            if violations:
                raise RuntimeError(f"迁移后有外键违规：{violations[:5]}")
            cur.execute("COMMIT")
        except BaseException:
            cur.execute("ROLLBACK")
            raise
        report["backup"] = backup
        report["tables"] = {k: len(v) for k, v in before.items()}
        # 打到 stdout：容器里 docker compose logs 能看到升级发生过、备份在哪，
        # 而不是某天发现数据"变新了"却不知道什么时候变的
        counts = report["tables"]
        print(f"[升级] 老库已升级为多清单：默认清单 id={report['default_list_id']}"
              f"（物料 {counts.get('items', 0)} 条、分组 {counts.get('rooms', 0)} 个、"
              f"分类 {counts.get('categories', 0)} 个已归入它）；"
              f"升级前的库备份在 {backup}")
        return report
    finally:
        conn.close()

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
from datetime import datetime, timezone


def utcnow():
    """UTC 当前时刻（迁移标记用，与全端时间戳同一把尺子）。"""
    return datetime.now(timezone.utc).replace(tzinfo=None)
from datetime import datetime

from .db import DB_PATH
from .services import codes

DEFAULT_LIST_NAME = "采购清单"

# 需要挂到清单名下的表。purchase_records / allocations 不直接挂：
# 它们只通过 item_id 关联，物料属于哪份清单就跟着到哪份清单。
_TABLES_WITH_LIST = ("items", "rooms", "categories")



def _columns(cur, table):
    """表的列名集合。

    用 pragma_table_info 表值函数而不是 `PRAGMA table_info(x)`：前者能把表名
    当参数绑定（`?`），表名不进 SQL 文本，静态扫描器也就没有可拼的注入面。
    两者返回的列完全一致。
    """
    try:
        return {row[1] for row in cur.execute(
            "SELECT * FROM pragma_table_info(?)", (table,))}
    except sqlite3.OperationalError:
        return set()


def _tables(cur):
    return {r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}


def _unique_indexes(cur, table):
    """返回该表所有唯一约束/索引的列组合，例如 [["list_id", "name"]]。"""
    out = []
    for row in cur.execute("SELECT * FROM pragma_index_list(?)", (table,)):
        if row[2]:  # unique 标志
            out.append([r[2] for r in cur.execute(
                "SELECT * FROM pragma_index_info(?)", (row[1],))])
    return out


def needs_migration(cur) -> bool:
    """库里已经有条目、结构却还是老样子 —— 才需要迁移。

    全新的空库不算：那是 create_all 直接按新结构建表的事，没有数据要搬。

    两种老结构都算：
      - 单清单时代（items 没有 list_id）；
      - 多清单但清单名还是全局唯一（lists.name 上有唯一约束）—— 放开重名
        得整表重建，这一步同样要在有数据之前先备份。
    """
    tables = _tables(cur)
    if "items" not in tables:
        return False
    if "lists" not in tables:
        return True
    for table in _TABLES_WITH_LIST:
        if table in tables and "list_id" not in _columns(cur, table):
            return True
    return ["name"] in _unique_indexes(cur, "lists")


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
    """迁移前后要比对的东西。只取确定存在的列，老库缺列也不影响。

    lists 也在里面：重建这张表去掉 name 唯一约束时，要能证明每一份清单
    （连同 id、名字、备注、顺序）都原样搬过去了，一条没丢。
    不含 code —— 撞号的清单会被刻意重新发码（见 _dedupe_list_codes），
    编号的完整性由 _verify 里单独一条断言保证。
    """
    wanted = (
        ("lists", ("id", "name", "note", "sort")),
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
        snap[table] = _snapshot_table(cur, table, have)
    return snap


def _snapshot_table(cur, table: str, have: set):
    """按表取全部数据，供迁移前后逐行比对。

    表名只会是 _snapshot 里那六个字面量之一，所以这里逐表写死完整语句 ——
    老库可能缺列（很老的库没有 code / 时间戳），缺列时整条语句换成不取那几列
    的那一份，不做字符串拼接。
    """
    if table == "lists":
        return cur.execute(
            "SELECT id, name, note, sort FROM lists ORDER BY id").fetchall()
    if table == "items":
        if {"qty_total", "price", "discount_price"} <= have:
            return cur.execute(
                "SELECT id, name, qty_total, price, discount_price "
                "FROM items ORDER BY id").fetchall()
        return cur.execute("SELECT id, name FROM items ORDER BY id").fetchall()
    if table == "rooms":
        return cur.execute(
            "SELECT id, name, sort FROM rooms ORDER BY id").fetchall()
    if table == "categories":
        return cur.execute(
            "SELECT id, name, sort FROM categories ORDER BY id").fetchall()
    if table == "allocations":
        return cur.execute(
            "SELECT id, item_id, room_id, qty, price_override "
            "FROM allocations ORDER BY id").fetchall()
    if table == "purchase_records":
        return cur.execute(
            "SELECT id, item_id, qty, amount, date "
            "FROM purchase_records ORDER BY id").fetchall()
    raise RuntimeError(f"快照遇到未知的表：{table}")
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
        (name, utcnow().strftime("%Y-%m-%d %H:%M:%S.%f")))
    return cur.lastrowid


def _rebuild_categories(cur, default_id: int) -> None:
    """按 (list_id, name) 唯一重建 categories，保留原 id 与创建/修改时间。

    老库有的带时间戳列、有的没有，两种语句各写一份完整字面量（不按列名拼串）——
    运行时只是二选一，SQL 文本本身没有可变的拼接面。
    """
    cols = _columns(cur, "categories")
    has_ts = "created_at" in cols and "updated_at" in cols
    if has_ts:
        cur.execute("""
            CREATE TABLE categories_new (
                id INTEGER NOT NULL,
                list_id INTEGER,
                name VARCHAR(50) NOT NULL,
                sort INTEGER,
                created_at DATETIME, updated_at DATETIME,
                PRIMARY KEY (id),
                CONSTRAINT uq_category_list_name UNIQUE (list_id, name),
                FOREIGN KEY(list_id) REFERENCES lists (id) ON DELETE CASCADE
            )
        """)
        cur.execute(
            "INSERT INTO categories_new "
            "(id, list_id, name, sort, created_at, updated_at) "
            "SELECT id, ?, name, sort, created_at, updated_at FROM categories",
            (default_id,),
        )
    else:
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
        cur.execute(
            "INSERT INTO categories_new (id, list_id, name, sort) "
            "SELECT id, ?, name, sort FROM categories",
            (default_id,),
        )
    cur.execute("DROP TABLE categories")
    cur.execute("ALTER TABLE categories_new RENAME TO categories")


def _rebuild_lists_drop_name_unique(cur) -> bool:
    """把 lists.name 的内联唯一约束去掉 —— 清单名允许重复，编号才是身份。

    与 categories 同理：SQLite 删不掉内联约束，只能整表重建。lists 被
    items/rooms/categories/extra_expenses 的 list_id 引用，DROP 之前必须
    关外键检查（调用方已经在事务外关了）。

    重建时把 code 的唯一性一并补上：以前没有这约束，两份清单可能撞号
    （老库回填是各自生成的，理论上不会，但导入过手机数据的库说不准），
    所以先扫出重复的把后者重新发码，再建部分唯一索引。

    返回是否真的重建过。
    """
    if ["name"] not in _unique_indexes(cur, "lists"):
        return False

    cols = _columns(cur, "lists")
    # 列按老库实际有的挑，缺的用默认值补（比如很老的库还没有 code）。
    # 四种组合各写一份完整语句，不按列名拼串 —— SQL 文本全是字面量。
    has_code = "code" in cols
    has_ts = "created_at" in cols and "updated_at" in cols

    if has_ts and has_code:
        cur.execute("""
            CREATE TABLE lists_new (
                id INTEGER NOT NULL,
                name VARCHAR(50) NOT NULL,
                note VARCHAR(200),
                sort INTEGER,
                created_at DATETIME, updated_at DATETIME,
                code VARCHAR(12),
                PRIMARY KEY (id)
            )
        """)
        cur.execute(
            "INSERT INTO lists_new "
            "(id, name, note, sort, created_at, updated_at, code) "
            "SELECT id, name, note, sort, created_at, updated_at, code FROM lists"
        )
    elif has_ts:
        cur.execute("""
            CREATE TABLE lists_new (
                id INTEGER NOT NULL,
                name VARCHAR(50) NOT NULL,
                note VARCHAR(200),
                sort INTEGER,
                created_at DATETIME, updated_at DATETIME,
                PRIMARY KEY (id)
            )
        """)
        cur.execute(
            "INSERT INTO lists_new (id, name, note, sort, created_at, updated_at) "
            "SELECT id, name, note, sort, created_at, updated_at FROM lists"
        )
    elif has_code:
        cur.execute("""
            CREATE TABLE lists_new (
                id INTEGER NOT NULL,
                name VARCHAR(50) NOT NULL,
                note VARCHAR(200),
                sort INTEGER,
                code VARCHAR(12),
                PRIMARY KEY (id)
            )
        """)
        cur.execute(
            "INSERT INTO lists_new (id, name, note, sort, code) "
            "SELECT id, name, note, sort, code FROM lists"
        )
    else:
        cur.execute("""
            CREATE TABLE lists_new (
                id INTEGER NOT NULL,
                name VARCHAR(50) NOT NULL,
                note VARCHAR(200),
                sort INTEGER,
                PRIMARY KEY (id)
            )
        """)
        cur.execute(
            "INSERT INTO lists_new (id, name, note, sort) "
            "SELECT id, name, note, sort FROM lists"
        )
    cur.execute("DROP TABLE lists")
    cur.execute("ALTER TABLE lists_new RENAME TO lists")

    # code 的普通索引（SQLAlchemy 的 index=True 建的）
    cur.execute("CREATE INDEX IF NOT EXISTS ix_lists_code ON lists (code)")
    return True


def _dedupe_list_codes(cur) -> int:
    """把撞号的清单重新发一个号，返回改了几份。

    编号是两端识别清单的依据，必须唯一。老库里理论上不会有重复（回填时
    逐个生成），但导入过手机数据的库说不准，建唯一索引前先扫一遍更稳。
    """
    taken = set()
    fixed = 0
    rows = cur.execute("SELECT id, code FROM lists ORDER BY id").fetchall()
    for list_id, code in rows:
        if not code:
            continue
        if code not in taken:
            taken.add(code)
            continue
        # 撞号：换一个没被占的
        for _ in range(50):
            candidate = codes.new_code()
            if candidate not in taken:
                taken.add(candidate)
                cur.execute("UPDATE lists SET code = ? WHERE id = ?",
                            (candidate, list_id))
                fixed += 1
                break
    return fixed


def _ensure_code_unique_index(cur) -> None:
    """给 code 建唯一索引（空值不参与 —— 老库回填前可以有多个空）。"""
    cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_lists_code "
                "ON lists (code) WHERE code IS NOT NULL AND code != ''")


def _apply(cur, default_list_name: str) -> dict:
    if "lists" not in _tables(cur):
        raise RuntimeError("lists 表不存在：init_db 应先在 create_all 里建好它")
    default_id = _ensure_default_list(cur, default_list_name)

    added = []
    if "list_id" not in _columns(cur, "items"):
        cur.execute("ALTER TABLE items ADD COLUMN list_id INTEGER "
                    "REFERENCES lists(id) ON DELETE CASCADE")
        added.append("items")
    cur.execute("UPDATE items SET list_id = ? WHERE list_id IS NULL", (default_id,))
    cur.execute("CREATE INDEX IF NOT EXISTS ix_items_list_id ON items (list_id)")

    if "list_id" not in _columns(cur, "rooms"):
        cur.execute("ALTER TABLE rooms ADD COLUMN list_id INTEGER "
                    "REFERENCES lists(id) ON DELETE CASCADE")
        added.append("rooms")
    cur.execute("UPDATE rooms SET list_id = ? WHERE list_id IS NULL", (default_id,))
    cur.execute("CREATE INDEX IF NOT EXISTS ix_rooms_list_id ON rooms (list_id)")

    if "list_id" not in _columns(cur, "categories"):
        cur.execute("ALTER TABLE categories ADD COLUMN list_id INTEGER "
                    "REFERENCES lists(id) ON DELETE CASCADE")
        added.append("categories")
    cur.execute("UPDATE categories SET list_id = ? WHERE list_id IS NULL",
                (default_id,))
    cur.execute("CREATE INDEX IF NOT EXISTS ix_categories_list_id "
                "ON categories (list_id)")

    rebuilt = False
    if ["name"] in _unique_indexes(cur, "categories"):
        _rebuild_categories(cur, default_id)
        rebuilt = True
        cur.execute("CREATE INDEX IF NOT EXISTS ix_categories_list_id "
                    "ON categories (list_id)")

    # 清单名放开唯一、编号成为身份：去掉 lists.name 的内联唯一约束，
    # 顺手给 code 建唯一索引（先扫掉撞号的）
    lists_rebuilt = _rebuild_lists_drop_name_unique(cur)
    if lists_rebuilt:
        # 重建后外键指向的仍是同一个 id（表名换了、数据原样搬），
        # 但索引随表一起丢了，得补回来
        default_id = _ensure_default_list(cur, default_list_name)
    recoded = _dedupe_list_codes(cur)
    _ensure_code_unique_index(cur)

    return {"default_list_id": default_id, "default_list_name": default_list_name,
            "columns_added": added, "categories_rebuilt": rebuilt,
            "lists_rebuilt": lists_rebuilt, "codes_fixed": recoded}


def _verify(cur, before: dict) -> None:
    after = _snapshot(cur)
    problems = []
    for key, rows in before.items():
        now = after.get(key)
        if now is None:
            problems.append(f"{key} 表不见了")
            continue
        # lists 会多出默认清单（老库一张都没有，迁移给它补一张），所以只核对
        # 迁移前就存在的那几行有没有原样保留 —— 多出来的不算丢数据
        if key == "lists":
            now = [row for row in now if row[0] in {r[0] for r in rows}]
        if len(now) != len(rows):
            problems.append(f"{key} 行数变了：{len(rows)} → {len(now)}")
        elif now != rows:
            idx = next(i for i, (a, b) in enumerate(zip(rows, now)) if a != b)
            problems.append(f"{key} 第 {idx + 1} 行内容变了：{rows[idx]!r} → {now[idx]!r}")
    # 三张表逐条断言：语句在调用点就是字面量，不做拼接也不经变量
    if _columns(cur, "items"):
        left = cur.execute(
            "SELECT COUNT(*) FROM items WHERE list_id IS NULL").fetchone()[0]
        if left:
            problems.append(f"items 还有 {left} 行没归到清单")
    if _columns(cur, "rooms"):
        left = cur.execute(
            "SELECT COUNT(*) FROM rooms WHERE list_id IS NULL").fetchone()[0]
        if left:
            problems.append(f"rooms 还有 {left} 行没归到清单")
    if _columns(cur, "categories"):
        left = cur.execute(
            "SELECT COUNT(*) FROM categories WHERE list_id IS NULL").fetchone()[0]
        if left:
            problems.append(f"categories 还有 {left} 行没归到清单")

    # 清单身份：已有的编号不能撞号（撞了 _dedupe_list_codes 会改，改完必须真不撞）。
    # 空编号不在这里判 —— 老库的编号由 seed 的轻量迁移回填，那一步在本次迁移之后。
    if "lists" in after:
        dup = cur.execute(
            "SELECT code FROM lists WHERE code IS NOT NULL AND code != '' "
            "GROUP BY code HAVING COUNT(*) > 1").fetchall()
        if dup:
            problems.append(f"清单编号重复：{[r[0] for r in dup][:5]}")

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

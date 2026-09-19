"""老库升级到多清单：数据必须一字不差地搬过去，且可重复执行。

这里刻意绕开 ORM：用 sqlite3 手写一个「老版本」的库文件（没有 lists 表，
items/rooms/categories 没有 list_id，categories.name 是全局唯一，items 还缺
brand/model/rev 等后来补的列），再跑 init_db() 走真实升级路径。

覆盖三件事：
  - 数据（条数、金额口径、明细）升级前后完全一致；
  - 升级前先备份，备份里就是那份老库；
  - 重复启动不会重复迁移、不会重复备份。
"""

import glob
import os
import sqlite3

import pytest

from app.db import DB_PATH, SessionLocal, engine
from app.models import Category, Item, ItemList, PurchaseRecord, Room
from app.seed import init_db
from app.services import compute

LEGACY_SCHEMA = """
CREATE TABLE users (
    id INTEGER NOT NULL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    created_at DATETIME, updated_at DATETIME
);
CREATE TABLE categories (
    id INTEGER NOT NULL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    sort INTEGER
);
CREATE TABLE rooms (
    id INTEGER NOT NULL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    sort INTEGER
);
CREATE TABLE items (
    id INTEGER NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    category_id INTEGER,
    unit VARCHAR(20), qty_total FLOAT, price FLOAT, discount_price FLOAT,
    bought BOOLEAN, note VARCHAR(500), sort INTEGER,
    FOREIGN KEY(category_id) REFERENCES categories (id)
);
CREATE TABLE purchase_records (
    id INTEGER NOT NULL PRIMARY KEY,
    item_id INTEGER NOT NULL,
    qty FLOAT, amount FLOAT, date VARCHAR(20), note VARCHAR(200),
    FOREIGN KEY(item_id) REFERENCES items (id) ON DELETE CASCADE
);
CREATE TABLE allocations (
    id INTEGER NOT NULL PRIMARY KEY,
    item_id INTEGER NOT NULL, room_id INTEGER NOT NULL,
    qty FLOAT, price_override FLOAT, note VARCHAR(200),
    FOREIGN KEY(item_id) REFERENCES items (id) ON DELETE CASCADE,
    FOREIGN KEY(room_id) REFERENCES rooms (id) ON DELETE CASCADE
);
"""


def _remove_db_files():
    for path in [DB_PATH] + glob.glob(DB_PATH + ".bak-*"):
        try:
            os.remove(path)
        except FileNotFoundError:
            pass


def _write_legacy_db():
    conn = sqlite3.connect(DB_PATH)
    try:
        conn.executescript(LEGACY_SCHEMA)
        conn.executemany("INSERT INTO rooms (id, name, sort) VALUES (?,?,?)",
                         [(1, "客厅", 0), (2, "餐厅", 1)])
        conn.executemany("INSERT INTO categories (id, name, sort) VALUES (?,?,?)",
                         [(1, "照明", 0), (2, "网络", 1)])
        conn.executemany(
            "INSERT INTO items (id, name, category_id, unit, qty_total, price, "
            "discount_price, bought, note, sort) VALUES (?,?,?,?,?,?,?,?,?,?)",
            [(1, "筒灯", 1, "个", 6, 99, 79.4, 1, "客厅用贵的", 0),
             (2, "网线", 2, "米", 150, 4.41, None, 0, "", 1)])
        conn.executemany(
            "INSERT INTO allocations (id, item_id, room_id, qty, price_override, note) "
            "VALUES (?,?,?,?,?,?)",
            [(1, 1, 1, 2, 119, "客厅用贵的"), (2, 1, 2, 4, None, "")])
        conn.execute("INSERT INTO purchase_records (id, item_id, qty, amount, date, note) "
                     "VALUES (1, 1, 2, 200, '2026-09-01', '定金')")
        conn.commit()
    finally:
        conn.close()


def _legacy_totals():
    """按老库里的数据把金额口径算出来（纯 SQL，不依赖应用代码）。"""
    conn = sqlite3.connect(DB_PATH)
    try:
        items = {r[0]: r for r in conn.execute(
            "SELECT id, price, discount_price, qty_total FROM items").fetchall()}
        allocs = conn.execute(
            "SELECT item_id, qty, price_override FROM allocations").fetchall()
        paid = conn.execute("SELECT COALESCE(SUM(amount), 0) FROM purchase_records") \
            .fetchone()[0]
    finally:
        conn.close()

    by_item = {}
    for item_id, qty, override in allocs:
        by_item.setdefault(item_id, []).append((qty, override))

    list_total = discount_total = 0.0
    for item_id, (_id, price, disc, qty_total) in items.items():
        rows = by_item.get(item_id)
        unit_disc = disc if disc is not None else price
        if rows:
            list_total += sum(q * (o if o is not None else price) for q, o in rows)
            total_qty = sum(q for q, _ in rows)
        else:
            list_total += (qty_total or 0) * (price or 0)
            total_qty = qty_total or 0
        discount_total += total_qty * (unit_disc or 0)
    return {"list_total": round(list_total, 2),
            "discount_total": round(discount_total, 2),
            "paid": round(paid, 2)}


@pytest.fixture()
def legacy_db():
    engine.dispose()
    _remove_db_files()
    _write_legacy_db()
    before = _legacy_totals()
    yield before
    engine.dispose()
    _remove_db_files()


def _unique_cols(table):
    conn = sqlite3.connect(DB_PATH)
    try:
        out = []
        # 表值函数写法：表名/索引名能当参数绑定，不进 SQL 文本
        for row in conn.execute("SELECT * FROM pragma_index_list(?)", (table,)):
            if row[2]:
                out.append([r[2] for r in conn.execute(
                    "SELECT * FROM pragma_index_info(?)", (row[1],))])
        return out
    finally:
        conn.close()


def _columns(table):
    conn = sqlite3.connect(DB_PATH)
    try:
        return {r[1] for r in conn.execute(
            "SELECT * FROM pragma_table_info(?)", (table,))}
    finally:
        conn.close()


def test_migration_moves_everything_into_default_list(legacy_db):
    init_db()
    db = SessionLocal()
    try:
        lists = db.query(ItemList).all()
        assert len(lists) == 1
        default = lists[0]
        assert default.name == "采购清单"

        assert {i.list_id for i in db.query(Item).all()} == {default.id}
        assert {r.list_id for r in db.query(Room).all()} == {default.id}
        assert {c.list_id for c in db.query(Category).all()} == {default.id}
        # id 全部保留：条目的分类指向没变
        assert {c.id for c in db.query(Category).all()} == {1, 2}
        assert db.query(Item).count() == 2
        assert db.query(Room).count() == 2
        assert db.query(PurchaseRecord).count() == 1

        # 分组名、备注这些老数据原样还在
        names = {r.name for r in db.query(Room).all()}
        assert names == {"客厅", "餐厅"}
        ceiling = db.query(Item).filter(Item.name == "筒灯").one()
        assert ceiling.note == "客厅用贵的"
    finally:
        db.close()

    cols = _columns("items")
    assert {"list_id", "brand", "model", "rev"} <= cols  # 多清单列 + 老补列都在


def test_migration_keeps_money_numbers(legacy_db):
    """升级前后三个金额口径必须完全一致。"""
    init_db()
    db = SessionLocal()
    try:
        views = [compute.item_dict(i) for i in db.query(Item).all()]
    finally:
        db.close()
    after = {"list_total": round(sum(v["list_total"] for v in views), 2),
             "discount_total": round(sum(v["discount_total"] for v in views), 2),
             "paid": round(sum(v["paid"] for v in views), 2)}
    assert after == legacy_db
    # 手算一遍钉住口径：筒灯(布点 2×119 + 4×99) + 网线(150×4.41)
    assert after == {"list_total": round(2 * 119 + 4 * 99 + 150 * 4.41, 2),
                     "discount_total": round(6 * 79.4 + 150 * 4.41, 2),
                     "paid": 200.0}


def test_migration_backs_up_the_old_db_first(legacy_db):
    init_db()
    backups = glob.glob(DB_PATH + ".bak-*")
    assert len(backups) == 1
    # 备份里就是迁移前那份老库：还没有 list_id，也还没有 lists 表
    conn = sqlite3.connect(backups[0])
    try:
        assert "list_id" not in {r[1] for r in conn.execute("PRAGMA table_info(items)")}
        assert conn.execute("SELECT COUNT(*) FROM items").fetchone()[0] == 2
        assert conn.execute("SELECT name FROM sqlite_master WHERE type='table' "
                            "AND name='lists'").fetchone() is None
    finally:
        conn.close()


def test_migration_is_idempotent(legacy_db):
    init_db()
    backups = set(glob.glob(DB_PATH + ".bak-*"))

    db = SessionLocal()
    try:
        first = [(i.id, i.name, i.list_id) for i in db.query(Item).all()]
    finally:
        db.close()

    init_db()  # 再启动一次

    assert set(glob.glob(DB_PATH + ".bak-*")) == backups, "第二次启动不该再备份"
    db = SessionLocal()
    try:
        assert [(i.id, i.name, i.list_id) for i in db.query(Item).all()] == first
        assert db.query(ItemList).count() == 1
    finally:
        db.close()


def test_categories_unique_within_list_only(legacy_db):
    init_db()
    assert ["list_id", "name"] in _unique_cols("categories")
    assert ["name"] not in _unique_cols("categories")

    db = SessionLocal()
    try:
        default_id = db.query(ItemList.id).scalar()
        other = ItemList(name="年货清单", sort=1)
        db.add(other)
        db.commit()
        # 另一份清单可以有自己的「照明」
        db.add(Category(name="照明", list_id=other.id))
        db.commit()
        assert db.query(Category).filter(Category.name == "照明").count() == 2
        assert db.query(Category).filter(Category.list_id == default_id,
                                         Category.name == "照明").count() == 1
    finally:
        db.close()


# ---------------------------------------------------------------- 清单名放开唯一

MULTI_LIST_SCHEMA = """
CREATE TABLE lists (
    id INTEGER NOT NULL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    note VARCHAR(200), sort INTEGER,
    created_at DATETIME, updated_at DATETIME,
    code VARCHAR(12)
);
CREATE TABLE items (
    id INTEGER NOT NULL PRIMARY KEY,
    list_id INTEGER REFERENCES lists (id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    category_id INTEGER,
    unit VARCHAR(20), qty_total FLOAT, price FLOAT, discount_price FLOAT,
    paid_amount FLOAT, paid_qty FLOAT, bought_qty FLOAT, bought BOOLEAN,
    note VARCHAR(500), sort INTEGER, rev INTEGER,
    brand VARCHAR(50), model VARCHAR(100), deleted_at DATETIME,
    created_at DATETIME, updated_at DATETIME
);
CREATE TABLE rooms (
    id INTEGER NOT NULL PRIMARY KEY,
    list_id INTEGER REFERENCES lists (id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL, sort INTEGER,
    created_at DATETIME, updated_at DATETIME
);
CREATE TABLE categories (
    id INTEGER NOT NULL PRIMARY KEY,
    list_id INTEGER REFERENCES lists (id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL, sort INTEGER,
    created_at DATETIME, updated_at DATETIME,
    CONSTRAINT uq_category_list_name UNIQUE (list_id, name)
);
CREATE TABLE allocations (
    id INTEGER NOT NULL PRIMARY KEY,
    item_id INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    room_id INTEGER NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    qty FLOAT, price_override FLOAT, paid_qty FLOAT, note VARCHAR(200)
);
CREATE TABLE purchase_records (
    id INTEGER NOT NULL PRIMARY KEY,
    item_id INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    qty FLOAT, amount FLOAT, date VARCHAR(20), note VARCHAR(200),
    vendor VARCHAR(50), order_no VARCHAR(50),
    room_id INTEGER REFERENCES rooms (id) ON DELETE SET NULL,
    created_at DATETIME, updated_at DATETIME
);
CREATE TABLE extra_expenses (
    id INTEGER NOT NULL PRIMARY KEY,
    list_id INTEGER REFERENCES lists (id) ON DELETE CASCADE,
    kind VARCHAR(20), amount FLOAT, date VARCHAR(20), vendor VARCHAR(50),
    order_no VARCHAR(50), note VARCHAR(200),
    item_id INTEGER REFERENCES items (id) ON DELETE SET NULL,
    created_at DATETIME, updated_at DATETIME
);
CREATE TABLE users (
    id INTEGER NOT NULL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    created_at DATETIME, updated_at DATETIME
);
"""


@pytest.fixture()
def multi_list_db():
    """多清单、但清单名还是全局唯一的库 —— 升级前就是这个样子。"""
    engine.dispose()
    _remove_db_files()
    conn = sqlite3.connect(DB_PATH)
    try:
        conn.executescript(MULTI_LIST_SCHEMA)
        conn.executemany(
            "INSERT INTO lists (id, name, note, sort, code) VALUES (?,?,?,?,?)",
            [(1, "采购清单", "老备注", 0, "AAAA1111"),
             (2, "年货清单", "", 1, "BBBB2222")])
        conn.execute(
            "INSERT INTO items (id, list_id, name, unit, qty_total, price, sort) "
            "VALUES (1, 1, '筒灯', '个', 6, 99, 0)")
        conn.execute(
            "INSERT INTO rooms (id, list_id, name, sort) VALUES (1, 1, '客厅', 0)")
        conn.execute(
            "INSERT INTO categories (id, list_id, name, sort) VALUES (1, 1, '照明', 0)")
        conn.commit()
    finally:
        conn.close()
    yield
    engine.dispose()
    _remove_db_files()


def test_migration_drops_name_unique_but_keeps_data(multi_list_db):
    """重建 lists 去掉 name 唯一：每一份清单（id/名字/备注/编号）原样还在。"""
    init_db()
    assert ["name"] not in _unique_cols("lists")

    db = SessionLocal()
    try:
        rows = {lst.id: lst for lst in db.query(ItemList).all()}
        assert set(rows) == {1, 2}
        assert rows[1].name == "采购清单" and rows[1].note == "老备注"
        assert rows[1].code == "AAAA1111"
        assert rows[2].name == "年货清单" and rows[2].code == "BBBB2222"
        # 挂在清单下的数据也没丢
        assert db.query(Item).filter(Item.list_id == 1).count() == 1
        assert db.query(Room).filter(Room.list_id == 1).count() == 1
        assert db.query(Category).filter(Category.list_id == 1).count() == 1
    finally:
        db.close()


def test_same_name_lists_can_coexist_after_migration(multi_list_db):
    """放开唯一之后，同名清单能并存，靠编号区分。"""
    init_db()
    db = SessionLocal()
    try:
        db.add(ItemList(name="采购清单", sort=2, code="CCCC3333"))
        db.commit()
        same = db.query(ItemList).filter(ItemList.name == "采购清单").all()
        assert len(same) == 2
        assert len({lst.code for lst in same}) == 2
    finally:
        db.close()


def test_code_has_unique_index(multi_list_db):
    """编号唯一索引建起来了（空值不参与，老库回填前可以有多个空）。"""
    init_db()
    conn = sqlite3.connect(DB_PATH)
    try:
        names = {row[1] for row in conn.execute("PRAGMA index_list(lists)")}
        assert "uq_lists_code" in names
    finally:
        conn.close()


def test_fresh_db_also_has_code_unique_index():
    """**全新安装**的库也要有编号唯一索引。

    从前这个索引只在"老库迁移"那条路上建，新装的实例反而没有 —— 编号唯一
    就只剩应用层"查一次再插入"，并发下能插进两个同号清单，手机端会认错清单。
    """
    _remove_db_files()
    init_db()          # 全新库：没有老数据要迁，needs_migration 返回 False

    conn = sqlite3.connect(DB_PATH)
    try:
        names = {row[1] for row in conn.execute("PRAGMA index_list(lists)")}
        assert "uq_lists_code" in names, f"新库缺少唯一索引：{names}"

        # 真的挡得住重复编号才算数
        conn.execute("UPDATE lists SET code = 'DUPCODE1' WHERE id = 1")
        conn.commit()
        try:
            conn.execute("INSERT INTO lists (name, note, sort, code) "
                         "VALUES ('撞号的', '', 9, 'DUPCODE1')")
            conn.commit()
            raise AssertionError("重复编号居然插进去了：唯一索引没生效")
        except sqlite3.IntegrityError:
            pass
    finally:
        conn.close()


def test_duplicate_codes_are_reissued(multi_list_db):
    """老库里万一有两份撞号，迁移时给后来那份重新发码，不会建索引失败。"""
    conn = sqlite3.connect(DB_PATH)
    try:
        conn.execute("UPDATE lists SET code = 'SAME0001' WHERE id = 2")
        conn.commit()
    finally:
        conn.close()

    init_db()
    db = SessionLocal()
    try:
        codes = [lst.code for lst in db.query(ItemList).order_by(ItemList.id).all()]
        assert len(codes) == len(set(codes)), f"编号仍重复：{codes}"
        assert all(c for c in codes), f"有清单没编号：{codes}"
    finally:
        db.close()


def test_lists_migration_is_idempotent(multi_list_db):
    """重复启动不会重复重建（第二次没有 name 唯一约束就不再动它）。"""
    init_db()
    first = _unique_cols("lists")
    init_db()
    assert _unique_cols("lists") == first
    assert ["name"] not in first

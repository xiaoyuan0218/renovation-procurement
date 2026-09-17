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
        for row in conn.execute(f"PRAGMA index_list({table})"):
            if row[2]:
                out.append([r[2] for r in conn.execute(f"PRAGMA index_info({row[1]})")])
        return out
    finally:
        conn.close()


def _columns(table):
    conn = sqlite3.connect(DB_PATH)
    try:
        return {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
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

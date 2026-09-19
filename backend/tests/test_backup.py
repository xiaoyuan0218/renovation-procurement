"""整库备份：下载下来是一个完整可用的库，传回去能把数据原样换回来。

恢复走的是 SQLite 的 backup API，不经过 ORM —— 连账号、清单结构、清单 id
一起换掉。备份可能是更老版本产出的，所以恢复之后必须再跑一遍结构升级。
"""

import glob
import io
import os
import sqlite3
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.auth import hash_password
from app.db import DB_PATH, SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, Item, ItemList, PurchaseRecord,
                        Room, User)
from app.seed import init_db
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER

LEGACY_SCHEMA = """
CREATE TABLE users (
    id INTEGER NOT NULL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    created_at DATETIME, updated_at DATETIME
);
CREATE TABLE categories (
    id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL UNIQUE, sort INTEGER
);
CREATE TABLE rooms (
    id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL, sort INTEGER
);
CREATE TABLE items (
    id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(100) NOT NULL, category_id INTEGER,
    unit VARCHAR(20), qty_total FLOAT, price FLOAT, discount_price FLOAT,
    bought BOOLEAN, note VARCHAR(500), sort INTEGER,
    FOREIGN KEY(category_id) REFERENCES categories (id)
);
CREATE TABLE purchase_records (
    id INTEGER NOT NULL PRIMARY KEY, item_id INTEGER NOT NULL,
    qty FLOAT, amount FLOAT, date VARCHAR(20), note VARCHAR(200),
    FOREIGN KEY(item_id) REFERENCES items (id) ON DELETE CASCADE
);
CREATE TABLE allocations (
    id INTEGER NOT NULL PRIMARY KEY, item_id INTEGER NOT NULL, room_id INTEGER NOT NULL,
    qty FLOAT, price_override FLOAT, note VARCHAR(200),
    FOREIGN KEY(item_id) REFERENCES items (id) ON DELETE CASCADE,
    FOREIGN KEY(room_id) REFERENCES rooms (id) ON DELETE CASCADE
);
"""


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    for table in (Allocation, Item, PurchaseRecord, Room, Category, User, ItemList):
        session.query(table).delete()
    session.commit()
    session.add(ItemList(name="采购清单", sort=0))
    session.commit()
    session.close()
    auth._failures.clear()
    with TestClient(app) as c:
        c.post("/api/auth/setup", json={"username": USER, "password": PASSWORD})
        yield c
    # 这个文件会把整库换掉（恢复备份就是这么工作的）。恢复过程中已经顺手做了
    # 结构升级，库本身不用管；把恢复产生的 .bak 文件清掉就行，别留给后面的测试
    engine.dispose()
    for path in glob.glob(DB_PATH + ".bak-*"):
        try:
            os.remove(path)
        except OSError:
            pass


def _first_list(client):
    return client.get("/api/lists").json()[0]


def _hdr(lst):
    return {"X-List-Id": str(lst["id"])}


def _add_item(client, lst, name, **kw):
    r = client.post("/api/items", json={"name": name, "unit": "个",
                                        "qty_total": 1, "price": 10, **kw},
                    headers=_hdr(lst))
    assert r.status_code == 200, r.text
    return r.json()


def _names(client, lst):
    return [i["name"] for i in client.get("/api/items", headers=_hdr(lst)).json()]


def _dump_to_bytes(sql: str, rows=()) -> bytes:
    """写一个临时 sqlite 文件并读回字节。"""
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    try:
        conn = sqlite3.connect(path)
        try:
            conn.executescript(sql)
            for statement, params in rows:
                conn.execute(statement, params)
            conn.commit()
        finally:
            conn.close()
        return Path(path).read_bytes()
    finally:
        os.remove(path)


def test_download_is_a_complete_sqlite_db(client):
    from urllib.parse import unquote
    lst = _first_list(client)
    _add_item(client, lst, "筒灯")

    r = client.get("/api/backup")
    assert r.status_code == 200
    assert r.content[:16] == b"SQLite format 3\x00"
    assert "清单备份" in unquote(r.headers["content-disposition"])

    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    try:
        Path(path).write_bytes(r.content)
        conn = sqlite3.connect(path)
        try:
            assert conn.execute("SELECT COUNT(*) FROM items").fetchone()[0] == 1
            assert conn.execute("SELECT name FROM lists").fetchone()[0] == "采购清单"
        finally:
            conn.close()
    finally:
        os.remove(path)


def test_restore_brings_data_back(client):
    lst = _first_list(client)
    _add_item(client, lst, "筒灯")
    backup = client.get("/api/backup").content

    # 之后把库改得面目全非
    _add_item(client, lst, "网线")
    client.post("/api/items/batch/delete",
                json={"ids": [i["id"] for i in
                              client.get("/api/items", headers=_hdr(lst)).json()]},
                headers=_hdr(lst))
    assert _names(client, lst) == []

    r = client.post("/api/backup/restore",
                    files={"file": ("backup.db", io.BytesIO(backup),
                                    "application/octet-stream")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ok"] is True
    assert body["current"]["items"] == 1
    # 恢复前的现状被另存了一份，传错文件也救得回来
    assert os.path.isfile(body["previous_backup"])
    assert body["previous_backup"].startswith(DB_PATH)

    assert _names(client, lst) == ["筒灯"]


def test_restore_rejects_garbage_and_leaves_data_alone(client):
    lst = _first_list(client)
    _add_item(client, lst, "筒灯")

    r = client.post("/api/backup/restore",
                    files={"file": ("x.db", io.BytesIO(b"this is not a database"),
                                    "application/octet-stream")})
    assert r.status_code == 400
    assert "SQLite" in r.json()["detail"]

    empty = client.post("/api/backup/restore",
                        files={"file": ("x.db", io.BytesIO(b""),
                                        "application/octet-stream")})
    assert empty.status_code == 400

    # 不是本工具的库（没有 items 表）同样挡住
    other = _dump_to_bytes("CREATE TABLE foo (id INTEGER);")
    wrong = client.post("/api/backup/restore",
                        files={"file": ("x.db", io.BytesIO(other),
                                        "application/octet-stream")})
    assert wrong.status_code == 400
    assert "清单数据表" in wrong.json()["detail"]

    assert _names(client, lst) == ["筒灯"]


def test_restore_of_old_version_backup_migrates_it(client):
    """老版本的备份（没有 lists 表）传回去，要自动升级成多清单结构。"""
    legacy = _dump_to_bytes(
        LEGACY_SCHEMA,
        rows=[
            ("INSERT INTO users (id, username, password_hash) VALUES (?,?,?)",
             (1, "oldadmin", hash_password("legacy-pass"))),
            ("INSERT INTO rooms (id, name, sort) VALUES (1, '客厅', 0)", ()),
            ("INSERT INTO categories (id, name, sort) VALUES (1, '照明', 0)", ()),
            ("INSERT INTO items (id, name, category_id, unit, qty_total, price, "
             "discount_price, bought, note, sort) VALUES (1, '筒灯', 1, '个', 6, 99, 79.4, 0, '', 0)", ()),
            ("INSERT INTO allocations (id, item_id, room_id, qty, price_override, note) "
             "VALUES (1, 1, 1, 2, 119, '')", ()),
        ])

    r = client.post("/api/backup/restore",
                    files={"file": ("old.db", io.BytesIO(legacy),
                                    "application/octet-stream")})
    assert r.status_code == 200, r.text
    assert r.json()["incoming"]["lists"] == 0      # 传进来时还没有清单
    assert r.json()["current"]["lists"] == 1       # 升级后有一份默认清单

    # 账号也跟着备份走了：得用老库里的账号重新登录（这也说明结构确实换掉了）
    fresh = TestClient(app)
    fresh.post("/api/auth/login", json={"username": "oldadmin", "password": "legacy-pass"})
    lst = fresh.get("/api/lists").json()[0]
    assert lst["name"] == "采购清单"
    assert lst["item_count"] == 1
    items = fresh.get("/api/items", headers={"X-List-Id": str(lst["id"])}).json()
    assert items[0]["name"] == "筒灯"
    assert items[0]["list_total"] == 2 * 119

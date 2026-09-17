"""清单级同步：整份导出、整份灌回、或者新建一份。

手机单机版靠这三个接口把本地清单搬上服务器、或把服务器上的清单带回手机。
最关键的一条是**搬运不改变数据**：导出再覆盖回去，三个口径的数字必须分文不差 ——
否则来回搬几次就会累积偏差，用户还不知道该信哪一边。
"""

import glob
import os

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import DB_PATH, SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, ExtraExpense, Item, ItemList,
                        PurchaseRecord, RecordRoom, Room, User)
from app.seed import init_db

USER = "admin"
PASSWORD = "s3cret-pass"


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    for table in (RecordRoom, Allocation, PurchaseRecord, ExtraExpense, Item,
                  Room, Category, User, ItemList):
        session.query(table).delete()
    session.commit()
    session.add(ItemList(name="采购清单", sort=0))
    session.commit()
    session.close()
    auth._failures.clear()
    with TestClient(app) as c:
        c.post("/api/auth/setup", json={"username": USER, "password": PASSWORD})
        yield c
    engine.dispose()


def _mk_list(client, name="装修采购"):
    r = client.post("/api/lists", json={"name": name})
    assert r.status_code == 200, r.text
    return r.json()


def _hdr(lst):
    return {"X-List-Id": str(lst["id"])}


def _seed(client, lst):
    """造一批有代表性的数据：两个分组、一个分类、两条物料（带分配与采购记录）、一笔费用。"""
    h = _hdr(lst)
    rooms = [client.post("/api/rooms", json={"name": n}, headers=h).json()
             for n in ("客厅", "主卧")]
    cat = client.post("/api/categories", json={"name": "照明"}, headers=h).json()
    item = client.post("/api/items", json={
        "name": "筒灯", "category_id": cat["id"], "brand": "松下", "model": "NN-3021",
        "unit": "个", "price": 30.5, "discount_price": 28.0,
        "allocations": [
            {"room_id": rooms[0]["id"], "qty": 6},
            {"room_id": rooms[1]["id"], "qty": 4, "price_override": 25.0},
        ],
        "records": [{
            "qty": 6, "amount": 180.5, "date": "2026-09-01",
            "vendor": "京东", "order_no": "A1", "room_ids": [rooms[0]["id"]],
        }],
    }, headers=h).json()
    switch = client.post("/api/items", json={
        "name": "开关", "unit": "个", "qty_total": 10, "price": 12.5,
    }, headers=h).json()
    client.post("/api/expenses", json={
        "kind": "运费", "amount": 80, "date": "2026-09-02",
    }, headers=h)
    return rooms, cat, item, switch


def _export(client, lst):
    r = client.get(f"/api/sync/lists/{lst['id']}", headers=_hdr(lst))
    assert r.status_code == 200, r.text
    return r.json()


def _push(client, lst, snapshot, **extra):
    body = {**snapshot["payload"], "base_fingerprint": snapshot["fingerprint"], **extra}
    return client.put(f"/api/sync/lists/{lst['id']}", json=body, headers=_hdr(lst))


# ---------------------------------------------------------------- 导出

def test_export_carries_content_but_not_derived_fields(client):
    lst = _mk_list(client)
    rooms, cat, item, _ = _seed(client, lst)

    snap = _export(client, lst)
    assert set(snap) == {"fingerprint", "payload"}
    payload = snap["payload"]
    assert payload["version"] == 1
    assert payload["list"]["name"] == "装修采购"

    assert {r["name"] for r in payload["rooms"]} == {"客厅", "主卧"}
    assert [c["name"] for c in payload["categories"]] == ["照明"]

    names = {i["name"] for i in payload["items"]}
    assert names == {"筒灯", "开关"}
    light = next(i for i in payload["items"] if i["name"] == "筒灯")
    assert light["brand"] == "松下" and light["model"] == "NN-3021"
    assert light["price"] == 30.5 and light["discount_price"] == 28.0
    assert len(light["allocations"]) == 2
    assert {a["qty"] for a in light["allocations"]} == {6, 4}
    record = light["records"][0]
    assert record["amount"] == 180.5 and record["vendor"] == "京东"
    assert record["room_ids"] == [rooms[0]["id"]]

    assert [e["kind"] for e in payload["expenses"]] == ["运费"]

    # 派生值与历史字段不参与搬运：带上只会让"内容没变"被误判成"变过"
    for junk in ("rev", "bought", "paid_qty", "paid_amount", "bought_qty", "created_at"):
        assert junk not in light


def test_fingerprint_is_stable_then_changes_on_any_edit(client):
    lst = _mk_list(client)
    _seed(client, lst)

    first = _export(client, lst)["fingerprint"]
    assert _export(client, lst)["fingerprint"] == first

    # 只加一笔费用，指纹就该变 —— 说明它盯着的是内容，而不是某个漏了打点的字段
    client.post("/api/expenses", json={"kind": "安装费", "amount": 50},
                headers=_hdr(lst))
    assert _export(client, lst)["fingerprint"] != first


def test_export_only_covers_its_own_list(client):
    a = _mk_list(client, "装修采购")
    b = _mk_list(client, "年货")
    _seed(client, a)
    client.post("/api/items", json={"name": "坚果", "qty_total": 3},
                headers=_hdr(b))

    payload = _export(client, a)["payload"]
    assert "坚果" not in {i["name"] for i in payload["items"]}
    assert {i["name"] for i in payload["items"]} == {"筒灯", "开关"}


def test_trashed_items_travel_along(client):
    lst = _mk_list(client)
    _, _, item, switch = _seed(client, lst)
    client.delete(f"/api/items/{switch['id']}", headers=_hdr(lst))

    payload = _export(client, lst)["payload"]
    trashed = next(i for i in payload["items"] if i["name"] == "开关")
    assert trashed["deleted_at"], "回收站里的物料也要跟着走，两端回收站才能一致"

    # 灌回去之后仍然是回收站状态，不会"复活"
    put = _push(client, lst, _export(client, lst))
    assert put.status_code == 200, put.text
    names = [t["name"] for t in client.get("/api/trash", headers=_hdr(lst)).json()]
    assert "开关" in names


def test_empty_list_exports_cleanly(client):
    lst = _mk_list(client)
    payload = _export(client, lst)["payload"]
    assert payload["items"] == [] and payload["rooms"] == []
    assert payload["expenses"] == []


# ---------------------------------------------------------------- 覆盖

def test_replace_roundtrip_keeps_every_total(client):
    """导出 → 覆盖回去：三个口径的数字分文不差，条数也一样。"""
    lst = _mk_list(client)
    _seed(client, lst)
    h = _hdr(lst)
    before = client.get("/api/summary", headers=h).json()["totals"]

    put = _push(client, lst, _export(client, lst))
    assert put.status_code == 200, put.text

    after = client.get("/api/summary", headers=h).json()["totals"]
    assert after == before


def test_replace_drops_what_the_sender_no_longer_has(client):
    """以推送方为准：手机上没有的东西，服务器上也要消失。"""
    lst = _mk_list(client)
    _, _, _, switch = _seed(client, lst)
    h = _hdr(lst)

    snap = _export(client, lst)
    snap["payload"]["items"] = [i for i in snap["payload"]["items"]
                               if i["name"] != "开关"]
    put = _push(client, lst, snap)
    assert put.status_code == 200, put.text

    names = {i["name"] for i in client.get("/api/items", headers=h).json()}
    assert names == {"筒灯"}
    assert client.get(f"/api/items/{switch['id']}", headers=h).status_code == 404


def test_stale_fingerprint_is_rejected(client):
    lst = _mk_list(client)
    _seed(client, lst)
    h = _hdr(lst)
    snap = _export(client, lst)

    # 服务器这边又改了一处（模拟电脑上也在动）
    client.post("/api/expenses", json={"kind": "安装费", "amount": 50}, headers=h)

    put = _push(client, lst, snap)
    assert put.status_code == 409
    detail = put.json()["detail"]
    assert detail["current_fingerprint"] == _export(client, lst)["fingerprint"]


def test_force_overwrites_and_keeps_a_backup(client):
    lst = _mk_list(client)
    _seed(client, lst)
    h = _hdr(lst)
    snap = _export(client, lst)
    client.post("/api/expenses", json={"kind": "安装费", "amount": 50}, headers=h)

    before_backups = set(glob.glob(f"{DB_PATH}.bak-*"))
    put = _push(client, lst, snap, force=True)
    assert put.status_code == 200, put.text

    # 用户选了"以我为准"，覆盖前要留一份能救回来的
    after_backups = set(glob.glob(f"{DB_PATH}.bak-*"))
    assert len(after_backups) > len(before_backups), "force 覆盖前应留整库备份"

    kinds = {e["kind"] for e in client.get("/api/expenses", headers=h).json()}
    assert kinds == {"运费"}, "强制覆盖后应该是推送方的内容"


# ---------------------------------------------------------------- 新建

def test_create_list_from_payload(client):
    src = _mk_list(client, "手机装修")
    _seed(client, src)
    h = _hdr(src)
    before = client.get("/api/lists").json()

    payload = _export(client, src)["payload"]
    payload["list"] = {**payload["list"], "name": "手机搬来的清单"}
    r = client.post("/api/sync/lists", json=payload)
    assert r.status_code == 200, r.text
    created = r.json()
    new_id = created["list_id"]
    assert new_id != src["id"]

    # 服务器上的老清单原封不动
    assert len(client.get("/api/lists").json()) == len(before) + 1
    assert client.get("/api/summary", headers=h).json()["totals"]["item_count"] == 2

    # 新清单里的内容与来源一致
    new_h = {"X-List-Id": str(new_id)}
    totals = client.get("/api/summary", headers=new_h).json()["totals"]
    assert totals["item_count"] == 2
    assert totals["paid_total"] == 180.5
    assert {r["name"] for r in client.get("/api/rooms", headers=new_h).json()} == {"客厅", "主卧"}
    assert {i["name"] for i in client.get("/api/items", headers=new_h).json()} == {"筒灯", "开关"}


def test_create_renames_on_conflict(client):
    _mk_list(client, "装修采购")
    src = _mk_list(client, "别的")
    _seed(client, src)

    payload = _export(client, src)["payload"]
    payload["list"] = {**payload["list"], "name": "装修采购"}   # 与已有清单重名
    r = client.post("/api/sync/lists", json=payload)
    assert r.status_code == 200, r.text
    assert r.json()["list_id"]

    names = {lst["name"] for lst in client.get("/api/lists").json()}
    assert "装修采购" in names and "装修采购 2" in names


# ---------------------------------------------------------------- 鉴权

def test_requires_login(client):
    lst = _mk_list(client)
    _seed(client, lst)
    client.post("/api/auth/logout")

    assert client.get(f"/api/sync/lists/{lst['id']}").status_code == 401
    assert client.put(f"/api/sync/lists/{lst['id']}", json={}).status_code == 401
    assert client.post("/api/sync/lists", json={}).status_code == 401


def test_unknown_list_is_404(client):
    assert client.get("/api/sync/lists/999999", headers={"X-List-Id": "1"}).status_code == 404

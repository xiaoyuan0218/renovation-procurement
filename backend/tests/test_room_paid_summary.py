"""总览的各分组实付金额（by_room.paid）：按记录勾选的 room_ids 归集。

口径在这里钉死（详见 summary.py 里的注释）：
- 勾了 N 个分组 → 金额按 N 份均摊，每笔钱不重不漏；
- 没勾分组的记录不计入任何分组，合计在 by_room_unassigned_paid；
- 定金与退款（负数）照常按勾选分组计入。
硬不变量：Σby_room.paid + by_room_unassigned_paid = totals.paid_total。
"""

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, ExtraExpense, Item, ItemList,
                        PurchaseRecord, Room, User)
from app.seed import init_db
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    for table in (ExtraExpense, Allocation, Item, PurchaseRecord, Room, Category,
                  User, ItemList):
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


def _hdr(client):
    return {"X-List-Id": str(client.get("/api/lists").json()[0]["id"])}


def _scene(client):
    """两个分组 + 一条四分配额的物料，返回 (item, living, dining)。"""
    hdr = _hdr(client)
    living = client.post("/api/rooms", json={"name": "客厅", "sort": 0}, headers=hdr).json()
    dining = client.post("/api/rooms", json={"name": "餐厅", "sort": 1}, headers=hdr).json()
    item = client.post("/api/items", json={"name": "筒灯", "unit": "个", "price": 100},
                       headers=hdr).json()
    client.put(f"/api/items/{item['id']}", headers=hdr, json={
        "name": "筒灯", "unit": "个", "qty_total": 0, "price": 100,
        "allocations": [{"room_id": living["id"], "qty": 2},
                        {"room_id": dining["id"], "qty": 2}],
        "records": [],
        "base_rev": client.get(f"/api/items/{item['id']}", headers=hdr).json()["rev"],
    })
    return item, living, dining


def _summary(client):
    return client.get("/api/summary", headers=_hdr(client)).json()


def _paid_by_name(summary):
    return {row["name"]: row["paid"] for row in summary["by_room"]}


def test_payment_lands_on_its_room(client):
    item, living, dining = _scene(client)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 2, "amount": 200, "room_ids": [living["id"]]},
                headers=_hdr(client))
    paid = _paid_by_name(_summary(client))
    assert paid["客厅"] == 200
    assert paid["餐厅"] == 0


def test_one_payment_splits_evenly_across_rooms(client):
    """一笔 300 勾了客厅和餐厅：两间各摊 150（均分口径）。"""
    item, living, dining = _scene(client)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 3, "amount": 300, "room_ids": [living["id"], dining["id"]]},
                headers=_hdr(client))
    paid = _paid_by_name(_summary(client))
    assert paid["客厅"] == 150
    assert paid["餐厅"] == 150


def test_uneven_split_leaves_no_penny_behind(client):
    """除不尽时尾差落在最后一间：三间摊 100 = 33.33 + 33.33 + 33.34。"""
    hdr = _hdr(client)
    item, living, dining = _scene(client)
    bath = client.post("/api/rooms", json={"name": "卫生间", "sort": 2}, headers=hdr).json()
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 3, "amount": 100,
                      "room_ids": [living["id"], dining["id"], bath["id"]]},
                headers=hdr)
    paid = _paid_by_name(_summary(client))
    assert paid["客厅"] == 33.33 and paid["餐厅"] == 33.33
    assert paid["卫生间"] == 33.34
    assert round(sum(paid.values()), 2) == 100


def test_unassigned_payment_goes_to_unassigned_bucket(client):
    """没勾分组的记录不计入任何一间，但一分钱都不能丢。"""
    item, living, dining = _scene(client)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 4, "amount": 500}, headers=_hdr(client))
    summary = _summary(client)
    assert all(row["paid"] == 0 for row in summary["by_room"])
    assert summary["by_room_unassigned_paid"] == 500


def test_legacy_single_room_id_still_counts(client):
    """老客户端的单值 room_id 写法，归集时同样认。"""
    item, living, dining = _scene(client)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 4, "amount": 400, "room_id": dining["id"]},
                headers=_hdr(client))
    paid = _paid_by_name(_summary(client))
    assert paid["餐厅"] == 400
    assert paid["客厅"] == 0


def test_deposit_and_refund_follow_their_rooms(client):
    """定金计入、退款（负数）也按勾选分组扣回 —— 与 paid_total 同口径。"""
    hdr = _hdr(client)
    item, living, dining = _scene(client)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 0, "amount": 100, "is_deposit": True, "room_ids": [living["id"]]},
                headers=hdr)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 2, "amount": 300, "room_ids": [living["id"]]},
                headers=hdr)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 0, "amount": -50, "room_ids": [living["id"]]},
                headers=hdr)
    summary = _summary(client)
    assert _paid_by_name(summary)["客厅"] == 350
    totals = summary["totals"]
    assert totals["paid_total"] == 350
    assert (round(sum(r["paid"] for r in summary["by_room"])
                  + summary["by_room_unassigned_paid"], 2) == totals["paid_total"])


def test_identity_holds_on_mixed_data(client):
    """混合数据（勾单间、勾多间、不勾、退款）下守恒等式必须严格成立。"""
    hdr = _hdr(client)
    item, living, dining = _scene(client)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 1, "amount": 100, "room_ids": [living["id"]]}, headers=hdr)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 2, "amount": 333.33, "room_ids": [living["id"], dining["id"]]},
                headers=hdr)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 1, "amount": 66.67}, headers=hdr)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 0, "amount": -10, "room_ids": [dining["id"]]}, headers=hdr)
    summary = _summary(client)
    assert round(sum(r["paid"] for r in summary["by_room"])
                 + summary["by_room_unassigned_paid"], 2) == summary["totals"]["paid_total"]
    assert summary["by_room_unassigned_paid"] == 66.67


def test_trashed_item_no_longer_counts(client):
    """物料进回收站后，它的付款不再出现在分组已付里（与 totals 同一步调）。"""
    hdr = _hdr(client)
    item, living, dining = _scene(client)
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 2, "amount": 200, "room_ids": [living["id"]]}, headers=hdr)
    client.delete(f"/api/items/{item['id']}", headers=hdr)
    summary = _summary(client)
    assert all(row["paid"] == 0 for row in summary["by_room"])
    assert summary["totals"]["paid_total"] == 0


def test_other_list_never_leaks_in(client):
    item, living, dining = _scene(client)
    other = client.post("/api/lists", json={"name": "年货清单"}).json()
    hdr2 = {"X-List-Id": str(other["id"])}
    item2 = client.post("/api/items", json={"name": "春联", "qty_total": 1, "price": 20},
                        headers=hdr2).json()
    client.post(f"/api/items/{item2['id']}/records",
                json={"qty": 1, "amount": 88, "room_ids": [living["id"]]}, headers=hdr2)
    paid = _paid_by_name(_summary(client))
    assert paid["客厅"] == 0    # 别的清单勾了同名分组也进不来

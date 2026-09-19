"""并发覆盖保护：整条保存（PUT）必须发现「这期间别处也改了这条」。

场景是真实的：网页上打开某物料的编辑框还没保存，家里人在手机上给它记了一笔
付款；这时网页点保存，如果直接把整条写回去，那笔付款就没了 —— 因为重建采购
记录是「先清空再写入」。
"""

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import Allocation, Category, Item, PurchaseRecord, Room, User
from app.seed import init_db
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    for table in (Allocation, Item, PurchaseRecord, Room, Category, User):
        session.query(table).delete()
    session.commit()
    session.close()
    auth._failures.clear()
    with TestClient(app) as c:
        c.post("/api/auth/setup", json={"username": USER, "password": PASSWORD})
        yield c
    engine.dispose()


def _make_item(client):
    r = client.post("/api/items", json={
        "name": "筒灯", "unit": "个", "qty_total": 6, "price": 99,
        "discount_price": 79.5, "note": "",
    })
    assert r.status_code == 200, r.text
    return r.json()


def test_put_with_stale_rev_is_rejected(client):
    """别处改过之后，拿旧 rev 整条保存要被挡住。"""
    item = _make_item(client)
    stale_rev = item["rev"]

    # 模拟另一台设备记了一笔采购（会顶高 rev）
    r = client.post(f"/api/items/{item['id']}/records",
                    json={"qty": 2, "amount": 200})
    assert r.status_code == 200
    assert r.json()["rev"] != stale_rev

    # 网页那边仍拿着旧 rev 整条保存（records 里没有那笔付款）
    r = client.put(f"/api/items/{item['id']}", json={
        "name": "筒灯", "unit": "个", "qty_total": 6, "price": 99,
        "discount_price": 79.5, "note": "",
        "allocations": [], "records": [],
        "base_rev": stale_rev,
    })
    assert r.status_code == 409
    assert "别处" in r.json()["detail"]

    # 那笔付款必须还在
    got = client.get(f"/api/items/{item['id']}").json()
    assert got["paid"] == 200
    assert len(got["records"]) == 1


def test_put_with_current_rev_succeeds(client):
    """拿最新 rev 保存照常成功。"""
    item = _make_item(client)
    fresh = client.get(f"/api/items/{item['id']}").json()
    r = client.put(f"/api/items/{item['id']}", json={
        "name": "筒灯（改名）", "unit": "个", "qty_total": 6, "price": 99,
        "discount_price": 79.5, "note": "",
        "allocations": [], "records": [],
        "base_rev": fresh["rev"],
    })
    assert r.status_code == 200
    assert r.json()["name"] == "筒灯（改名）"


def test_put_without_base_rev_is_allowed(client):
    """老客户端不带 base_rev —— 不做校验，避免升级期直接坏掉。"""
    item = _make_item(client)
    client.post(f"/api/items/{item['id']}/records", json={"qty": 1, "amount": 50})
    r = client.put(f"/api/items/{item['id']}", json={
        "name": "筒灯", "unit": "个", "qty_total": 6, "price": 99,
        "discount_price": None, "note": "", "allocations": [], "records": [],
    })
    assert r.status_code == 200


def test_rev_bumps_on_every_kind_of_write(client):
    """改字段、记一笔、改记录、删记录、清空、矩阵改格 —— 都要顶高 rev。"""
    item = _make_item(client)
    iid = item["id"]

    def rev():
        return client.get(f"/api/items/{iid}").json()["rev"]

    seen = [rev()]
    client.patch(f"/api/items/{iid}", json={"note": "改个备注"})
    seen.append(rev())

    rec = client.post(f"/api/items/{iid}/records", json={"qty": 1, "amount": 50}).json()
    seen.append(rec["rev"])
    rid = rec["records"][0]["id"]

    client.put(f"/api/records/{rid}", json={"amount": 60})
    seen.append(rev())

    room = client.post("/api/rooms", json={"name": "客厅"}).json()
    client.put("/api/matrix/cell",
               json={"item_id": iid, "room_id": room["id"], "qty": 3})
    seen.append(rev())

    client.delete(f"/api/records/{rid}")
    seen.append(rev())

    client.delete(f"/api/items/{iid}/records")
    seen.append(rev())

    assert all(b > a for a, b in zip(seen, seen[1:])), f"rev 没有严格递增：{seen}"

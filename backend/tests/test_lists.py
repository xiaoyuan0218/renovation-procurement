"""多清单：每份清单的条目、分组、分类彼此隔离，既看不见也改不到。

隔离靠每个接口上的「当前清单」依赖：请求头 X-List-Id（网页、安卓）或
?list_id= 查询参数（导出这类 <a href> 直接导航的走不了自定义头）。
两个都不带的请求落到第一份清单 —— 升级后的老版安卓走的就是这条路，
它必须仍能看到升级前的全部数据。
"""

import io
from urllib.parse import unquote

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, Item, ItemList, PurchaseRecord,
                        Room, User)
from app.seed import init_db
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER


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
    engine.dispose()


def _mk_list(client, name):
    r = client.post("/api/lists", json={"name": name})
    assert r.status_code == 200, r.text
    return r.json()


def _hdr(lst):
    return {"X-List-Id": str(lst["id"])}


def _mk_item(client, lst, name="筒灯", **kw):
    body = {"name": name, "unit": "个", "qty_total": 4, "price": 99, **kw}
    r = client.post("/api/items", json=body, headers=_hdr(lst))
    assert r.status_code == 200, r.text
    return r.json()


def test_default_list_is_created_for_fresh_db(client):
    lists = client.get("/api/lists").json()
    assert len(lists) == 1
    assert lists[0]["name"] == "采购清单"


def test_list_crud_and_usage_counts(client):
    a = client.get("/api/lists").json()[0]
    _mk_item(client, a)
    client.post("/api/rooms", json={"name": "客厅", "sort": 0}, headers=_hdr(a))
    client.post("/api/categories", json={"name": "照明", "sort": 0}, headers=_hdr(a))

    got = client.get("/api/lists").json()[0]
    assert (got["item_count"], got["room_count"], got["category_count"]) == (1, 1, 1)

    r = client.put(f"/api/lists/{a['id']}",
                   json={"name": "装修采购", "note": "2026 老房翻新", "sort": 0})
    assert r.status_code == 200
    assert r.json()["name"] == "装修采购"
    assert r.json()["note"] == "2026 老房翻新"

    # 名字允许重复：编号才是身份，同名两份各自独立（不再报「已存在」）
    dup = client.post("/api/lists", json={"name": "装修采购"})
    assert dup.status_code == 200
    assert dup.json()["name"] == "装修采购"
    assert dup.json()["id"] != r.json()["id"]
    assert dup.json()["code"] != r.json()["code"]


def test_items_are_isolated_between_lists(client):
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    item = _mk_item(client, a, "筒灯")
    client.post("/api/rooms", json={"name": "客厅", "sort": 0}, headers=_hdr(a))
    client.post("/api/categories", json={"name": "照明", "sort": 0}, headers=_hdr(a))

    # 清单 B 里什么都看不到
    assert client.get("/api/items", headers=_hdr(b)).json() == []
    assert client.get("/api/rooms", headers=_hdr(b)).json() == []
    assert client.get("/api/categories", headers=_hdr(b)).json() == []
    assert client.get("/api/summary", headers=_hdr(b)).json()["totals"]["item_count"] == 0
    assert client.get("/api/matrix", headers=_hdr(b)).json() == {"rooms": [], "items": []}

    # 拿着 A 的条目 ID 去 B 里操作：一律 404，不能改也不能看
    assert client.get(f"/api/items/{item['id']}", headers=_hdr(b)).status_code == 404
    assert client.patch(f"/api/items/{item['id']}", json={"note": "x"},
                        headers=_hdr(b)).status_code == 404
    assert client.delete(f"/api/items/{item['id']}", headers=_hdr(b)).status_code == 404
    put = client.put(f"/api/items/{item['id']}",
                     json={"name": "改过", "unit": "个", "qty_total": 1, "price": 1},
                     headers=_hdr(b))
    assert put.status_code == 404
    rec = client.post(f"/api/items/{item['id']}/records", json={"qty": 1, "amount": 1},
                      headers=_hdr(b))
    assert rec.status_code == 404

    # A 里照旧
    assert len(client.get("/api/items", headers=_hdr(a)).json()) == 1
    assert client.get(f"/api/items/{item['id']}", headers=_hdr(a)).status_code == 200


def test_batch_delete_only_touches_current_list(client):
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    item_a = _mk_item(client, a, "筒灯")
    item_b = _mk_item(client, b, "糖果")

    # 用 B 的清单头去删 A 的条目：删不掉，B 自己的不受影响
    r = client.post("/api/items/batch/delete", json={"ids": [item_a["id"]]},
                    headers=_hdr(b))
    assert r.status_code == 200 and r.json()["deleted"] == 0
    assert client.get(f"/api/items/{item_a['id']}", headers=_hdr(a)).status_code == 200
    assert len(client.get("/api/items", headers=_hdr(b)).json()) == 1

    r = client.post("/api/items/batch/delete", json={"ids": [item_b["id"]]},
                    headers=_hdr(b))
    assert r.json()["deleted"] == 1
    assert client.get("/api/items", headers=_hdr(b)).json() == []


def test_matrix_and_allocation_are_isolated(client):
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    item = _mk_item(client, a, "筒灯")
    room_a = client.post("/api/rooms", json={"name": "客厅", "sort": 0},
                         headers=_hdr(a)).json()
    room_b = client.post("/api/rooms", json={"name": "客厅", "sort": 0},
                         headers=_hdr(b)).json()
    assert room_a["id"] != room_b["id"]  # 同名分组各归各的清单

    # 用 B 的房间去给 A 的条目布点：挡住
    r = client.put("/api/matrix/cell",
                   json={"item_id": item["id"], "room_id": room_b["id"], "qty": 2},
                   headers=_hdr(a))
    assert r.status_code == 404

    # 同一个房间 ID 在 B 的清单头下也用不了 A 的条目
    r = client.put("/api/matrix/cell",
                   json={"item_id": item["id"], "room_id": room_b["id"], "qty": 2},
                   headers=_hdr(b))
    assert r.status_code == 404

    # 各自清单内正常
    ok = client.put("/api/matrix/cell",
                    json={"item_id": item["id"], "room_id": room_a["id"], "qty": 2},
                    headers=_hdr(a))
    assert ok.status_code == 200
    m = client.get("/api/matrix", headers=_hdr(a)).json()
    assert m["items"][0]["cells"][str(room_a["id"])]["qty"] == 2
    assert client.get("/api/matrix", headers=_hdr(b)).json()["items"] == []


def test_category_with_same_name_in_two_lists(client):
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    r1 = client.post("/api/categories", json={"name": "照明", "sort": 0}, headers=_hdr(a))
    r2 = client.post("/api/categories", json={"name": "照明", "sort": 0}, headers=_hdr(b))
    assert r1.status_code == 200 and r2.status_code == 200
    assert r1.json()["id"] != r2.json()["id"]
    # 同一份清单里重名仍然不允许
    dup = client.post("/api/categories", json={"name": "照明", "sort": 0}, headers=_hdr(a))
    assert dup.status_code == 400


def test_item_category_must_belong_to_same_list(client):
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    cat_b = client.post("/api/categories", json={"name": "照明", "sort": 0},
                        headers=_hdr(b)).json()
    r = client.post("/api/items", json={"name": "筒灯", "unit": "个",
                                        "category_id": cat_b["id"]},
                    headers=_hdr(a))
    assert r.status_code == 400 and "不属于当前清单" in r.json()["detail"]


def test_requests_without_list_header_use_first_list(client):
    """升级后的老客户端不带 X-List-Id：必须看到第一份清单里的数据。"""
    a = client.get("/api/lists").json()[0]
    _mk_item(client, a, "筒灯")
    _mk_list(client, "年货清单")

    assert len(client.get("/api/items").json()) == 1
    assert client.get("/api/summary").json()["totals"]["item_count"] == 1
    assert [r["name"] for r in client.get("/api/rooms").json()] == []


def test_unknown_list_header_is_rejected(client):
    r = client.get("/api/items", headers={"X-List-Id": "99999"})
    assert r.status_code == 404 and "清单不存在" in r.json()["detail"]
    r = client.get("/api/items", headers={"X-List-Id": "abc"})
    assert r.status_code == 400


def test_create_list_blank_by_default(client):
    a = client.get("/api/lists").json()[0]
    client.post("/api/rooms", json={"name": "客厅", "sort": 0}, headers=_hdr(a))
    created = client.post("/api/lists", json={"name": "空白清单"}).json()
    assert (created["room_count"], created["category_count"], created["item_count"]) == (0, 0, 0)


def test_create_list_can_copy_structure(client):
    """新建清单可以照抄另一份的分组与分类（不带物料）——省去重建十几个房间的功夫。"""
    a = client.get("/api/lists").json()[0]
    _mk_item(client, a, "筒灯")
    client.post("/api/rooms", json={"name": "客厅", "sort": 0}, headers=_hdr(a))
    client.post("/api/rooms", json={"name": "餐厅", "sort": 1}, headers=_hdr(a))
    client.post("/api/categories", json={"name": "照明", "sort": 0}, headers=_hdr(a))

    created = client.post("/api/lists",
                          json={"name": "二期", "copy_from": a["id"]}).json()
    assert created["room_count"] == 2 and created["category_count"] == 1
    assert created["item_count"] == 0  # 只搬结构，物料不带走

    rooms = client.get("/api/rooms", headers=_hdr(created)).json()
    assert [r["name"] for r in rooms] == ["客厅", "餐厅"]
    assert [c["name"] for c in client.get("/api/categories", headers=_hdr(created)).json()] == ["照明"]
    assert client.get("/api/items", headers=_hdr(created)).json() == []

    # 来源清单原样不动，而且两边的分组是各自独立的两行
    source_rooms = client.get("/api/rooms", headers=_hdr(a)).json()
    assert [r["name"] for r in source_rooms] == ["客厅", "餐厅"]
    assert not ({r["id"] for r in rooms} & {r["id"] for r in source_rooms})


def test_create_list_with_missing_source_is_rejected(client):
    r = client.post("/api/lists", json={"name": "抄不存在的", "copy_from": 99999})
    assert r.status_code == 400 and "不存在" in r.json()["detail"]
    assert [l["name"] for l in client.get("/api/lists").json()] == ["采购清单"]


def test_last_list_cannot_be_deleted(client):
    a = client.get("/api/lists").json()[0]
    r = client.delete(f"/api/lists/{a['id']}")
    assert r.status_code == 400 and "至少" in r.json()["detail"]


def test_deleting_list_removes_its_data_only(client):
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    item_a = _mk_item(client, a, "筒灯")
    room_a = client.post("/api/rooms", json={"name": "客厅", "sort": 0},
                         headers=_hdr(a)).json()
    client.put("/api/matrix/cell",
               json={"item_id": item_a["id"], "room_id": room_a["id"], "qty": 2},
               headers=_hdr(a))
    client.post(f"/api/items/{item_a['id']}/records", json={"qty": 1, "amount": 50},
                headers=_hdr(a))
    item_b = _mk_item(client, b, "糖果")

    assert client.delete(f"/api/lists/{a['id']}").status_code == 200

    session = SessionLocal()
    try:
        assert session.query(ItemList).count() == 1
        assert session.query(Item).count() == 1
        assert session.query(Item).one().name == "糖果"
        # 布点、采购记录、分组、分类都跟着清单走了
        assert session.query(Allocation).count() == 0
        assert session.query(PurchaseRecord).count() == 0
        assert session.query(Room).count() == 0
    finally:
        session.close()

    assert len(client.get("/api/items", headers=_hdr(b)).json()) == 1
    assert client.get(f"/api/items/{item_b['id']}", headers=_hdr(b)).status_code == 200


def test_export_and_import_are_per_list(client):
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    _mk_item(client, a, "筒灯")
    _mk_item(client, b, "糖果")

    exported = client.get("/api/export", headers=_hdr(a))
    assert exported.status_code == 200
    # 导出文件名带上清单名（清单名是中文，头里是百分号编码）
    assert "采购清单" in unquote(exported.headers["content-disposition"])

    # 把 A 的导出灌进 B（覆盖模式）：B 变成 A 的内容，A 不受影响
    r = client.post("/api/import",
                    files={"file": ("a.xlsx", io.BytesIO(exported.content),
                                    "application/vnd.openxmlformats-officedocument"
                                    ".spreadsheetml.sheet")},
                    data={"mode": "replace"}, headers=_hdr(b))
    assert r.status_code == 200, r.text
    assert r.json()["items_created"] == 1

    names_b = [i["name"] for i in client.get("/api/items", headers=_hdr(b)).json()]
    names_a = [i["name"] for i in client.get("/api/items", headers=_hdr(a)).json()]
    assert names_b == ["筒灯"]
    assert names_a == ["筒灯"]


def test_import_into_empty_list_keeps_duplicate_names(client):
    """把一份清单导出、合并进另一份清单时，同名物料不能被并成一条。

    目标清单原本是空的，导入时刚建出来的物料如果还参与"按名称匹配"，
    第二条同名物料就会并到第一条上（真实数据里有三条重名，会少 4 条）。
    """
    a = client.get("/api/lists").json()[0]
    b = _mk_list(client, "年货清单")
    one = _mk_item(client, a, "易来灯带控制器", qty_total=2, price=99)
    two = _mk_item(client, a, "易来灯带控制器", qty_total=1, price=99)
    assert one["id"] != two["id"]

    exported = client.get("/api/export", headers=_hdr(a)).content
    r = client.post("/api/import",
                    files={"file": ("a.xlsx", io.BytesIO(exported),
                                    "application/vnd.openxmlformats-officedocument"
                                    ".spreadsheetml.sheet")},
                    data={"mode": "merge"}, headers=_hdr(b))
    assert r.status_code == 200, r.text
    assert r.json()["items_created"] == 2

    rows = client.get("/api/items", headers=_hdr(b)).json()
    assert [i["name"] for i in rows].count("易来灯带控制器") == 2
    # 各自的明细跟着各自主人，没有被合并
    assert sorted(i["total_qty"] for i in rows) == [1, 2]

"""跨物料采购流水（GET /api/records）：一张台账看全部采购记录。

验证列表形状与排序、四个筛选条件、筛选后的合计，以及与物料详情
/ 软删 / 多清单隔离的口径一致。
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


def _two_items(client):
    """两个商家、两个物料、若干笔记录的常规场景。"""
    hdr = _hdr(client)
    a = client.post("/api/items", json={"name": "筒灯", "unit": "个", "price": 100},
                    headers=hdr).json()
    b = client.post("/api/items", json={"name": "乳胶漆", "unit": "桶", "price": 300},
                    headers=hdr).json()
    client.post(f"/api/items/{a['id']}/records",
                json={"qty": 2, "amount": 200, "date": "2026-09-01",
                      "vendor": "淘宝旗舰", "order_no": "A001"}, headers=hdr)
    client.post(f"/api/items/{b['id']}/records",
                json={"qty": 1, "amount": 300, "date": "2026-09-03",
                      "vendor": "京东自营", "order_no": "B002",
                      "note": "五升装两遍"}, headers=hdr)
    client.post(f"/api/items/{a['id']}/records",
                json={"qty": 0, "amount": -20, "date": "2026-09-05",
                      "vendor": "淘宝旗舰", "order_no": "A001",
                      "note": "价保退款"}, headers=hdr)
    return a, b


def test_lists_all_records_newest_first(client):
    a, b = _two_items(client)
    data = client.get("/api/records", headers=_hdr(client)).json()
    assert data["totals"] == {"count": 3, "qty": 3, "amount": 480}
    dates = [r["date"] for r in data["records"]]
    assert dates == ["2026-09-05", "2026-09-03", "2026-09-01"]
    # 每行带物料信息，字段与物料详情里的 records 一致
    first = data["records"][0]
    assert first["item_name"] == "筒灯" and first["unit"] == "个"
    assert first["amount"] == -20 and first["vendor"] == "淘宝旗舰"
    assert "room_ids" in first and "order_no" in first and "is_deposit" in first


def test_undated_records_sink_to_bottom(client):
    hdr = _hdr(client)
    a, b = _two_items(client)
    # POST 会把空日期补成今天，所以先带日期记录、再用改单接口把日期清空
    rec = client.post(f"/api/items/{a['id']}/records",
                      json={"qty": 1, "amount": 100, "date": "2026-08-01"},
                      headers=hdr).json()["records"][-1]
    cleared = client.put(f"/api/records/{rec['id']}", json={"date": ""}, headers=hdr)
    assert cleared.status_code == 200
    data = client.get("/api/records", headers=hdr).json()
    dates = [r["date"] for r in data["records"]]
    assert dates == ["2026-09-05", "2026-09-03", "2026-09-01", ""]
    assert data["totals"]["count"] == 4 and data["totals"]["amount"] == 580


def test_filter_by_date_range(client):
    _two_items(client)
    hdr = _hdr(client)
    data = client.get("/api/records", params={"date_from": "2026-09-02",
                                              "date_to": "2026-09-04"}, headers=hdr).json()
    assert [r["date"] for r in data["records"]] == ["2026-09-03"]
    assert data["totals"]["amount"] == 300
    # 开区间只用一头也行
    data = client.get("/api/records", params={"date_from": "2026-09-02"}, headers=hdr).json()
    assert [r["date"] for r in data["records"]] == ["2026-09-05", "2026-09-03"]


def test_filter_matches_dirty_dates_after_normalizing(client):
    """库里的脏日期（Excel str() 出来的带时间值）按归一化后的日期参与筛选。"""
    hdr = _hdr(client)
    a, _ = _two_items(client)
    session = SessionLocal()
    try:
        session.add(PurchaseRecord(item_id=a["id"], qty=1, amount=99,
                                   date="2026-09-14 00:00:00", vendor="线下店"))
        session.commit()
    finally:
        session.close()
    data = client.get("/api/records", params={"date_from": "2026-09-10",
                                              "date_to": "2026-09-14"}, headers=hdr).json()
    assert [r["amount"] for r in data["records"]] == [99]
    assert data["records"][0]["date"] == "2026-09-14"    # 读出口一并归一化


def test_filter_by_vendor_and_order_no(client):
    _two_items(client)
    hdr = _hdr(client)
    data = client.get("/api/records", params={"vendor": "淘宝"}, headers=hdr).json()
    assert data["totals"]["count"] == 2
    assert all(r["vendor"] == "淘宝旗舰" for r in data["records"])
    data = client.get("/api/records", params={"order_no": "B002"}, headers=hdr).json()
    assert data["totals"] == {"count": 1, "qty": 1, "amount": 300}


def test_filter_by_keyword_hits_item_name_note_vendor(client):
    a, b = _two_items(client)
    hdr = _hdr(client)
    # 关键词命中物料名
    data = client.get("/api/records", params={"q": "乳胶漆"}, headers=hdr).json()
    assert data["totals"]["count"] == 1
    # 命中备注
    data = client.get("/api/records", params={"q": "价保"}, headers=hdr).json()
    assert data["totals"]["count"] == 1 and data["records"][0]["amount"] == -20
    # 命中商家
    data = client.get("/api/records", params={"q": "京东"}, headers=hdr).json()
    assert data["totals"]["count"] == 1
    # 什么都不命中
    data = client.get("/api/records", params={"q": "不存在"}, headers=hdr).json()
    assert data["records"] == [] and data["totals"]["amount"] == 0


def test_conditions_can_stack(client):
    _two_items(client)
    hdr = _hdr(client)
    data = client.get("/api/records",
                      params={"vendor": "淘宝旗舰", "date_from": "2026-09-05"},
                      headers=hdr).json()
    assert data["totals"] == {"count": 1, "qty": 0, "amount": -20}


def test_trashed_item_records_are_hidden(client):
    a, b = _two_items(client)
    hdr = _hdr(client)
    client.delete(f"/api/items/{a['id']}", headers=hdr)
    data = client.get("/api/records", headers=hdr).json()
    assert data["totals"] == {"count": 1, "qty": 1, "amount": 300}
    assert data["records"][0]["item_name"] == "乳胶漆"


def test_lists_are_isolated(client):
    a, b = _two_items(client)
    other = client.post("/api/lists", json={"name": "年货清单"}).json()
    hdr2 = {"X-List-Id": str(other["id"])}
    c = client.post("/api/items", json={"name": "春联", "qty_total": 1, "price": 20},
                    headers=hdr2).json()
    client.post(f"/api/items/{c['id']}/records",
                json={"qty": 1, "amount": 88, "date": "2026-09-02",
                      "vendor": "淘宝旗舰"}, headers=hdr2)
    assert client.get("/api/records", headers=_hdr(client)).json()["totals"]["count"] == 3
    assert client.get("/api/records", headers=hdr2).json()["totals"] == {
        "count": 1, "qty": 1, "amount": 88}


def test_record_shape_matches_item_detail(client):
    """流水行的业务字段必须与物料详情的 records 一致（外加 item_name/unit）。

    created_at / updated_at 不比：详情走 pydantic 模型（ISO 带 T），流水是
    dict 直出由响应编码转文本（空格分隔），与 summary 的 unbought 同现状。
    """
    a, _ = _two_items(client)
    hdr = _hdr(client)
    detail = client.get(f"/api/items/{a['id']}", headers=hdr).json()["records"][0]
    row = next(r for r in client.get("/api/records", headers=hdr).json()["records"]
               if r["id"] == detail["id"])
    keys = [k for k in detail if k not in ("created_at", "updated_at")]
    assert set(row) - {"item_name", "unit"} == set(detail)
    assert {k: row[k] for k in keys} == {k: detail[k] for k in keys}

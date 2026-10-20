"""退款：负数采购记录的统计口径、定金回退、导出导入与审计文案。

退款不建新表：它就是一条 amount 为负的采购记录。这样已付合计、月度统计、
Excel 往返、同步搬运全都沿用现有路径，不会出现"某个地方忘了扣退款"的口径分叉。
"""

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, ExtraExpense, Item, ItemList,
                        OperationLog, PurchaseRecord, RecordRoom, Room, User)
from app.seed import init_db
from app.services import compute, excel_io, list_transfer
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    for table in (ExtraExpense, RecordRoom, Allocation, Item, PurchaseRecord,
                  OperationLog, Room, Category, User, ItemList):
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
    token = client.post("/api/auth/login",
                        json={"username": USER, "password": PASSWORD}).json()["token"]
    return {"Authorization": f"Bearer {token}"}


def _item(client, h):
    r = client.post("/api/items", json={
        "name": "壁挂炉", "unit": "台", "qty_total": 1, "price": 6999,
        "allocations": [],
    }, headers=h)
    assert r.status_code == 200, r.text
    return r.json()


def _rec(client, h, item_id, **body):
    r = client.post(f"/api/items/{item_id}/records", json=body, headers=h)
    assert r.status_code == 200, r.text
    return r.json()


def test_partial_refund_reduces_paid_but_not_progress(client):
    """价保退差价：已付扣回去，但东西已经买了，数量与状态都不倒退。"""
    h = _hdr(client)
    item = _item(client, h)
    _rec(client, h, item["id"], qty=1, amount=6999)
    out = _rec(client, h, item["id"], qty=0, amount=-999, note="价保退款")

    assert out["paid"] == 6000
    assert out["paid_qty"] == 1
    assert out["status"] == "done"
    assert out["unpaid"] == 0
    # 已付 + 实际优惠 + 未付 = 原价小计；退款让「实际优惠」跟着变大
    assert out["actual_discount"] == 999
    assert (out["actual_discount"] + out["paid"] + out["unpaid"]
            == out["list_total"])
    assert out["records"][-1]["amount"] == -999


def test_refunding_deposit_restores_the_unpaid_amount(client):
    """定金退掉后，未付里那笔定金抵扣也要退回，否则账会少算 1000。"""
    h = _hdr(client)
    item = _item(client, h)
    after_deposit = _rec(client, h, item["id"], qty=0, amount=1000,
                         is_deposit=True)
    assert after_deposit["status"] == "deposit"
    assert after_deposit["unpaid"] == 5999

    out = _rec(client, h, item["id"], qty=0, amount=-1000, is_deposit=True,
               note="退定")
    assert out["paid"] == 0
    assert out["unpaid"] == 6999
    assert out["status"] == "unbought"


def test_refund_shows_as_refund_in_audit_log(client):
    """日志里不能写成「买 0 台花 -999 元」，一眼要能看出是退款。"""
    h = _hdr(client)
    item = _item(client, h)
    _rec(client, h, item["id"], qty=1, amount=6999)
    _rec(client, h, item["id"], qty=0, amount=-999, note="价保退款")

    session = SessionLocal()
    try:
        log = (session.query(OperationLog)
               .filter(OperationLog.action.contains("退款"))
               .order_by(OperationLog.id.desc()).first())
        assert log is not None, "操作日志里应有一条退款"
        assert "退款 999 元" in log.action
    finally:
        session.close()


def test_excel_roundtrip_keeps_refund(client):
    """导出再导回：负数金额原样保留，已付净额不变。"""
    h = _hdr(client)
    item = _item(client, h)
    _rec(client, h, item["id"], qty=1, amount=6999, date="2026-10-01",
         vendor="京东", order_no="A1")
    _rec(client, h, item["id"], qty=0, amount=-999, date="2026-10-09",
         vendor="京东", order_no="A1", note="价保退款")

    session = SessionLocal()
    try:
        lst = session.query(ItemList).order_by(ItemList.id).first()
        data = excel_io.export_xlsx(session, lst.id)
        report = excel_io.import_template(session, data, mode="replace",
                                          list_id=lst.id)
        assert report.get("warnings") == [], report.get("warnings")
        session.expire_all()
        row = session.query(Item).one()
        assert compute.item_paid(row) == 6000
        assert sorted(r.amount for r in row.records) == [-999, 6999]
    finally:
        session.close()


def test_sync_roundtrip_keeps_refund(client):
    """同步搬运同样只是搬运：负数金额不能在路上被清洗掉。"""
    h = _hdr(client)
    item = _item(client, h)
    _rec(client, h, item["id"], qty=1, amount=6999)
    _rec(client, h, item["id"], qty=0, amount=-999, note="价保退款")

    session = SessionLocal()
    try:
        lst = session.query(ItemList).order_by(ItemList.id).first()
        payload = list_transfer.export_list(session, lst)
        amounts = [r["amount"] for it in payload["items"] for r in it["records"]]
        assert -999 in amounts

        list_transfer.import_list(session, payload, target=lst)
        session.expire_all()
        row = session.query(Item).one()
        assert compute.item_paid(row) == 6000
    finally:
        session.close()


def test_summary_paid_total_deducts_refunds(client):
    h = _hdr(client)
    item = _item(client, h)
    _rec(client, h, item["id"], qty=1, amount=6999)
    _rec(client, h, item["id"], qty=0, amount=-999)

    data = client.get("/api/summary", headers=h).json()
    assert data["totals"]["paid_total"] == 6000

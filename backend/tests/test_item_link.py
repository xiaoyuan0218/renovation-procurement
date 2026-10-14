"""商品链接：字段往返、Excel 往返，以及"老文件/老客户端不带这一列时不清空"。

链接是 1.2.5 新加的字段。与定金那次同样的坑：整份覆盖的路径（同步落地、表格
导入）在对方"没带这个字段"时，不能当成"要清空" —— 否则老客户端推一次、老表格
导一份，别人填好的链接就没了。
"""

import io

import openpyxl
import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, ExtraExpense, Item, ItemList,
                        PurchaseRecord, RecordRoom, Room, User)
from app.seed import init_db
from app.services import excel_io, list_transfer
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER

LINK = "https://item.jd.com/100012043978.html"


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    for table in (ExtraExpense, RecordRoom, Allocation, Item, PurchaseRecord,
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


def _hdr(client):
    token = client.post("/api/auth/login",
                        json={"username": USER, "password": PASSWORD}).json()["token"]
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture()
def db():
    init_db()
    session = SessionLocal()
    for table in (RecordRoom, Allocation, PurchaseRecord, Item, Room, Category):
        session.query(table).delete()
    session.commit()
    yield session
    session.close()
    engine.dispose()


@pytest.fixture()
def item(db):
    lst = db.query(ItemList).order_by(ItemList.sort, ItemList.id).first()
    row = Item(name="壁挂炉", unit="台", qty_total=1, price=6999,
               discount_price=5099, list_id=lst.id)
    db.add(row)
    db.commit()
    return row


def test_item_link_roundtrip(client):
    """建物料带链接、读回来还在；换一个再存也跟着变。"""
    h = _hdr(client)
    created = client.post("/api/items", json={
        "name": "小米电视", "unit": "台", "qty_total": 1, "price": 2999,
        "link": LINK, "allocations": [],
    }, headers=h).json()
    assert created["link"] == LINK

    fetched = client.get(f"/api/items/{created['id']}", headers=h).json()
    assert fetched["link"] == LINK

    saved = client.put(f"/api/items/{created['id']}", json={
        "name": "小米电视", "unit": "台", "qty_total": 1, "price": 2999,
        "link": "https://item.taobao.com/item.htm?id=123", "allocations": [],
        "base_rev": fetched["rev"],
    }, headers=h).json()
    assert saved["link"] == "https://item.taobao.com/item.htm?id=123"


def test_link_only_accepts_http_and_https(client):
    """javascript: 之类的伪协议一律清空 —— 它最终会被渲染成可点链接。"""
    h = _hdr(client)
    created = client.post("/api/items", json={
        "name": "危险链接", "unit": "台", "qty_total": 1, "price": 1,
        "link": "javascript:alert(1)", "allocations": [],
    }, headers=h).json()
    assert created["link"] == ""
    assert client.get(f"/api/items/{created['id']}", headers=h).json()["link"] == ""


def test_patch_link_also_only_accepts_http_and_https(client):
    """行内改动（PATCH）走同一条伪协议收口：它同样会存库、被渲染成可点链接。"""
    h = _hdr(client)
    created = client.post("/api/items", json={
        "name": "行内改", "unit": "台", "qty_total": 1, "price": 1, "allocations": [],
    }, headers=h).json()

    cleaned = client.patch(f"/api/items/{created['id']}",
                           json={"link": "javascript:alert(1)"}, headers=h)
    assert cleaned.status_code == 200, cleaned.text
    assert cleaned.json()["link"] == ""

    ok = client.patch(f"/api/items/{created['id']}", json={"link": LINK}, headers=h)
    assert ok.json()["link"] == LINK


def test_excel_roundtrip_keeps_link(db, item):
    """导出带「商品链接」列，紧接着导回来还在。"""
    item.link = LINK
    db.commit()
    data = excel_io.export_xlsx(db, item.list_id)
    wb = openpyxl.load_workbook(io.BytesIO(data))
    ws = wb["物料汇总"]
    header = [c.value for c in ws[1]]
    assert "商品链接" in header
    assert ws.cell(2, header.index("商品链接") + 1).value == LINK

    excel_io.import_template(db, data, mode="replace", list_id=item.list_id)
    db.expire_all()
    assert db.query(Item).one().link == LINK


def test_old_excel_without_link_column_keeps_existing(db, item):
    """老表格（没有「商品链接」列）导回来时，不清空已填的链接。"""
    item.link = LINK
    db.commit()
    data = excel_io.export_xlsx(db, item.list_id)
    wb = openpyxl.load_workbook(io.BytesIO(data))
    ws = wb["物料汇总"]
    header = [c.value for c in ws[1]]
    ws.delete_cols(header.index("商品链接") + 1)
    buf = io.BytesIO()
    wb.save(buf)

    report = excel_io.import_template(db, buf.getvalue(), mode="replace",
                                      list_id=item.list_id)
    assert report.get("warnings") == [], report.get("warnings")
    db.expire_all()
    assert db.query(Item).one().link == LINK


def test_old_client_push_keeps_link(db, item):
    """老客户端推的 payload 里没有 link 字段：按物料名保留已填的链接。"""
    item.link = LINK
    db.commit()
    lst = db.query(ItemList).filter(ItemList.id == item.list_id).one()
    payload = list_transfer.export_list(db, lst)
    for row in payload["items"]:
        row.pop("link", None)

    list_transfer.import_list(db, payload, target=lst)

    db.expire_all()
    assert db.query(Item).one().link == LINK


def test_new_client_push_can_clear_link(db, item):
    """新客户端明确带空串时照清 —— 兼容层只兜"没带"，不拦用户主动清空。"""
    item.link = LINK
    db.commit()
    lst = db.query(ItemList).filter(ItemList.id == item.list_id).one()
    payload = list_transfer.export_list(db, lst)
    for row in payload["items"]:
        row["link"] = ""

    list_transfer.import_list(db, payload, target=lst)

    db.expire_all()
    assert db.query(Item).one().link == ""

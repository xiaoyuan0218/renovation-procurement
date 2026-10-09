"""兼容层回归：缺 is_deposit 的老客户端/老文件覆盖时，不能把定金抹平。

定金标记是 1.2.2 才加的：老客户端（≤1.2.1）推送的 payload 里没有这个字段、
老版本导出的表格里没有这一列。整份覆盖（同步、以我为准、导入）时如果把
"没带这个信息"当成"不是定金"，一次就把整份清单的标记清零 —— 用户看到的
是"升级之后勾的定金又没了"。这组用例盯的就是这几条覆盖路径。
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


def _add(db, item, qty, amount, deposit=False):
    item.records.append(PurchaseRecord(qty=qty, amount=amount, is_deposit=deposit))
    db.commit()
    db.refresh(item)


def _strip_deposit(payload: dict) -> dict:
    """模拟老客户端：payload 里根本没有 is_deposit 这个键。"""
    for row in payload.get("items", []):
        for rec in row.get("records", []):
            rec.pop("is_deposit", None)
    return payload


def test_old_client_push_keeps_deposit(db, item):
    """老客户端整份覆盖（同步落库）时，按业务键沿用覆盖前的定金标记。"""
    lst = db.query(ItemList).filter(ItemList.id == item.list_id).one()
    _add(db, item, 0, 1000, deposit=True)

    payload = _strip_deposit(list_transfer.export_list(db, lst))
    list_transfer.import_list(db, payload, target=lst)

    db.expire_all()
    assert db.query(PurchaseRecord).one().is_deposit is True


def test_new_client_push_can_clear_deposit(db, item):
    """新客户端明确说"这条不是定金"时照改 —— 兼容层只兜"没带信息"，不拦明确操作。"""
    lst = db.query(ItemList).filter(ItemList.id == item.list_id).one()
    _add(db, item, 0, 1000, deposit=True)

    payload = list_transfer.export_list(db, lst)
    for row in payload["items"]:
        for rec in row["records"]:
            rec["is_deposit"] = False
    list_transfer.import_list(db, payload, target=lst)

    db.expire_all()
    assert db.query(PurchaseRecord).one().is_deposit is False


def test_old_excel_file_keeps_existing_deposit(db, item):
    """老表格（没有「定金」列）导回来时，清单里已有的定金标记要留着。"""
    _add(db, item, 0, 1000, deposit=True)
    data = excel_io.export_xlsx(db, item.list_id)
    wb = openpyxl.load_workbook(io.BytesIO(data))
    ws = wb["采购记录"]
    header = [c.value for c in ws[1]]
    ws.delete_cols(header.index("定金") + 1)
    buf = io.BytesIO()
    wb.save(buf)

    report = excel_io.import_template(db, buf.getvalue(), mode="replace",
                                      list_id=item.list_id)
    assert report.get("warnings") == [], report.get("warnings")
    db.expire_all()
    assert db.query(PurchaseRecord).one().is_deposit is True


def test_desktop_apply_keeps_deposit_when_payload_has_no_field(db, item):
    """桌面端落地：服务器（老版）的 payload 没带定金字时，本地旧值不能被抹掉。"""
    from app.services import local_apply, record_keys
    lst = db.query(ItemList).filter(ItemList.id == item.list_id).one()
    _add(db, item, 0, 1000, deposit=True)

    payload = _strip_deposit(list_transfer.export_list(db, lst))
    # desktop_sync._adopt 走的就是这两步：先回填，再整份落地
    record_keys.fill_missing_deposit(db, lst.id, payload)
    local_apply.apply_payload(db, lst, payload)

    db.expire_all()
    assert db.query(PurchaseRecord).one().is_deposit is True


def test_sync_http_with_old_payload_keeps_deposit(client):
    """走接口的老客户端推送（body 里没有 is_deposit 字段）同样不能抹平。"""
    h = _hdr(client)
    created = client.post("/api/items", json={
        "name": "老客户端推的", "unit": "台", "qty_total": 1, "price": 6999,
        "allocations": [],
        "records": [{"qty": 0, "amount": 1000, "is_deposit": True, "room_ids": []}],
    }, headers=h).json()
    assert created["records"][0]["is_deposit"] is True

    lists = client.get("/api/lists", headers=h).json()
    list_id = lists[0]["id"]
    snap = client.get(f"/api/sync/lists/{list_id}", headers=h).json()
    payload = _strip_deposit(snap["payload"])

    r = client.put(f"/api/sync/lists/{list_id}",
                   json={"base_fingerprint": snap["fingerprint"], **payload},
                   headers=h)
    assert r.status_code == 200, r.text
    assert r.json()["payload"]["items"][0]["records"][0]["is_deposit"] is True

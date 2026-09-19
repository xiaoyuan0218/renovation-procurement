"""付款日期：写进去要规范，读出来要能容忍历史脏值。

以前日期是自由文本，Excel 的日期单元格会被 str 成 "2026-09-14 00:00:00" 存进库，
而且谁也不校验。现在入口收口成 YYYY-MM-DD，老脏值不改库、只在读的时候归一化。
"""

import datetime
import io

import openpyxl
import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, Item, ItemList, PurchaseRecord,
                        Room, User)
from app.seed import init_db
from app.services import dates, excel_io
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER


# ---------------------------------------------------------------- 纯函数

def test_for_read_normalizes_excel_datetime():
    assert dates.for_read("2026-09-14 00:00:00") == "2026-09-14"
    assert dates.for_read("2026-09-14T08:30:00") == "2026-09-14"
    assert dates.for_read(datetime.datetime(2026, 9, 14, 8, 30)) == "2026-09-14"
    assert dates.for_read(datetime.date(2026, 9, 14)) == "2026-09-14"


def test_for_read_accepts_loose_forms():
    assert dates.for_read("2026/9/4") == "2026-09-04"
    assert dates.for_read("2026.09.04") == "2026-09-04"
    assert dates.for_read(" 2026-09-14 ") == "2026-09-14"


def test_for_read_keeps_unrecognized_text():
    """认不出来的原样返回：宁可界面上看着怪，也不要悄悄把用户填的东西抹掉。"""
    assert dates.for_read("上周三") == "上周三"
    assert dates.for_read("2026-13-45") == "2026-13-45"
    assert dates.for_read("") == ""
    assert dates.for_read(None) == ""


def test_clean_rejects_impossible_dates():
    assert dates.clean("2026-09-14") == "2026-09-14"
    assert dates.clean("2026/9/4") == "2026-09-04"
    assert dates.clean("") == ""
    assert dates.clean(None) == ""
    for bad in ("2026-02-31", "2026-13-01", "上周三", "9月14日"):
        with pytest.raises(ValueError):
            dates.clean(bad)


def test_month_of():
    assert dates.month_of("2026-09-14") == "2026-09"
    assert dates.month_of("2026-09-14 00:00:00") == "2026-09"
    assert dates.month_of("") is None
    assert dates.month_of("上周三") is None


# ---------------------------------------------------------------- 接口层

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


def _item(client):
    return client.post("/api/items", json={"name": "筒灯", "unit": "个",
                                           "qty_total": 4, "price": 99}).json()


def test_api_normalizes_and_rejects_dates(client):
    item = _item(client)
    ok = client.post(f"/api/items/{item['id']}/records",
                     json={"qty": 1, "amount": 99, "date": "2026/9/4"})
    assert ok.status_code == 200
    assert ok.json()["records"][0]["date"] == "2026-09-04"

    bad = client.post(f"/api/items/{item['id']}/records",
                      json={"qty": 1, "amount": 99, "date": "上周三"})
    assert bad.status_code == 422

    rec_id = ok.json()["records"][0]["id"]
    bad_patch = client.put(f"/api/records/{rec_id}", json={"date": "9月14日"})
    assert bad_patch.status_code == 422
    # 拒绝之后原来那条还是好的
    kept = client.get(f"/api/items/{item['id']}").json()["records"][0]
    assert kept["date"] == "2026-09-04"


def test_reading_old_dirty_date_is_normalized(client):
    """老数据里存着带时间的脏值：不改库，但接口吐出来的已经是干净的日期。"""
    item = _item(client)
    session = SessionLocal()
    try:
        session.add(PurchaseRecord(item_id=item["id"], qty=1, amount=99,
                                   date="2026-09-14 00:00:00"))
        session.commit()
    finally:
        session.close()

    got = client.get(f"/api/items/{item['id']}").json()
    assert got["records"][0]["date"] == "2026-09-14"

    # 库里那个值没被动过
    session = SessionLocal()
    try:
        raw = session.query(PurchaseRecord).one()
        assert raw.date == "2026-09-14 00:00:00"
    finally:
        session.close()


@pytest.fixture()
def db():
    init_db()
    session = SessionLocal()
    for table in (Allocation, Item, PurchaseRecord, Room, Category, ItemList):
        session.query(table).delete()
    session.commit()
    session.add(ItemList(name="采购清单", sort=0))
    session.commit()
    yield session
    session.close()
    engine.dispose()


def test_excel_import_normalizes_date_cells(db):
    """Excel 里写的是真日期格式时，导入后存的必须是 YYYY-MM-DD。"""
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "物料汇总"
    ws.append(["类目", "物料名称", "单位", "数量", "单价", "优惠单价",
               "日常价", "实付数量", "实付金额", "未付数量", "未付金额",
               "已购", "备注"])
    ws.append(["照明", "筒灯", "个", 2, 99, None, None, None, None, None,
               None, "否", ""])
    ws3 = wb.create_sheet("采购记录")
    ws3.append(["物料名称", "实付数量", "实付金额", "付款日期", "备注"])
    ws3.append(["筒灯", 2, 198, datetime.datetime(2026, 9, 14, 0, 0), "第一批"])
    buf = io.BytesIO()
    wb.save(buf)

    excel_io.import_template(db, buf.getvalue(), mode="replace")

    db.expire_all()
    assert db.query(PurchaseRecord).one().date == "2026-09-14"

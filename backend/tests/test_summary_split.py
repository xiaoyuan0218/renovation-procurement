"""总览接口的三段拆分：聚合之后两个等式仍须严格成立。

单条物料的等式在 test_compute.py 里逐项验证，这里验证接口的求和环节
没有把残差式定义算丢（聚合必须等于各项相加，不能各算一遍再取整）。
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


def _totals(client):
    return client.get("/api/summary").json()["totals"]


def test_totals_split_identities_on_mixed_data(client):
    """混合数据：房间覆盖价 + 部分付款 + 无日常单价 + 买超，聚合等式都要成立。"""
    session = SessionLocal()
    room = Room(name="客厅", sort=0)
    session.add(room)
    session.flush()

    # 有覆盖价 + 部分付款
    a = Item(name="吊灯", price=100, discount_price=80)
    session.add(a)
    session.flush()
    session.add(Allocation(item_id=a.id, room_id=room.id, qty=2, price_override=150))
    a.records.append(PurchaseRecord(qty=1, amount=120))

    # 无日常单价
    b = Item(name="门锁", qty_total=1, price=2899)
    session.add(b)
    session.flush()
    b.records.append(PurchaseRecord(qty=1, amount=1547.3))

    # 买超：实付数量 5 超过总量 4，未付按下限 0 计
    c = Item(name="买多了", qty_total=4, price=10, discount_price=8)
    session.add(c)
    session.flush()
    c.records.append(PurchaseRecord(qty=5, amount=15))

    session.commit()
    session.close()

    t = _totals(client)
    assert t["list_total"] == 300 + 2899 + 40
    assert t["discount_total"] == 160 + 2899 + 32
    assert t["paid_total"] == 120 + 1547.3 + 15
    assert t["unpaid_total"] == 100 + 0 + 0        # 买超那条未付为 0
    assert t["daily_unpaid_total"] == 80 + 0 + 0
    # 聚合等式：这两条是本次改动的核心不变量
    assert round(t["paid_total"] + t["actual_discount_total"] + t["unpaid_total"], 2) == t["list_total"]
    assert round(t["paid_total"] + t["daily_discount_total"] + t["daily_unpaid_total"], 2) == t["discount_total"]


def test_negative_discount_survives_aggregation(client):
    """整库只有溢价付款时，聚合的日常价优惠是负数，不能被夹到 0。"""
    session = SessionLocal()
    item = Item(name="溢价件", qty_total=4, price=10, discount_price=8)
    session.add(item)
    session.flush()
    item.records.append(PurchaseRecord(qty=1, amount=15))
    session.commit()
    session.close()

    t = _totals(client)
    assert t["actual_discount_total"] == -5   # 40 − 15 − 30
    assert t["daily_discount_total"] == -7    # 32 − 15 − 24
    assert t["daily_unpaid_total"] == 24
    assert round(t["paid_total"] + t["daily_discount_total"] + t["daily_unpaid_total"], 2) == t["discount_total"]


def test_by_month_groups_paid_by_payment_date(client):
    """按月已付：付款记录里的日期终于有用了 ——「这个月花了多少」直接聚合出来。"""
    session = SessionLocal()
    room = Room(name="客厅", sort=0)
    session.add(room)
    session.flush()
    item = Item(name="筒灯", price=100)
    session.add(item)
    session.flush()
    session.add(Allocation(item_id=item.id, room_id=room.id, qty=5))
    item.records.append(PurchaseRecord(qty=1, amount=100, date="2026-08-20"))
    item.records.append(PurchaseRecord(qty=1, amount=150, date="2026-09-01"))
    # 老数据里从 Excel 存进来的脏值，也要能归到正确的月份
    item.records.append(PurchaseRecord(qty=1, amount=50, date="2026-09-14 00:00:00"))
    item.records.append(PurchaseRecord(qty=1, amount=30, date=""))
    session.commit()
    session.close()

    data = client.get("/api/summary").json()
    assert data["by_month"] == [
        {"month": "2026-08", "paid": 100.0},
        {"month": "2026-09", "paid": 200.0},
    ]
    assert data["by_month_undated"] == 30.0


def test_empty_db_split_is_zero_not_null(client):
    """空库：三个新字段是 0，不是 null —— 前端直接渲染，不做兜底。"""
    t = _totals(client)
    assert t["actual_discount_total"] == 0
    assert t["daily_discount_total"] == 0
    assert t["daily_unpaid_total"] == 0

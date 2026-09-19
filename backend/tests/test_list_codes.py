"""清单编号：改名字、重名都不影响它，两端靠它对认。

手机上传时带着自己的编号上来，服务器沿用它 —— 这样"手机上这份"和"服务器上那份"
编号相同，用户一眼能确认是哪一份（名字会改、也允许重名，编号不会）。
"""

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, ExtraExpense, Item, ItemList,
                        PurchaseRecord, RecordRoom, Room, User)
from app.seed import init_db
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER

VALID = set("ABCDEFGHJKMNPQRSTUVWXYZ23456789")


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


def test_every_list_has_a_readable_code(client):
    a = client.post("/api/lists", json={"name": "装修采购"}).json()
    b = client.post("/api/lists", json={"name": "年货"}).json()

    for item in (a, b):
        assert len(item["code"]) == 8
        # 字母表里没有 0/O/1/I/L —— 这串码要拿眼睛对、甚至口头念
        assert set(item["code"]) <= VALID, item["code"]
    assert a["code"] != b["code"]


def test_upload_keeps_the_code_from_the_phone(client):
    """手机带着自己的编号上传，服务器沿用它（新建一份也一样）。"""
    created = client.post("/api/lists", json={"name": "装修采购", "code": "K7M2P4QX"})
    assert created.json()["code"] == "K7M2P4QX"


def test_conflicting_code_gets_reissued(client):
    first = client.post("/api/lists", json={"name": "甲", "code": "K7M2P4QX"}).json()
    second = client.post("/api/lists", json={"name": "乙", "code": "K7M2P4QX"}).json()

    assert first["code"] == "K7M2P4QX"
    assert second["code"] != "K7M2P4QX"
    assert len(second["code"]) == 8


def test_malformed_code_is_replaced(client):
    bad = client.post("/api/lists", json={"name": "丙", "code": "不是编号"}).json()
    assert len(bad["code"]) == 8
    assert set(bad["code"]) <= VALID


def test_code_travels_with_the_payload_and_comes_back(client):
    """手机上传时编号跟着 payload 上来；之后覆盖同一份，编号保持不动。"""
    payload = {
        "list": {"name": "装修采购", "code": "K7M2P4QX"},
        "rooms": [], "categories": [], "items": [], "expenses": [],
    }
    created = client.post("/api/sync/lists", json=payload).json()
    assert created["payload"]["list"]["code"] == "K7M2P4QX", "新建时沿用手机带来的编号"

    list_id = created["list_id"]
    header = {"X-List-Id": str(list_id)}
    client.post("/api/items", json={"name": "筒灯", "qty_total": 2, "price": 30},
                headers=header)

    snapshot = client.get(f"/api/sync/lists/{list_id}", headers=header).json()
    assert snapshot["payload"]["list"]["code"] == "K7M2P4QX"

    # 覆盖回去（force）：编号不该变，否则手机那边就认不出这份清单了
    pushed = client.put(f"/api/sync/lists/{list_id}",
                        json={**snapshot["payload"], "force": True},
                        headers=header).json()
    assert pushed["payload"]["list"]["code"] == "K7M2P4QX"

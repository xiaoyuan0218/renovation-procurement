"""操作日志与回退的接口级测试。

验证三件事：写操作都留下中文日志且分得清人与 API 密钥；常用三类操作能
按快照回退（布点与采购记录跟着一起回来）；「只撤最近一条」的约束不被
绕过。"""

import os

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import (Allocation, ApiKey, ExtraExpense, Item, ItemList,
                        OperationLog, PurchaseRecord, RecordRoom, User)
from app.seed import init_db

USER = os.environ.get("TEST_AUTH_USER", "admin")
# 测试用的假密码，只在测试库里创建账号，不是任何真实环境的凭据
PASSWORD = os.environ.get("TEST_AUTH_PASSWORD", "test-only-pass")


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    for table in (RecordRoom, OperationLog, ApiKey, PurchaseRecord,
                  Allocation, ExtraExpense, Item, User, ItemList):
        session.query(table).delete()
    session.commit()
    session.close()
    auth._failures.clear()
    with TestClient(app) as c:
        c.post("/api/auth/setup", json={"username": USER, "password": PASSWORD})
        yield c
    engine.dispose()


def _create_item(client, name="筒灯", **extra):
    r = client.post("/api/items", json={
        "name": name, "qty_total": 10, "price": 88, **extra})
    assert r.status_code == 200, r.text
    return r.json()


def _top_log(client, headers=None) -> dict:
    items = client.get("/api/logs", headers=headers or {}).json()["items"]
    return items[0] if items else {}


# ---------------------------------------------------------------- 记录

def test_write_leaves_chinese_log(client):
    _create_item(client, "筒灯")
    top = _top_log(client)
    assert "新增物料" in top["action"]
    assert "筒灯" in top["action"]
    assert top["actor_kind"] == "human"
    assert "admin" in top["actor_name"]
    assert top["status_code"] == 200


def test_api_key_actor_is_named(client):
    key = client.post("/api/keys", json={"name": "快捷指令"}).json()["key"]
    client.cookies.clear()
    r = client.post("/api/items", headers={"X-API-Key": key},
                    json={"name": "程序建的", "qty_total": 1, "price": 1})
    assert r.status_code == 200

    top = _top_log(client, {"X-API-Key": key})
    assert top["actor_kind"] == "api"
    assert "快捷指令" in top["actor_name"]


def test_rejected_request_is_logged(client):
    # 不存在的物料：404 也算「有人试过」，要留痕
    r = client.patch("/api/items/999", json={"price": 1})
    assert r.status_code == 404
    top = _top_log(client)
    assert top["status_code"] == 404
    assert "修改物料" in top["action"]


def test_login_does_not_block_undo(client):
    _create_item(client, "筒灯")
    # 中间隔一次重新登录：登录不碰业务数据，不该挡住回退
    client.post("/api/auth/login", json={"username": USER, "password": PASSWORD})
    logs = client.get("/api/logs").json()
    assert logs["undoable_id"] is not None


# ---------------------------------------------------------------- 回退：物料

def test_undo_create_removes_item(client):
    item = _create_item(client, "筒灯")
    top = _top_log(client)
    assert top["can_undo"]
    r = client.post(f"/api/logs/{top['id']}/undo")
    assert r.status_code == 200, r.text
    assert client.get(f"/api/items/{item['id']}").status_code == 404


def test_undo_update_restores_everything(client):
    item = _create_item(client, "筒灯")
    room = client.post("/api/rooms", json={"name": "客厅"}).json()
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 2, "amount": 168, "date": "2026-10-07",
                      "room_ids": [room["id"]]})
    # 改名字与单价：最近一条变成「修改物料」
    r = client.put(f"/api/items/{item['id']}",
                   json={"name": "筒灯改名", "qty_total": 10, "price": 99,
                         "unit": "个"})
    assert r.status_code == 200
    assert client.get("/api/logs").json()["items"][0]["can_undo"]

    top = _top_log(client)
    assert client.post(f"/api/logs/{top['id']}/undo").status_code == 200

    after = client.get(f"/api/items/{item['id']}").json()
    assert after["name"] == "筒灯"           # 名字回到改之前
    assert after["price"] == 88
    assert after["paid"] == 168              # 记的那笔账不受这次回退影响
    assert len(after["records"]) == 1
    assert after["records"][0]["room_ids"] == [room["id"]]


def test_undo_delete_brings_item_back_with_children(client):
    item = _create_item(client, "筒灯")
    room = client.post("/api/rooms", json={"name": "卧室"}).json()
    client.put(f"/api/items/{item['id']}",
               json={"name": "筒灯", "qty_total": 10, "price": 88,
                     "unit": "个", "allocations": [
                         {"room_id": room["id"], "qty": 10}]})
    assert client.delete(f"/api/items/{item['id']}").status_code == 200
    assert client.get(f"/api/items/{item['id']}").status_code == 404

    top = _top_log(client)
    assert client.post(f"/api/logs/{top['id']}/undo").status_code == 200

    after = client.get(f"/api/items/{item['id']}").json()
    assert after["id"] == item["id"]                    # id 不变，引用不断
    assert after["name"] == "筒灯"
    assert [(a["room_id"], a["qty"]) for a in after["allocations"]] == [
        (room["id"], 10)]
    assert after["rev"] > item["rev"]                   # 让同步感知这次回退


def test_undo_add_record_then_create(client):
    """连着撤两步：先撤记账，再撤新增 —— 回退本身不该挡住下一次回退。"""
    item = _create_item(client, "筒灯")
    client.post(f"/api/items/{item['id']}/records",
                json={"qty": 2, "amount": 168, "date": "2026-10-07"})

    first = _top_log(client)
    assert "记一笔采购" in first["action"]
    assert client.post(f"/api/logs/{first['id']}/undo").status_code == 200
    assert client.get(f"/api/items/{item['id']}").json()["records"] == []

    second = _top_log(client)
    assert "回退" in second["action"]
    assert not second["can_undo"]          # 回退这条本身不可再回退
    assert second["id"] != first["id"]
    # 被回退的那条与更早的「新增物料」都还在日志里（只是不再能回退）；
    # 新增物料这条跳过回退日志后仍可回退
    earlier = next(x for x in client.get("/api/logs").json()["items"]
                   if "新增物料" in x["action"])
    assert earlier["can_undo"]
    assert client.post(f"/api/logs/{earlier['id']}/undo").status_code == 200
    assert client.get(f"/api/items/{item['id']}").status_code == 404


def test_blocked_after_batch_delete(client):
    """批量删除之后，更早的可回退操作要被挡住 —— 中间夹了改数据的动作。"""
    item = _create_item(client, "筒灯")
    other = _create_item(client, "另一条")
    r = client.post("/api/items/batch/delete", json={"ids": [item["id"], other["id"]]})
    assert r.status_code == 200
    logs = client.get("/api/logs").json()
    assert logs["undoable_id"] is None
    assert logs["items"][0]["can_undo"] is False


# ---------------------------------------------------------------- 回退：费用

def test_undo_expense_create_and_update(client):
    fee = client.post("/api/expenses",
                      json={"kind": "运费", "amount": 45,
                            "date": "2026-10-07"}).json()
    assert fee["kind"] == "运费"

    top = _top_log(client)
    assert client.post(f"/api/logs/{top['id']}/undo").status_code == 200
    assert client.get("/api/expenses").json() == []

    # 重新记一笔、改金额，再回退：金额回到 45
    fee2 = client.post("/api/expenses",
                       json={"kind": "运费", "amount": 45,
                             "date": "2026-10-07"}).json()
    client.put(f"/api/expenses/{fee2['id']}",
               json={"kind": "运费", "amount": 99, "date": "2026-10-07"})
    top = _top_log(client)
    assert client.post(f"/api/logs/{top['id']}/undo").status_code == 200
    rows = client.get("/api/expenses").json()
    assert rows[0]["amount"] == 45


# ---------------------------------------------------------------- 筛选

def test_filters(client):
    _create_item(client, "筒灯")
    client.post("/api/keys", json={"name": "快捷指令"})
    key = client.post("/api/keys", json={"name": "脚本"}).json()["key"]
    client.cookies.clear()
    client.post("/api/items", headers={"X-API-Key": key},
                json={"name": "程序件", "qty_total": 1, "price": 1})

    def actions(**params):
        rows = client.get("/api/logs", params=params,
                          headers={"X-API-Key": key}).json()["items"]
        return [x["action"] for x in rows]

    # 关键词：动作描述里带着物料名，搜名字就能翻出它的全部操作
    lamp = actions(q="筒灯")
    assert lamp and all("筒灯" in a for a in lamp)

    # 来源
    api_rows = client.get("/api/logs", params={"source": "api"},
                          headers={"X-API-Key": key}).json()["items"]
    assert api_rows and all(x["actor_kind"] == "api" for x in api_rows)
    human_rows = client.get("/api/logs", params={"source": "human"},
                            headers={"X-API-Key": key}).json()["items"]
    assert human_rows and all(x["actor_kind"] == "human" for x in human_rows)

    # 类别：密钥相关的只有两条生成日志
    key_rows = client.get("/api/logs", params={"category": "key"},
                          headers={"X-API-Key": key}).json()["items"]
    assert key_rows and all("API 密钥" in x["action"] for x in key_rows)

    # 失败：伪造一次 404 后只看未成功的；不带 failed 时全部都返回
    client.patch("/api/items/999", json={"price": 1}, headers={"X-API-Key": key})
    failed_rows = client.get("/api/logs", params={"failed": "true"},
                             headers={"X-API-Key": key}).json()["items"]
    assert failed_rows and all(x["status_code"] >= 400 for x in failed_rows)
    all_rows = client.get("/api/logs", headers={"X-API-Key": key}).json()["items"]
    assert any(x["status_code"] >= 400 for x in all_rows)


# ---------------------------------------------------------------- 保留上限

def test_trim_keeps_recent_logs(client):
    from app.models import OperationLog as Log
    session = SessionLocal()
    session.add_all([OperationLog(
        actor_kind="human", actor_name="预置", action=f"历史 {i}",
        method="POST", path="/api/none", status_code=200) for i in range(2100)])
    session.commit()
    session.close()

    _create_item(client, "触发裁剪")
    count = SessionLocal().query(Log.id).count()
    assert count <= 2000
    # 最新的一条还在，没有被裁掉
    assert "触发裁剪" in _top_log(client)["action"]

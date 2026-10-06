"""API 密钥的接口级测试。

密钥是给外部程序用的长期凭据，这里验的是它真能替代登录：能生成、能调
业务接口、撤销后立刻失效，同时不打扰原有的 Cookie 与 Bearer 登录。

关键手法：测密钥时先 `client.cookies.clear()` —— TestClient 会自动保存
setup 的会话 Cookie，不清掉的话请求同时带着 Cookie 和密钥，验不出密钥
自己管不管用。
"""

import os

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import ApiKey, ItemList, User
from app.seed import init_db

USER = os.environ.get("TEST_AUTH_USER", "admin")
# 测试用的假密码，只在测试库里创建账号，不是任何真实环境的凭据。
PASSWORD = os.environ.get("TEST_AUTH_PASSWORD", "test-only-pass")


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    session.query(ApiKey).delete()
    session.query(User).delete()
    session.query(ItemList).delete()
    session.commit()
    session.add(ItemList(name="采购清单", sort=0))
    session.commit()
    session.close()
    auth._failures.clear()
    with TestClient(app) as c:
        c.post("/api/auth/setup", json={"username": USER, "password": PASSWORD})
        yield c
    engine.dispose()


def _new_key(client, name="脚本"):
    r = client.post("/api/keys", json={"name": name})
    assert r.status_code == 200, r.text
    return r.json()


# ---------------------------------------------------------------- 权限

def test_requires_login_to_manage_keys(client):
    client.cookies.clear()
    assert client.get("/api/keys").status_code == 401
    assert client.post("/api/keys", json={"name": "外部脚本"}).status_code == 401
    assert client.delete("/api/keys/1").status_code == 401


def test_docs_require_login(client):
    # fixture 里已经建好账号并登录着，此时文档可用
    assert client.get("/api/openapi.json").status_code == 200
    client.cookies.clear()
    assert client.get("/api/docs").status_code == 401
    assert client.get("/api/openapi.json").status_code == 401


# ---------------------------------------------------------------- 生成与列表

def test_key_shows_once_and_list_keeps_prefix_only(client):
    created = _new_key(client, "手机快捷指令")
    assert created["key"].startswith(auth.API_KEY_PREFIX)
    assert len(created["key"]) > 30
    assert created["name"] == "手机快捷指令"

    rows = client.get("/api/keys").json()
    assert len(rows) == 1
    assert rows[0]["prefix"] == created["key"][:11]
    # 明文不能再从任何地方读到
    assert "key" not in rows[0]


def test_name_is_required(client):
    assert client.post("/api/keys", json={"name": ""}).status_code == 422
    assert client.post("/api/keys", json={"name": "   "}).status_code == 400


# ---------------------------------------------------------------- 用它调接口

def test_key_works_via_x_api_key_header(client):
    raw = _new_key(client)["key"]
    client.cookies.clear()

    r = client.get("/api/lists", headers={"X-API-Key": raw})

    assert r.status_code == 200, r.text
    assert len(r.json()) == 1


def test_key_works_via_bearer_header(client):
    raw = _new_key(client)["key"]
    client.cookies.clear()

    r = client.get("/api/lists", headers={"Authorization": f"Bearer {raw}"})

    assert r.status_code == 200, r.text


def test_last_used_is_recorded(client):
    raw = _new_key(client)["key"]
    assert client.get("/api/keys").json()[0]["last_used_at"] == ""

    # 清掉会话，全程只用密钥：调一次业务接口，再读回密钥列表
    client.cookies.clear()
    assert client.get("/api/lists", headers={"X-API-Key": raw}).status_code == 200
    rows = client.get("/api/keys", headers={"X-API-Key": raw}).json()

    assert rows[0]["last_used_at"] != ""


# ---------------------------------------------------------------- 失效

def test_revoked_key_stops_working(client):
    created = _new_key(client)
    raw = created["key"]
    client.cookies.clear()
    # 撤销前确实能用，否则下面的 401 说明不了问题
    assert client.get("/api/lists", headers={"X-API-Key": raw}).status_code == 200

    # 回到会话态再撤销（登录取回的 Cookie 由 TestClient 自动保存）
    client.post("/api/auth/login", json={"username": USER, "password": PASSWORD})
    assert client.delete(f"/api/keys/{created['id']}").status_code == 200

    client.cookies.clear()
    assert client.get("/api/lists", headers={"X-API-Key": raw}).status_code == 401


def test_fake_key_is_rejected(client):
    client.cookies.clear()
    for bogus in (auth.API_KEY_PREFIX + "not-a-real-key", "xk_", "whatever"):
        r = client.get("/api/lists", headers={"X-API-Key": bogus})
        assert r.status_code == 401, bogus


# ---------------------------------------------------------------- 不破坏原有登录

def test_login_bearer_token_still_works(client):
    """老的 Bearer 登录 token（不带 xk_ 前缀）必须照旧可用 —— 安卓端在用。"""
    token = client.post(
        "/api/auth/login", json={"username": USER, "password": PASSWORD}).json()["token"]
    client.cookies.clear()

    r = client.get("/api/lists", headers={"Authorization": f"Bearer {token}"})

    assert r.status_code == 200, r.text

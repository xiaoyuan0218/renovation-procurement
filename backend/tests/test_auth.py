"""登录鉴权的接口级测试。

这些是唯一走 HTTP 的测试：鉴权挂在路由依赖上，只有真的发请求才能验证
"没登录会被拦、登录后能过"。其余单测仍然直接调 service 函数。
"""

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import SessionLocal, engine
from app.main import app
from app.models import User
from app.seed import init_db

USER = "admin"
PASSWORD = "s3cret-pass"


@pytest.fixture()
def client():
    init_db()
    session = SessionLocal()
    session.query(User).delete()
    session.commit()
    session.close()
    # 限流计数是进程内状态，会跨测试累积
    auth._failures.clear()
    with TestClient(app) as c:
        yield c
    engine.dispose()


def _setup(client, username=USER, password=PASSWORD):
    return client.post("/api/auth/setup",
                       json={"username": username, "password": password})


def _login(client, password=PASSWORD, username=USER):
    return client.post("/api/auth/login",
                       json={"username": username, "password": password})


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------- 初始化状态

def test_state_reports_not_initialized(client):
    r = client.get("/api/auth/state")
    assert r.status_code == 200
    assert r.json() == {"initialized": False, "authenticated": False, "username": None}


def test_health_is_public(client):
    """容器健康检查打的就是这个接口，必须免登录。"""
    assert client.get("/api/health").status_code == 200


def test_setup_creates_account_and_logs_in(client):
    r = _setup(client)
    assert r.status_code == 200
    assert r.json()["username"] == USER
    assert r.json()["token"]

    state = client.get("/api/auth/state").json()
    assert state == {"initialized": True, "authenticated": True, "username": USER}

    # 建完账号即视为已登录，业务接口直接可用
    assert client.get("/api/summary").status_code == 200


def test_setup_twice_is_rejected(client):
    assert _setup(client).status_code == 200
    assert _setup(client, username="other").status_code == 409


def test_setup_rejects_short_password(client):
    assert _setup(client, password="123").status_code == 422


# ---------------------------------------------------------------- 未登录被拦

@pytest.mark.parametrize("path", ["/api/summary", "/api/items", "/api/rooms",
                                  "/api/categories", "/api/matrix", "/api/export",
                                  "/api/auth/me"])
def test_protected_endpoints_require_login(client, path):
    _setup(client)
    client.cookies.clear()
    assert client.get(path).status_code == 401


def test_spa_and_static_stay_public(client):
    """前端页面本身不该被拦，否则没登录连登录页都打不开。
    这里只断言"不是 401"——前端产物没构建时首页本来就不存在（404）。"""
    assert client.get("/").status_code != 401
    assert client.get("/assets/index.js").status_code != 401


# ---------------------------------------------------------------- 登录

def test_login_with_cookie(client):
    _setup(client)
    client.cookies.clear()

    assert client.get("/api/summary").status_code == 401
    assert _login(client).status_code == 200
    # Cookie 由 TestClient 自动保存，下一个请求自动带上
    assert client.get("/api/summary").status_code == 200


def test_login_with_bearer_token(client):
    """安卓端走这条路：不带 Cookie，只发 Authorization 头。"""
    token = _setup(client).json()["token"]
    client.cookies.clear()

    assert client.get("/api/items").status_code == 401
    assert client.get("/api/items", headers=_bearer(token)).status_code == 200


def test_wrong_password_is_rejected_without_revealing_existence(client):
    _setup(client)
    client.cookies.clear()

    r = _login(client, password="wrong-password")
    assert r.status_code == 401
    # 用户不存在与密码错误返回同一句话，避免被用来枚举账号
    assert r.json()["detail"] == "用户名或密码不正确"

    r = _login(client, username="nobody", password="wrong-password")
    assert r.status_code == 401
    assert r.json()["detail"] == "用户名或密码不正确"


def test_logout_clears_session(client):
    _setup(client)
    assert client.post("/api/auth/logout").status_code == 200
    assert client.get("/api/summary").status_code == 401


def test_garbage_token_is_rejected(client):
    _setup(client)
    client.cookies.clear()
    for bad in ("abc", "abc.def", "", "a.b.c.d"):
        r = client.get("/api/summary", headers=_bearer(bad))
        assert r.status_code == 401, bad


def test_tampered_token_is_rejected(client):
    _setup(client)
    client.cookies.clear()
    token = _login(client).json()["token"]
    body, _, signature = token.partition(".")
    # 换个签名应当通不过
    assert client.get("/api/summary", headers=_bearer(f"{body}.{signature[:-2]}xx")).status_code == 401
    # 改载荷也通不过
    assert client.get("/api/summary", headers=_bearer(f"x{body}.{signature}")).status_code == 401


# ---------------------------------------------------------------- 改密码

def test_change_password_requires_old_password(client):
    _setup(client)
    r = client.post("/api/auth/password",
                    json={"old_password": "nope", "new_password": "brand-new-pass"})
    assert r.status_code == 401


def test_change_password_invalidates_old_tokens(client):
    old_token = _setup(client).json()["token"]

    r = client.post("/api/auth/password",
                    json={"old_password": PASSWORD, "new_password": "brand-new-pass"},
                    headers=_bearer(old_token))
    assert r.status_code == 200
    new_token = r.json()["token"]
    assert new_token != old_token

    client.cookies.clear()
    # 旧 token 失效，当前会话拿新 token 继续用
    assert client.get("/api/summary", headers=_bearer(old_token)).status_code == 401
    assert client.get("/api/summary", headers=_bearer(new_token)).status_code == 200
    # 新密码可登录、旧密码不可
    assert _login(client, password="brand-new-pass").status_code == 200
    assert _login(client, password=PASSWORD).status_code == 401


# ---------------------------------------------------------------- 限流

def test_repeated_failures_are_throttled(client):
    _setup(client)
    client.cookies.clear()

    for _ in range(auth.MAX_FAILURES):
        assert _login(client, password="wrong-password").status_code == 401
    # 再试就被锁了，即便密码是对的
    r = _login(client)
    assert r.status_code == 429
    assert "秒后再试" in r.json()["detail"]


def test_successful_login_resets_failure_counter(client):
    _setup(client)
    client.cookies.clear()

    for _ in range(auth.MAX_FAILURES - 1):
        _login(client, password="wrong-password")
    assert _login(client).status_code == 200
    # 成功后计数清零，下一次失败只是普通的 401
    assert _login(client, password="wrong-password").status_code == 401


# ---------------------------------------------------------------- 密码哈希

def test_password_hash_is_salted_and_verifiable():
    first = auth.hash_password(PASSWORD)
    second = auth.hash_password(PASSWORD)
    assert first != second  # 同样的密码每次盐不同
    assert PASSWORD not in first
    assert auth.verify_password(PASSWORD, first)
    assert not auth.verify_password("wrong", first)
    assert not auth.verify_password(PASSWORD, "garbage")

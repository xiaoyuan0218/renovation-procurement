"""桌面端同步链路的端到端测试（真起一个后端进程当「远程服务器」）。

覆盖：登录、上传、拉取、双向合并、冲突上报、服务器那份被删后自动解绑。

「远程」是一个独立的 uvicorn 进程，有自己的数据目录和端口。不能图省事用同一个
库模拟：`client.lists()` 会把本地清单自己也列出来，upload 的「按编号找同一份」
立刻会认错 —— 真实场景本来就是两台机器，这里如实还原。
"""

import os
import socket
import subprocess
import sys
import tempfile
import time

import httpx
import pytest

from app import auth
from app.db import SessionLocal, engine
from app.models import (Allocation, Category, ExtraExpense, Item, ItemList,
                        PurchaseRecord, RecordRoom, RemoteSession, Room,
                        SyncBinding, User)
from app.seed import init_db
from app.services import codes, desktop_sync, remote_sync
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER

BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


# ---------------------------------------------------------------- 远程测试服务器

def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _wait_ready(base: str, timeout: float = 60.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if httpx.get(f"{base}/api/health", timeout=1.0).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.3)
    raise RuntimeError("远程测试服务器没能在超时前起来")


@pytest.fixture(scope="module")
def remote_server():
    tmp = tempfile.mkdtemp(prefix="renovation_remote_")
    port = _free_port()
    env = {**os.environ,
           "RENOVATION_DATA_DIR": tmp,
           "RENOVATION_DB": os.path.join(tmp, "remote.db"),
           "RENOVATION_SECRET": "remote-test-secret-not-for-production",
           "RENOVATION_HOST": "127.0.0.1",
           "RENOVATION_PORT": str(port)}
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app",
         "--host", "127.0.0.1", "--port", str(port), "--log-level", "warning"],
        cwd=BACKEND_DIR, env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = f"http://127.0.0.1:{port}"
    try:
        _wait_ready(base)
        httpx.post(f"{base}/api/auth/setup",
                   json={"username": USER, "password": PASSWORD}, timeout=10.0)
        yield base
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


@pytest.fixture()
def ctx():
    """本地库：每次测试清空重建，只留一份带编号的清单。"""
    init_db()
    session = SessionLocal()
    for table in (RecordRoom, Allocation, PurchaseRecord, ExtraExpense, Item,
                  Room, Category, User, ItemList, SyncBinding, RemoteSession):
        session.query(table).delete()
    session.commit()
    session.add(ItemList(name="采购清单", sort=0, code=codes.new_code()))
    session.commit()
    session.close()
    auth._failures.clear()

    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
    engine.dispose()


# ---------------------------------------------------------------- helpers

def _first_list(db) -> ItemList:
    return db.query(ItemList).order_by(ItemList.sort, ItemList.id).first()


def _add_item(db, lst, name, qty=1, price=10):
    item = Item(name=name, unit="个", qty_total=qty, price=price, list_id=lst.id)
    db.add(item)
    db.flush()
    return item


def _names(db, list_id) -> set:
    db.expire_all()
    return {i.name for i in db.query(Item).filter(Item.list_id == list_id)}


def _set_price(db, lst, name, price, stamp):
    import datetime
    item = db.query(Item).filter(Item.list_id == lst.id, Item.name == name).one()
    item.price = price
    item.updated_at = datetime.datetime.strptime(stamp, "%Y-%m-%d %H:%M:%S")
    db.flush()


def _remote_token(base: str) -> str:
    response = httpx.post(f"{base}/api/auth/login",
                          json={"username": USER, "password": PASSWORD}, timeout=10.0)
    response.raise_for_status()
    return response.json()["token"]


def _remote_add_item(base: str, token: str, list_id: int, name: str,
                     qty=1, price=10) -> None:
    """模拟「在网页版上改了服务器那份清单」。"""
    response = httpx.post(
        f"{base}/api/items",
        json={"name": name, "unit": "个", "qty_total": qty, "price": price},
        headers={"Authorization": f"Bearer {token}", "X-List-Id": str(list_id)},
        timeout=10.0)
    response.raise_for_status()


def _remote_list_id(base: str, token: str, name: str) -> int:
    response = httpx.get(f"{base}/api/lists",
                         headers={"Authorization": f"Bearer {token}"}, timeout=10.0)
    response.raise_for_status()
    return next(row["id"] for row in response.json() if row["name"] == name)


def _remote_names(base: str, token: str, list_id: int) -> set:
    response = httpx.get(f"{base}/api/items",
                         headers={"Authorization": f"Bearer {token}",
                                  "X-List-Id": str(list_id)}, timeout=10.0)
    response.raise_for_status()
    return {row["name"] for row in response.json()}


# ---------------------------------------------------------------- 登录与上传

def test_login_then_upload_creates_remote_copy(ctx, remote_server):
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()

    state = desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    assert state["username"] == USER
    assert desktop_sync.session_state(ctx)["logged_in"] is True

    out = desktop_sync.upload(ctx, lst.id)
    assert out.get("created_list_id"), out

    ctx.expire_all()
    binding = ctx.get(SyncBinding, lst.id)
    assert binding is not None
    assert binding.remote_list_id == out["created_list_id"]
    assert binding.fingerprint

    # 服务器上那份确实带着同样的物料
    token = _remote_token(remote_server)
    response = httpx.get(
        f"{remote_server}/api/items",
        headers={"Authorization": f"Bearer {token}",
                 "X-List-Id": str(binding.remote_list_id)}, timeout=10.0)
    assert {row["name"] for row in response.json()} == {"筒灯"}


def test_login_with_bad_password_reports_readable_error(ctx, remote_server):
    with pytest.raises(remote_sync.RemoteError) as err:
        desktop_sync.login(ctx, remote_server, USER, "wrong-password")
    assert err.value.status == 401


def test_upload_without_login_is_refused(ctx, remote_server):
    lst = _first_list(ctx)
    with pytest.raises(desktop_sync.SyncError):
        desktop_sync.upload(ctx, lst.id)


# ---------------------------------------------------------------- 双向同步

def test_bidirectional_sync_keeps_both_sides(ctx, remote_server):
    """电脑上改一处、服务器上改另一处，同步后两边都在。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    ctx.expire_all()

    token = _remote_token(remote_server)
    _remote_add_item(remote_server, token, remote_id, "开关", qty=5, price=8)   # 服务器改
    _add_item(ctx, lst, "插座", qty=3, price=12)                                 # 本地改
    ctx.commit()

    result = desktop_sync.sync(ctx, lst.id)
    assert not result.get("conflicts"), result

    assert _names(ctx, lst.id) == {"筒灯", "开关", "插座"}
    response = httpx.get(f"{remote_server}/api/items",
                         headers={"Authorization": f"Bearer {token}",
                                  "X-List-Id": str(remote_id)}, timeout=10.0)
    assert {row["name"] for row in response.json()} == {"筒灯", "开关", "插座"}


def test_local_edit_pushes_to_remote(ctx, remote_server):
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    ctx.expire_all()

    item = ctx.query(Item).filter(Item.list_id == lst.id).one()
    item.price = 42
    ctx.commit()

    desktop_sync.sync(ctx, lst.id)

    token = _remote_token(remote_server)
    response = httpx.get(f"{remote_server}/api/items",
                         headers={"Authorization": f"Bearer {token}",
                                  "X-List-Id": str(remote_id)}, timeout=10.0)
    assert response.json()[0]["price"] == 42


def test_second_sync_is_noop(ctx, remote_server):
    """两边都没动时同步不该产生任何写入（否则自动同步会自己转起来）。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    desktop_sync.upload(ctx, lst.id)
    ctx.expire_all()

    binding = ctx.get(SyncBinding, lst.id)
    before = (binding.fingerprint, binding.last_synced_at)
    result = desktop_sync.sync(ctx, lst.id)
    assert result == {}
    ctx.expire_all()
    after = ctx.get(SyncBinding, lst.id)
    assert (after.fingerprint, after.last_synced_at) == before


# ---------------------------------------------------------------- 冲突

def test_conflicting_edit_is_reported_not_decided(ctx, remote_server):
    """两边改了同一处且判不出谁新：只上报冲突，不擅自覆盖。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    ctx.expire_all()

    stamp = "2026-03-01 12:00:00"
    _set_price(ctx, lst, "筒灯", 11, stamp)
    ctx.commit()

    # 服务器那边把同一个物料改成另一个价，时间戳**也**设成同一秒 —— 走同步接口
    # 推送才带得上指定时间戳（普通编辑接口的 updated_at 由服务端盖，会比本地新，
    # 那种情况属于「谁新听谁的」，不算冲突）
    token = _remote_token(remote_server)
    headers = {"Authorization": f"Bearer {token}"}
    snap = httpx.get(f"{remote_server}/api/sync/lists/{remote_id}",
                     headers=headers, timeout=10.0).json()
    for item in snap["payload"]["items"]:
        item["price"] = 22
        item["updated_at"] = stamp
        item["created_at"] = stamp
    httpx.put(f"{remote_server}/api/sync/lists/{remote_id}",
              json={**snap["payload"], "base_fingerprint": snap["fingerprint"]},
              headers=headers, timeout=10.0).raise_for_status()

    ctx.expire_all()
    result = desktop_sync.sync(ctx, lst.id)
    assert result.get("conflicts"), result
    assert result["conflicts"][0]["label"] == "物料"

    # 用户还没表态，两边都不该被改动
    assert ctx.query(Item).filter(Item.list_id == lst.id).one().price == 11
    remote_items = httpx.get(f"{remote_server}/api/items", headers={
        **headers, "X-List-Id": str(remote_id)}, timeout=10.0).json()
    assert remote_items[0]["price"] == 22


# ---------------------------------------------------------------- 拉取

def test_resolve_create_new_keeps_both_sides(ctx, remote_server):
    """选「另存一份」：本机内容成为服务器上一份新清单，两边的内容都留着。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    desktop_sync.unbind(ctx, lst.id)

    _add_item(ctx, lst, "本地新加的", qty=1, price=5)     # 本地改过
    ctx.commit()
    token = _remote_token(remote_server)
    _remote_add_item(remote_server, token, remote_id, "服务器加的", qty=3, price=7)

    decision = desktop_sync.pull_as_new(ctx, remote_id, "不管叫什么")["needs_upload_decision"]
    out = desktop_sync.resolve_upload(ctx, decision["list_id"],
                                     decision["remote_list_id"], choice="create_new")
    assert out.get("notice"), out

    # 本地内容一条不少，而且换了新编号、重新绑到新建的那份上
    assert _names(ctx, lst.id) == {"筒灯", "本地新加的"}
    binding = ctx.get(SyncBinding, lst.id)
    assert binding is not None and binding.remote_list_id != remote_id
    # 服务器上新旧各一份：新建那份带着本地全部内容，原来那份没被动过
    assert _remote_names(remote_server, token, binding.remote_list_id) == {"筒灯", "本地新加的"}
    assert _remote_names(remote_server, token, remote_id) == {"筒灯", "服务器加的"}


def test_resolve_create_new_on_pull_saves_remote_as_local_copy(ctx, remote_server):
    """拉取撞车时选「另存一份」：把服务器那份另存成本地的一份新清单，两份都留。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    desktop_sync.unbind(ctx, lst.id)

    _add_item(ctx, lst, "本地新加的", qty=1, price=5)     # 本地改过
    ctx.commit()
    token = _remote_token(remote_server)
    _remote_add_item(remote_server, token, remote_id, "服务器加的", qty=3, price=7)

    decision = desktop_sync.pull_as_new(ctx, remote_id, "不管叫什么")["needs_upload_decision"]
    assert decision["direction"] == "pull"

    out = desktop_sync.resolve_upload(ctx, decision["list_id"],
                                     decision["remote_list_id"],
                                     choice="create_new", direction="pull")
    copy_id = out["created_list_id"]
    assert copy_id != lst.id

    # 本地两份都在：原来那份没动，新份是服务器那份的副本
    assert _names(ctx, lst.id) == {"筒灯", "本地新加的"}
    assert _names(ctx, copy_id) == {"筒灯", "服务器加的"}
    # 副本编号换新（不跟原来那份撞），也没有绑定（独立副本）
    assert ctx.get(ItemList, copy_id).code != ctx.get(ItemList, lst.id).code
    assert ctx.get(SyncBinding, copy_id) is None


def test_resolve_upload_route_accepts_create_new():
    """路由层的白名单要认得 create_new。

    只改服务层、忘了路由白名单的话，用户在界面上选「另存一份」会被 400 挡掉 ——
    服务层的用例直接调函数，抓不到这种漏。
    """
    from fastapi.testclient import TestClient
    from app.main import app as local_app

    with TestClient(local_app) as client:
        client.post("/api/auth/setup", json={"username": USER, "password": PASSWORD})
        token = client.post("/api/auth/login",
                            json={"username": USER, "password": PASSWORD}).json()["token"]
        response = client.post(
            "/api/desktop/resolve-upload",
            json={"list_id": 1, "remote_list_id": 1,
                  "choice": "create_new", "direction": "pull"},
            headers={"Authorization": f"Bearer {token}"})

    assert "choice 只能是" not in response.text, response.text


def test_pull_creates_local_copy_and_binds(ctx, remote_server):
    """本地没有这份（同编号）时，拉取就新建一份并绑定。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    desktop_sync.unbind(ctx, lst.id)
    # 本地这份删掉，模拟"这台机器上还没有它"
    ctx.delete(lst)
    ctx.commit()

    out = desktop_sync.pull_as_new(ctx, remote_id, "拉下来的")
    assert out.get("created_list_id")
    assert _names(ctx, out["created_list_id"]) == {"筒灯"}
    assert ctx.get(SyncBinding, out["created_list_id"]) is not None


def test_pull_same_code_does_not_duplicate_list(ctx, remote_server):
    """本地已经有同编号的清单时，拉取不该再建一份同编号的，而是弹选择框。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    desktop_sync.unbind(ctx, lst.id)
    ctx.expire_all()

    out = desktop_sync.pull_as_new(ctx, remote_id, "不管叫什么")

    assert out.get("needs_upload_decision"), f"应当交给用户定：{out}"
    assert ctx.query(ItemList).count() == 1


def test_pull_same_code_asks_instead_of_overwriting(ctx, remote_server):
    """解绑后两边各改了一些，再点「拉到本地」：不许悄悄覆盖任何一边。

    没有基线就分不清谁改了什么，所以只能把两边摆出来让用户选（按名字合并 /
    以本机为准 / 以服务器为准），选择框与「上传撞上同一份」共用。从前是直接拿
    服务器那份整份覆盖本地 —— 本地改动无声消失。
    """
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    desktop_sync.unbind(ctx, lst.id)          # 解绑：共同基线没了

    # 两边各改一些：本地加一件，服务器（模拟网页版）加一件
    _add_item(ctx, lst, "本地新加的", qty=1, price=5)
    ctx.commit()
    token = _remote_token(remote_server)
    _remote_add_item(remote_server, token, remote_id, "服务器新加的", qty=3, price=7)

    out = desktop_sync.pull_as_new(ctx, remote_id, "不管叫什么")

    decision = out.get("needs_upload_decision")
    assert decision, f"应当弹选择框，而不是直接覆盖：{out}"
    assert decision["remote_list_id"] == remote_id
    assert decision["local"]["items"] == 2      # 本地的样子
    assert decision["remote"]["items"] == 2     # 服务器的样子
    # 用户拍板之前，谁都不许动
    assert _names(ctx, lst.id) == {"筒灯", "本地新加的"}


# ---------------------------------------------------------------- 服务器那份被删

def test_remote_deletion_unbinds_and_keeps_local(ctx, remote_server):
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    ctx.expire_all()

    # 在服务器上把那份删掉
    token = _remote_token(remote_server)
    httpx.delete(f"{remote_server}/api/lists/{remote_id}",
                 headers={"Authorization": f"Bearer {token}"}, timeout=10.0)

    result = desktop_sync.sync(ctx, lst.id)
    assert result.get("remote_missing") is True
    assert "解除绑定" in result["notice"]
    assert ctx.get(SyncBinding, lst.id) is None
    assert _names(ctx, lst.id) == {"筒灯"}


# ---------------------------------------------------------------- 自动同步

def test_auto_sync_requires_binding_and_login(ctx, remote_server):
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    # 没登录、没绑定：安静返回 False，不抛错
    assert desktop_sync.auto_sync(ctx, lst.id) is False


def test_auto_sync_pushes_changes(ctx, remote_server):
    """本地新增会自动推上去（返回值说的是「本地内容变没变」，这里本地没变）。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    ctx.expire_all()

    _add_item(ctx, lst, "开关", qty=1, price=5)
    ctx.commit()

    desktop_sync.auto_sync(ctx, lst.id)
    token = _remote_token(remote_server)
    response = httpx.get(f"{remote_server}/api/items",
                         headers={"Authorization": f"Bearer {token}",
                                  "X-List-Id": str(remote_id)}, timeout=10.0)
    assert {row["name"] for row in response.json()} == {"筒灯", "开关"}


def test_auto_sync_reports_when_remote_brought_changes(ctx, remote_server):
    """服务器上多了东西被拉下来 —— 本地内容真的变了，返回值要为 True 让界面刷新。"""
    lst = _first_list(ctx)
    _add_item(ctx, lst, "筒灯", qty=2, price=10)
    ctx.commit()
    desktop_sync.login(ctx, remote_server, USER, PASSWORD)
    remote_id = desktop_sync.upload(ctx, lst.id)["created_list_id"]
    ctx.expire_all()

    _remote_add_item(remote_server, _remote_token(remote_server), remote_id,
                     "服务器加的", qty=1, price=3)
    assert desktop_sync.auto_sync(ctx, lst.id) is True
    assert _names(ctx, lst.id) == {"筒灯", "服务器加的"}

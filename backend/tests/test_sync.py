"""清单级同步：整份导出、整份灌回、或者新建一份。

手机单机版靠这三个接口把本地清单搬上服务器、或把服务器上的清单带回手机。
最关键的一条是**搬运不改变数据**：导出再覆盖回去，三个口径的数字必须分文不差 ——
否则来回搬几次就会累积偏差，用户还不知道该信哪一边。
"""

import glob
import datetime
import os

import pytest
from fastapi.testclient import TestClient

from app import auth
from app.db import DB_PATH, SessionLocal, engine
from app.main import app
from app.models import (Allocation, Category, ExtraExpense, Item, ItemList,
                        PurchaseRecord, RecordRoom, Room, User)
from app.seed import init_db
from tests.conftest import TEST_PASSWORD as PASSWORD, TEST_USER as USER


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


def _mk_list(client, name="装修采购"):
    r = client.post("/api/lists", json={"name": name})
    assert r.status_code == 200, r.text
    return r.json()


def _hdr(lst):
    return {"X-List-Id": str(lst["id"])}


def _seed(client, lst):
    """造一批有代表性的数据：两个分组、一个分类、两条物料（带分配与采购记录）、一笔费用。"""
    h = _hdr(lst)
    rooms = [client.post("/api/rooms", json={"name": n}, headers=h).json()
             for n in ("客厅", "主卧")]
    cat = client.post("/api/categories", json={"name": "照明"}, headers=h).json()
    item = client.post("/api/items", json={
        "name": "筒灯", "category_id": cat["id"], "brand": "松下", "model": "NN-3021",
        "unit": "个", "price": 30.5, "discount_price": 28.0,
        "allocations": [
            {"room_id": rooms[0]["id"], "qty": 6},
            {"room_id": rooms[1]["id"], "qty": 4, "price_override": 25.0},
        ],
        "records": [{
            "qty": 6, "amount": 180.5, "date": "2026-09-01",
            "vendor": "京东", "order_no": "A1", "room_ids": [rooms[0]["id"]],
        }],
    }, headers=h).json()
    switch = client.post("/api/items", json={
        "name": "开关", "unit": "个", "qty_total": 10, "price": 12.5,
    }, headers=h).json()
    client.post("/api/expenses", json={
        "kind": "运费", "amount": 80, "date": "2026-09-02",
    }, headers=h)
    return rooms, cat, item, switch


def _export(client, lst):
    r = client.get(f"/api/sync/lists/{lst['id']}", headers=_hdr(lst))
    assert r.status_code == 200, r.text
    return r.json()


def _push(client, lst, snapshot, **extra):
    body = {**snapshot["payload"], "base_fingerprint": snapshot["fingerprint"], **extra}
    return client.put(f"/api/sync/lists/{lst['id']}", json=body, headers=_hdr(lst))


# ---------------------------------------------------------------- 导出

def test_export_carries_content_but_not_derived_fields(client):
    lst = _mk_list(client)
    rooms, cat, item, _ = _seed(client, lst)

    snap = _export(client, lst)
    assert set(snap) == {"fingerprint", "payload"}
    payload = snap["payload"]
    assert payload["version"] == 1
    assert payload["list"]["name"] == "装修采购"

    assert {r["name"] for r in payload["rooms"]} == {"客厅", "主卧"}
    assert [c["name"] for c in payload["categories"]] == ["照明"]

    names = {i["name"] for i in payload["items"]}
    assert names == {"筒灯", "开关"}
    light = next(i for i in payload["items"] if i["name"] == "筒灯")
    assert light["brand"] == "松下" and light["model"] == "NN-3021"
    assert light["price"] == 30.5 and light["discount_price"] == 28.0
    assert len(light["allocations"]) == 2
    assert {a["qty"] for a in light["allocations"]} == {6, 4}
    record = light["records"][0]
    assert record["amount"] == 180.5 and record["vendor"] == "京东"
    assert record["room_ids"] == [rooms[0]["id"]]

    assert [e["kind"] for e in payload["expenses"]] == ["运费"]

    # 派生值与历史字段不参与搬运：带上只会让"内容没变"被误判成"变过"
    for junk in ("rev", "bought", "paid_qty", "paid_amount", "bought_qty"):
        assert junk not in light

    # 创建/修改时间要搬（界面显示、客户端判冲突用），但不参与指纹（见下一条测试）
    assert light["created_at"] and light["updated_at"]
    assert record["created_at"] and record["updated_at"]
    assert payload["list"]["updated_at"]


def test_fingerprint_ignores_timestamps(client):
    """只刷新时间戳不该改指纹：同步本身就会把两侧的 updated_at 刷成当下。"""
    lst = _mk_list(client)
    _seed(client, lst)
    first = _export(client, lst)["fingerprint"]

    # 原样再推一次（服务端会重写内容、刷新时间）
    _push(client, lst, _export(client, lst))
    assert _export(client, lst)["fingerprint"] == first


def test_timestamps_survive_a_roundtrip(client):
    """时间戳必须原样搬过去，不能被服务器换成"导入时刻"。

    这条链子从前是断的：同步入参模型没声明这两个字段，Pydantic 默认把它们
    忽略掉，于是服务器上每行的 updated_at 都变成导入时刻 —— 两端判"谁改得
    更近"时服务器永远显得更新，手机上较新的改动会被静默覆盖。
    """
    lst = _mk_list(client)
    _seed(client, lst)
    snap = _export(client, lst)
    payload = snap["payload"]

    # 造一组明确的老时间（带微秒，与手机 nowStamp 的格式一致）
    old = "2020-01-01 08:00:00.123456"
    payload["list"]["updated_at"] = old
    payload["list"]["created_at"] = old
    for room in payload["rooms"]:
        room["updated_at"] = old
        room["created_at"] = old
    for item in payload["items"]:
        item["updated_at"] = old
        item["created_at"] = old
        for rec in item.get("records", []):
            rec["updated_at"] = old

    r = _push(client, lst, snap)
    assert r.status_code == 200, r.text

    after = _export(client, lst)["payload"]
    # 微秒在库里被截到秒（存储与导出精度一致），日期本身必须还是 2020
    assert after["list"]["updated_at"].startswith("2020-01-01 08:00:00"), after["list"]["updated_at"]
    assert after["rooms"], "样例清单应当有分组"
    for room in after["rooms"]:
        assert room["updated_at"].startswith("2020-01-01 08:00:00"), room
    for item in after["items"]:
        assert item["updated_at"].startswith("2020-01-01 08:00:00"), item
        for rec in item.get("records", []):
            assert rec["updated_at"].startswith("2020-01-01 08:00:00"), rec


def test_create_keeps_the_senders_created_at(client):
    """新建清单时用手机带来的创建时间，而不是服务器导入的时刻。"""
    src = _mk_list(client, "来源")
    _seed(client, src)
    payload = _export(client, src)["payload"]
    payload["list"] = {**payload["list"], "name": "搬过来的", "code": "",
                       "created_at": "2019-05-05 07:07:07", "updated_at": "2019-06-06 08:08:08"}

    created = client.post("/api/sync/lists", json=payload)
    assert created.status_code == 200, created.text
    new_id = created.json()["list_id"]

    row = [x for x in client.get("/api/lists").json() if x["id"] == new_id][0]
    assert row["created_at"].startswith("2019-05-05"), row["created_at"]
    assert row["updated_at"].startswith("2019-06-06"), row["updated_at"]


def test_microsecond_and_plain_formats_both_parse(client):
    """手机带微秒、服务器不带，两种写法都得认。

    只认后者的话，手机传来的时间戳会被当成"没带"而回退成导入时刻。
    """
    from app.services.list_transfer import _parse_ts

    want = datetime.datetime(2026, 9, 17, 10, 0, 0)
    assert _parse_ts("2026-09-17 10:00:00.123456") == want
    assert _parse_ts("2026-09-17 10:00:00") == want
    assert _parse_ts("2026-09-17 10:00") == want
    assert _parse_ts("") is None
    assert _parse_ts(None) is None
    assert _parse_ts("看不懂的东西") is None


def test_fingerprint_is_stable_then_changes_on_any_edit(client):
    lst = _mk_list(client)
    _seed(client, lst)

    first = _export(client, lst)["fingerprint"]
    assert _export(client, lst)["fingerprint"] == first

    # 只加一笔费用，指纹就该变 —— 说明它盯着的是内容，而不是某个漏了打点的字段
    client.post("/api/expenses", json={"kind": "安装费", "amount": 50},
                headers=_hdr(lst))
    assert _export(client, lst)["fingerprint"] != first


def test_overwrite_keeps_row_ids_and_fingerprint_after_a_hole(client):
    """删一行制造 id 空洞后覆盖，行 id 与指纹都不该漂移。

    从前 _clear+重插会让 SQLite 重排行 id，内容一字未改指纹却变了 ——
    另一台设备会误判"服务器又变过了"，其 id 映射也会失配、合并出重复行。
    """
    lst = _mk_list(client)
    # 三个分组，删中间的 → 剩余 id 有空洞
    a = client.post("/api/rooms", json={"name": "甲"}, headers=_hdr(lst)).json()
    b = client.post("/api/rooms", json={"name": "乙"}, headers=_hdr(lst)).json()
    c = client.post("/api/rooms", json={"name": "丙"}, headers=_hdr(lst)).json()
    client.delete(f"/api/rooms/{b['id']}", headers=_hdr(lst))

    snap = _export(client, lst)
    ids_before = [r["id"] for r in snap["payload"]["rooms"]]
    fp_before = snap["fingerprint"]

    r = _push(client, lst, snap)
    assert r.status_code == 200, r.text

    snap2 = _export(client, lst)
    # 行 id 仍会重排（内部的），但指纹是语义指纹：id 重排不该让它变
    assert snap2["fingerprint"] == fp_before, (
        f"内容没变指纹却变了（id 漂移 {ids_before} → "
        f"{[x['id'] for x in snap2['payload']['rooms']]}）")
    strip = lambda rows: [{k: v for k, v in r.items() if k != "id"}
                          for r in rows]
    assert strip(snap2["payload"]["rooms"]) == strip(snap["payload"]["rooms"]), (
        "语义内容该分毫不差")


def test_export_only_covers_its_own_list(client):
    a = _mk_list(client, "装修采购")
    b = _mk_list(client, "年货")
    _seed(client, a)
    client.post("/api/items", json={"name": "坚果", "qty_total": 3},
                headers=_hdr(b))

    payload = _export(client, a)["payload"]
    assert "坚果" not in {i["name"] for i in payload["items"]}
    assert {i["name"] for i in payload["items"]} == {"筒灯", "开关"}


def test_trashed_items_travel_along(client):
    lst = _mk_list(client)
    _, _, item, switch = _seed(client, lst)
    client.delete(f"/api/items/{switch['id']}", headers=_hdr(lst))

    payload = _export(client, lst)["payload"]
    trashed = next(i for i in payload["items"] if i["name"] == "开关")
    assert trashed["deleted_at"], "回收站里的物料也要跟着走，两端回收站才能一致"

    # 灌回去之后仍然是回收站状态，不会"复活"
    put = _push(client, lst, _export(client, lst))
    assert put.status_code == 200, put.text
    names = [t["name"] for t in client.get("/api/trash", headers=_hdr(lst)).json()]
    assert "开关" in names


def test_empty_list_exports_cleanly(client):
    lst = _mk_list(client)
    payload = _export(client, lst)["payload"]
    assert payload["items"] == [] and payload["rooms"] == []
    assert payload["expenses"] == []


# ---------------------------------------------------------------- 覆盖

def test_replace_roundtrip_keeps_every_total(client):
    """导出 → 覆盖回去：三个口径的数字分文不差，条数也一样。"""
    lst = _mk_list(client)
    _seed(client, lst)
    h = _hdr(lst)
    before = client.get("/api/summary", headers=h).json()["totals"]

    put = _push(client, lst, _export(client, lst))
    assert put.status_code == 200, put.text

    after = client.get("/api/summary", headers=h).json()["totals"]
    assert after == before


def test_replace_drops_what_the_sender_no_longer_has(client):
    """以推送方为准：手机上没有的东西，服务器上也要消失。"""
    lst = _mk_list(client)
    _, _, _, switch = _seed(client, lst)
    h = _hdr(lst)

    snap = _export(client, lst)
    snap["payload"]["items"] = [i for i in snap["payload"]["items"]
                               if i["name"] != "开关"]
    put = _push(client, lst, snap)
    assert put.status_code == 200, put.text

    names = {i["name"] for i in client.get("/api/items", headers=h).json()}
    assert names == {"筒灯"}
    assert client.get(f"/api/items/{switch['id']}", headers=h).status_code == 404


def test_push_without_base_fingerprint_is_rejected(client):
    """没带指纹又没带 force 的覆盖一律拒掉，不能静默覆盖。

    从前 `base_fingerprint` 为空时条件短路，既不做冲突检查、也不留备份 ——
    服务器上别人刚做的改动就这么没了，而且没有任何痕迹。
    """
    lst = _mk_list(client)
    _seed(client, lst)
    h = _hdr(lst)
    snap = _export(client, lst)
    before = client.get("/api/expenses", headers=h).json()

    # 先让服务器上多一笔费用，制造"服务器被改过"的事实
    client.post("/api/expenses", json={"kind": "安装费", "amount": 50}, headers=h)

    for body in (
        {"force": False, **snap["payload"]},                      # 完全不带
        {"force": False, "base_fingerprint": None, **snap["payload"]},
        {"force": False, "base_fingerprint": "", **snap["payload"]},
    ):
        r = client.put(f"/api/sync/lists/{lst['id']}", json=body, headers=h)
        assert r.status_code == 409, f"应当拒绝，实际 {r.status_code}：{r.text}"
        assert r.json()["detail"]["current_fingerprint"]

    # 服务器上那笔新费用还在（没被静默覆盖掉）
    kinds = [e["kind"] for e in client.get("/api/expenses", headers=h).json()]
    assert "安装费" in kinds, f"服务器上的改动被抹掉了：{kinds}"
    assert len(kinds) == len(before) + 1

    # 带上 force 就能覆盖：用户明确要"以我为准"
    forced = client.put(f"/api/sync/lists/{lst['id']}",
                        json={"force": True, **snap["payload"]}, headers=h)
    assert forced.status_code == 200, forced.text
    assert len(client.get("/api/expenses", headers=h).json()) == len(before)


def test_push_with_correct_fingerprint_still_works(client):
    """带对指纹的常规推送不受新护栏影响。"""
    lst = _mk_list(client)
    _seed(client, lst)
    snap = _export(client, lst)
    r = _push(client, lst, snap)
    assert r.status_code == 200, r.text


def test_stale_fingerprint_is_rejected(client):
    lst = _mk_list(client)
    _seed(client, lst)
    h = _hdr(lst)
    snap = _export(client, lst)

    # 服务器这边又改了一处（模拟电脑上也在动）
    client.post("/api/expenses", json={"kind": "安装费", "amount": 50}, headers=h)

    put = _push(client, lst, snap)
    assert put.status_code == 409
    detail = put.json()["detail"]
    assert detail["current_fingerprint"] == _export(client, lst)["fingerprint"]


def test_force_overwrites_and_keeps_a_backup(client):
    lst = _mk_list(client)
    _seed(client, lst)
    h = _hdr(lst)
    snap = _export(client, lst)
    client.post("/api/expenses", json={"kind": "安装费", "amount": 50}, headers=h)

    before_backups = set(glob.glob(f"{DB_PATH}.bak-*"))
    put = _push(client, lst, snap, force=True)
    assert put.status_code == 200, put.text

    # 用户选了"以我为准"，覆盖前要留一份能救回来的
    after_backups = set(glob.glob(f"{DB_PATH}.bak-*"))
    assert len(after_backups) > len(before_backups), "force 覆盖前应留整库备份"

    kinds = {e["kind"] for e in client.get("/api/expenses", headers=h).json()}
    assert kinds == {"运费"}, "强制覆盖后应该是推送方的内容"


# ---------------------------------------------------------------- 新建

def test_create_list_from_payload(client):
    src = _mk_list(client, "手机装修")
    _seed(client, src)
    h = _hdr(src)
    before = client.get("/api/lists").json()

    payload = _export(client, src)["payload"]
    payload["list"] = {**payload["list"], "name": "手机搬来的清单"}
    r = client.post("/api/sync/lists", json=payload)
    assert r.status_code == 200, r.text
    created = r.json()
    new_id = created["list_id"]
    assert new_id != src["id"]

    # 服务器上的老清单原封不动
    assert len(client.get("/api/lists").json()) == len(before) + 1
    assert client.get("/api/summary", headers=h).json()["totals"]["item_count"] == 2

    # 新清单里的内容与来源一致
    new_h = {"X-List-Id": str(new_id)}
    totals = client.get("/api/summary", headers=new_h).json()["totals"]
    assert totals["item_count"] == 2
    assert totals["paid_total"] == 180.5
    assert {r["name"] for r in client.get("/api/rooms", headers=new_h).json()} == {"客厅", "主卧"}
    assert {i["name"] for i in client.get("/api/items", headers=new_h).json()} == {"筒灯", "开关"}


def test_create_keeps_name_even_if_taken(client):
    """同名不冲突：编号才是身份，两份同名清单并存、各自独立。"""
    _mk_list(client, "装修采购")
    src = _mk_list(client, "别的")
    _seed(client, src)

    payload = _export(client, src)["payload"]
    payload["list"] = {**payload["list"], "name": "装修采购"}   # 与已有清单重名
    r = client.post("/api/sync/lists", json=payload)
    assert r.status_code == 200, r.text
    assert r.json()["list_id"]

    # 名字原样保留，不再加「 2」后缀 —— 后缀会让客户端认不出哪份是哪份
    same_name = [lst for lst in client.get("/api/lists").json()
                 if lst["name"] == "装修采购"]
    assert len(same_name) == 2
    # 两份的编号不同，靠它区分
    assert len({lst["code"] for lst in same_name}) == 2


def test_create_tolerates_duplicate_room_and_category_names(client):
    """payload 里带了同名分组/分类时不能让整份同步失败。

    同名在表上有唯一约束（uq_category_list_name）。客户端一旦发来重复的，
    从前会以 IntegrityError 500 收场 —— 用户的数据完全传不上来，而他看到的
    只是一句"请求失败"。重复的并到先出现的那份上，数据照样传得上去。
    """
    src = _mk_list(client, "手机装修")
    _seed(client, src)

    payload = _export(client, src)["payload"]
    payload["list"] = {**payload["list"], "name": "带重复名的清单"}
    # 手工造重复：把已有的第一个分类/分组再追加一遍（换个 id）
    payload["categories"].append({**payload["categories"][0], "id": 9999})
    payload["rooms"].append({**payload["rooms"][0], "id": 9999})

    r = client.post("/api/sync/lists", json=payload)
    assert r.status_code == 200, r.text

    new_h = {"X-List-Id": str(r.json()["list_id"])}
    names = [c["name"] for c in client.get("/api/categories", headers=new_h).json()]
    assert len(names) == len(set(names)), f"分类名不该重复：{names}"
    room_names = [x["name"] for x in client.get("/api/rooms", headers=new_h).json()]
    assert len(room_names) == len(set(room_names)), f"分组名不该重复：{room_names}"


def test_replace_tolerates_duplicate_room_and_category_names(client):
    """覆盖路径同样要能扛住重复名 —— 出问题的是 import_list 本身。"""
    src = _mk_list(client, "手机装修")
    _seed(client, src)
    payload = _export(client, src)["payload"]
    payload["categories"].append({**payload["categories"][0], "id": 9999})
    payload["rooms"].append({**payload["rooms"][0], "id": 9999})

    r = client.put(f"/api/sync/lists/{src['id']}",
                   json={"force": True, **payload})
    assert r.status_code == 200, r.text
    names = [c["name"] for c in client.get("/api/categories", headers=_hdr(src)).json()]
    assert len(names) == len(set(names)), f"分类名不该重复：{names}"


# ---------------------------------------------------------------- 鉴权

def test_requires_login(client):
    lst = _mk_list(client)
    _seed(client, lst)
    client.post("/api/auth/logout")

    assert client.get(f"/api/sync/lists/{lst['id']}").status_code == 401
    assert client.put(f"/api/sync/lists/{lst['id']}", json={}).status_code == 401
    assert client.post("/api/sync/lists", json={}).status_code == 401


def test_unknown_list_is_404(client):
    assert client.get("/api/sync/lists/999999", headers={"X-List-Id": "1"}).status_code == 404

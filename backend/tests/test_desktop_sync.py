"""桌面端同步的核心逻辑：三方合并、无基线合并、落地时的行 id 复用。

`merge.py` 是手机端 `Merger.kt` 的 Python 版，两边必须给出同样的结果 —— 同一份
清单在手机上和在电脑上同步，合并结论不一样的话就会来回打架。这里的用例逐条
对着 Kotlin 版的判定写。
"""

import pytest

from app.db import SessionLocal, engine
from app.models import (Allocation, Category, Item, ItemList, PurchaseRecord,
                        RecordRoom, Room)
from app.seed import init_db
from app.services import codes, list_transfer, local_apply, merge


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


# ---------------------------------------------------------------- 构造 payload

def _room(rid, name, sort=0, updated="2026-01-01 00:00:00"):
    return {"id": rid, "name": name, "sort": sort,
            "created_at": updated, "updated_at": updated}


def _item(iid, name, qty=1, price=10, updated="2026-01-01 00:00:00",
          allocations=None, records=None):
    return {"id": iid, "name": name, "category_id": None, "unit": "个",
            "brand": "", "model": "", "qty_total": qty, "price": price,
            "discount_price": None, "note": "", "sort": 0, "deleted_at": None,
            "created_at": updated, "updated_at": updated,
            "allocations": allocations or [], "records": records or []}


def _payload(rooms=None, categories=None, items=None, expenses=None,
             updated="2026-01-01 00:00:00"):
    return {"version": 1,
            "list": {"name": "清单", "note": "", "sort": 0, "code": "ABCDEFGH",
                     "created_at": updated, "updated_at": updated},
            "rooms": rooms or [], "categories": categories or [],
            "items": items or [], "expenses": expenses or []}


def _baseline(payload, local_map):
    return merge.Baseline(payload=payload, local_map=local_map)


# ---------------------------------------------------------------- 三方合并

def test_only_local_changed_wins():
    """只有一边改过 → 直接用改过的那边（绝大多数情况）。"""
    base = _payload(rooms=[_room(1, "客厅")])
    mine = _payload(rooms=[_room(1, "客厅改", updated="2026-01-02 00:00:00")])
    result = merge.merge(_baseline(base, {"room:1": 1}), mine, base)
    assert result.conflicts == []
    assert [r["name"] for r in result.payload["rooms"]] == ["客厅改"]


def test_only_remote_changed_wins():
    base = _payload(rooms=[_room(1, "客厅")])
    theirs = _payload(rooms=[_room(1, "服务器改", updated="2026-01-02 00:00:00")])
    result = merge.merge(_baseline(base, {"room:1": 1}), base, theirs)
    assert result.conflicts == []
    assert [r["name"] for r in result.payload["rooms"]] == ["服务器改"]


def test_both_changed_same_way_is_not_a_conflict():
    base = _payload(rooms=[_room(1, "客厅")])
    same = _payload(rooms=[_room(1, "都改成这个", updated="2026-01-02 00:00:00")])
    result = merge.merge(_baseline(base, {"room:1": 1}), same, same)
    assert result.conflicts == []
    assert [r["name"] for r in result.payload["rooms"]] == ["都改成这个"]


def test_newer_timestamp_wins_silently():
    """两边都改了但改得不一样：时间戳新的说了算，不打扰用户。"""
    base = _payload(rooms=[_room(1, "客厅")])
    mine = _payload(rooms=[_room(1, "本地改", updated="2026-01-02 00:00:00")])
    theirs = _payload(rooms=[_room(1, "服务器改", updated="2026-01-03 00:00:00")])
    result = merge.merge(_baseline(base, {"room:1": 1}), mine, theirs)
    assert result.conflicts == []
    assert [r["name"] for r in result.payload["rooms"]] == ["服务器改"]


def test_same_timestamp_reports_conflict():
    """时间戳一样（或都没有）时判不出来，交给用户裁决。"""
    base = _payload(rooms=[_room(1, "客厅")])
    mine = _payload(rooms=[_room(1, "本地改", updated="2026-01-02 00:00:00")])
    theirs = _payload(rooms=[_room(1, "服务器改", updated="2026-01-02 00:00:00")])
    result = merge.merge(_baseline(base, {"room:1": 1}), mine, theirs)
    assert len(result.conflicts) == 1
    assert result.conflicts[0]["label"] == "分组"
    assert result.conflicts[0]["name"] == "本地改"
    # 默认听服务器的
    assert [r["name"] for r in result.payload["rooms"]] == ["服务器改"]


def test_conflict_with_prefer_local_keeps_mine():
    base = _payload(rooms=[_room(1, "客厅")])
    mine = _payload(rooms=[_room(1, "本地改", updated="2026-01-02 00:00:00")])
    theirs = _payload(rooms=[_room(1, "服务器改", updated="2026-01-02 00:00:00")])
    result = merge.merge(_baseline(base, {"room:1": 1}), mine, theirs, prefer_local=True)
    assert [r["name"] for r in result.payload["rooms"]] == ["本地改"]


def test_remote_added_row_is_kept():
    result = merge.merge(_baseline(_payload(), {}), _payload(),
                         _payload(rooms=[_room(1, "服务器新增")]))
    assert [r["name"] for r in result.payload["rooms"]] == ["服务器新增"]
    assert result.conflicts == []


def test_local_added_row_is_kept():
    result = merge.merge(_baseline(_payload(), {}),
                         _payload(rooms=[_room(1, "本地新增")]), _payload())
    assert [r["name"] for r in result.payload["rooms"]] == ["本地新增"]


def test_deleted_on_one_side_and_untouched_stays_deleted():
    """一边删、另一边没动 → 跟着删，不算冲突。"""
    base = _payload(rooms=[_room(1, "客厅")])
    theirs = _payload(rooms=[_room(1, "客厅")])
    result = merge.merge(_baseline(base, {"room:1": 1}), _payload(), theirs)
    assert result.payload["rooms"] == []
    assert result.conflicts == []


def test_remote_new_row_matches_local_by_name():
    """服务器覆盖重插会重排 id，旧映射失效时按名字回退配对，不能冒出重复行。"""
    base = _payload(rooms=[_room(1, "客厅")])
    mine = _payload(rooms=[_room(7, "客厅", updated="2026-01-02 00:00:00")])
    # 服务器上这一行的 id 变成了 42（重插过），local_map 里没有它
    theirs = _payload(rooms=[_room(42, "客厅")])
    result = merge.merge(_baseline(base, {"room:1": 7}), mine, theirs)
    assert [r["name"] for r in result.payload["rooms"]] == ["客厅"]
    assert result.conflicts == []


def test_item_allocation_and_records_merge_as_one():
    """物料的分配与采购记录跟着物料整条合并。"""
    base = _payload(
        rooms=[_room(1, "客厅")],
        items=[_item(1, "筒灯", allocations=[
            {"id": 1, "room_id": 1, "qty": 3, "price_override": None, "note": ""}])])
    mine = _payload(
        rooms=[_room(1, "客厅")],
        items=[_item(1, "筒灯", updated="2026-01-02 00:00:00", allocations=[
            {"id": 1, "room_id": 1, "qty": 5, "price_override": None, "note": ""}],
            records=[{"id": 1, "qty": 5, "amount": 50, "date": "2026-01-02",
                      "note": "", "vendor": "", "order_no": "", "room_ids": [1],
                      "created_at": "2026-01-02 00:00:00",
                      "updated_at": "2026-01-02 00:00:00"}])])
    result = merge.merge(_baseline(base, {"room:1": 1, "item:1": 1}), mine, base)
    item = result.payload["items"][0]
    assert item["allocations"][0]["qty"] == 5
    assert len(item["records"]) == 1
    assert item["records"][0]["amount"] == 50


# ---------------------------------------------------------------- 无基线合并

def test_merge_without_base_pairs_by_name_and_renumbers():
    """从没同步过：同名的取较新那份，各自独有的都留着，结果重新编号。"""
    mine = _payload(rooms=[_room(1, "客厅", sort=0), _room(2, "书房", sort=1)])
    theirs = _payload(rooms=[_room(10, "客厅", sort=5,
                                  updated="2026-02-01 00:00:00"),
                             _room(11, "阳台")])
    merged = merge.merge_without_base(mine, theirs)
    assert [r["name"] for r in merged["rooms"]] == ["客厅", "书房", "阳台"]
    # 重新编号：两边 id 各自独立，混用会让引用挂错地方
    assert [r["id"] for r in merged["rooms"]] == [1, 2, 3]
    # 客厅取服务器那份（它更新）
    assert merged["rooms"][0]["sort"] == 5


def test_merge_without_base_rewrites_references():
    """引用要跟着新编号重写：物料的分配指向重编后的分组。"""
    mine = _payload(rooms=[_room(1, "客厅")],
                    items=[_item(1, "筒灯", allocations=[
                        {"id": 1, "room_id": 1, "qty": 3,
                         "price_override": None, "note": ""}])])
    theirs = _payload(rooms=[_room(9, "阳台")])
    merged = merge.merge_without_base(mine, theirs)
    room_ids = {r["name"]: r["id"] for r in merged["rooms"]}
    item = merged["items"][0]
    assert item["allocations"][0]["room_id"] == room_ids["客厅"]


# ---------------------------------------------------------------- 落地时沿用行 id

def test_apply_payload_keeps_existing_row_ids(db):
    """整份落地时同名行沿用原 id —— 否则同步刚跑完，界面点开物料就是「不存在」。"""
    lst = db.query(ItemList).order_by(ItemList.sort, ItemList.id).first()
    room = Room(name="客厅", sort=0, list_id=lst.id)
    cat = Category(name="照明", sort=0, list_id=lst.id)
    db.add_all([room, cat])
    db.flush()
    item = Item(name="筒灯", unit="个", qty_total=2, price=10,
                category_id=cat.id, list_id=lst.id)
    db.add(item)
    db.flush()
    db.add(Allocation(item_id=item.id, room_id=room.id, qty=2))
    db.commit()
    room_id, item_id, cat_id = room.id, item.id, cat.id

    payload = list_transfer.export_list(db, lst)
    mapping = local_apply.apply_payload(db, lst, payload)
    db.commit()
    db.expire_all()

    assert db.query(Room).filter(Room.list_id == lst.id).one().id == room_id
    assert db.query(Item).filter(Item.list_id == lst.id).one().id == item_id
    assert db.query(Category).filter(Category.list_id == lst.id).one().id == cat_id
    # 映射是「服务器 id → 本地 id」，同步靠它认出下一轮谁是谁
    assert mapping[f"room:{payload['rooms'][0]['id']}"] == room_id
    assert mapping[f"item:{payload['items'][0]['id']}"] == item_id


def test_apply_payload_keeps_duplicate_names_apart(db):
    """两条同名物料各有各的 id，落地后仍要各归各的。"""
    lst = db.query(ItemList).order_by(ItemList.sort, ItemList.id).first()
    first = Item(name="易来灯带控制器", unit="个", qty_total=1, price=99, list_id=lst.id)
    second = Item(name="易来灯带控制器", unit="个", qty_total=2, price=99, list_id=lst.id)
    db.add_all([first, second])
    db.commit()
    ids = sorted([first.id, second.id])

    payload = list_transfer.export_list(db, lst)
    local_apply.apply_payload(db, lst, payload)
    db.commit()
    db.expire_all()

    rows = db.query(Item).filter(Item.list_id == lst.id).order_by(Item.id).all()
    assert sorted(r.id for r in rows) == ids
    assert sorted(r.qty_total for r in rows) == [1, 2]


def test_apply_payload_drops_what_payload_no_longer_has(db):
    """payload 里没有的行要被删掉（整份替换的语义）。"""
    lst = db.query(ItemList).order_by(ItemList.sort, ItemList.id).first()
    db.add(Room(name="留着", sort=0, list_id=lst.id))
    db.add(Room(name="要删", sort=1, list_id=lst.id))
    db.commit()

    payload = list_transfer.export_list(db, lst)
    payload["rooms"] = [r for r in payload["rooms"] if r["name"] == "留着"]
    local_apply.apply_payload(db, lst, payload)
    db.commit()
    db.expire_all()

    assert {r.name for r in db.query(Room).filter(Room.list_id == lst.id)} == {"留着"}


def test_apply_payload_follows_incoming_code(db):
    """编号跟着 payload 走：两边显示同一个码，用户才对得上是同一份清单。"""
    lst = db.query(ItemList).order_by(ItemList.sort, ItemList.id).first()
    payload = list_transfer.export_list(db, lst)
    payload["list"]["code"] = codes.new_code()
    local_apply.apply_payload(db, lst, payload)
    db.commit()
    db.expire_all()
    assert lst.code == payload["list"]["code"]


def test_new_list_ids_avoid_rows_of_other_lists(db):
    """本地已有别的清单时，拉一份带分组的清单不能撞行 id。

    `_plan` 从前只按"本清单"发号：新清单没有可沿用的行，就从 1 开始发 —— 本地
    只要有别的清单占着 1 号（分组/分类/物料都算），一插就报
    `UNIQUE constraint failed: rooms.id`，界面上就是「拉到本地」报 500。
    """
    other = ItemList(name="旧清单", sort=0, code=codes.new_code())
    db.add(other)
    db.flush()
    db.add(Room(list_id=other.id, name="玄关", sort=0))
    db.add(Category(list_id=other.id, name="灯具", sort=0))
    db.add(Item(list_id=other.id, name="筒灯", qty_total=1, price=10))
    db.commit()
    assert db.query(Room).filter(Room.id == 1).count() == 1, "1 号应当已被占用"

    payload = _payload(
        rooms=[_room(1, "玄关")],
        categories=[{"id": 1, "name": "灯具", "sort": 0,
                     "created_at": "2026-01-01 00:00:00",
                     "updated_at": "2026-01-01 00:00:00"}],
        items=[_item(1, "筒灯", allocations=[
            {"room_id": 1, "qty": 1, "price_override": None, "note": ""}])],
    )
    lst, _mapping = local_apply.create_list_from_payload(db, payload, name="采购清单")
    db.commit()

    assert lst.id != other.id
    assert db.query(Room).filter(Room.list_id == lst.id).count() == 1
    assert db.query(Category).filter(Category.list_id == lst.id).count() == 1
    item = db.query(Item).filter(Item.list_id == lst.id).one()
    # 分配也得跟着落地（不是"没报错但丢了布点"）
    assert db.query(Allocation).filter(Allocation.item_id == item.id).count() == 1

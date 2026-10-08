"""把一份 payload 整份落到本地清单上（桌面端同步用）。

和 `list_transfer.import_list` 的区别只有一个，但很关键：**这里尽量沿用原有
的行 id**。

桌面端是「本地库 + 同步」，界面随时可能正拿着某条物料的 id（开着编辑框、停在
某一页），落地时把 id 全换掉，用户会当场看到「物料不存在」。而 `import_list`
走的是清空重插、id 由 SQLite 重排 —— 那是服务器端需要的行为（有测试
`test_overwrite_keeps_row_ids_and_fingerprint_after_a_hole` 盯着），所以这里
单独写一份，不动它。

id 复用的规则：按名字（费用按内容）把旧行的 id 排成队列，同名的新行按出现
顺序沿用；只有服务器新带来的行才拿新 id。
"""

import datetime

from sqlalchemy.orm import Session

from ..models import (Allocation, Category, ExtraExpense, Item, ItemList,
                      PurchaseRecord, RecordRoom, Room)
from . import codes
from .list_transfer import _parse_dt, _ts_or_now


def _clear_local(db: Session, lst: ItemList) -> None:
    """清空一份清单的内容（覆盖前先腾地方）。先删挂在下层的，再删主表。

    删除用 `synchronize_session="fetch"` 而不是默认的 False：下面要按原 id 把行
    插回来，session 里若还留着同主键的旧对象，identity map 会判定冲突并告警
    （行本身是对的，但 session 状态不干净，后面读到的可能是旧对象）。
    """
    item_ids = [row[0] for row in db.query(Item.id).filter(Item.list_id == lst.id)]
    if item_ids:
        record_ids = [row[0] for row in db.query(PurchaseRecord.id)
                      .filter(PurchaseRecord.item_id.in_(item_ids))]
        if record_ids:
            (db.query(RecordRoom).filter(RecordRoom.record_id.in_(record_ids))
             .delete(synchronize_session="fetch"))
        (db.query(PurchaseRecord).filter(PurchaseRecord.item_id.in_(item_ids))
         .delete(synchronize_session="fetch"))
        (db.query(Allocation).filter(Allocation.item_id.in_(item_ids))
         .delete(synchronize_session="fetch"))
    (db.query(ExtraExpense).filter(ExtraExpense.list_id == lst.id)
     .delete(synchronize_session="fetch"))
    (db.query(Item).filter(Item.list_id == lst.id).delete(synchronize_session="fetch"))
    (db.query(Room).filter(Room.list_id == lst.id).delete(synchronize_session="fetch"))
    (db.query(Category).filter(Category.list_id == lst.id)
     .delete(synchronize_session="fetch"))
    db.flush()


def _spare_ids(rows, key_of) -> dict:
    """把现有行按配对键排成「主键队列」，供重建时按序沿用。

    同键的多行必须各排各的：只认第一个的话，第二条仍会另拿新主键，界面里那一条
    的 id 照样失效。
    """
    out: dict = {}
    for row in rows:
        out.setdefault(key_of(row), []).append(row.id)
    return out


def _plan(rows, spare, key_of) -> list:
    """先给每行定好落地 id：能沿用的沿用，剩下的从「已用 id 之后」依次发。

    刻意不依赖数据库自增：自增从 max(rowid)+1 取，若复用 id 的行排在新增行
    后面，新增行可能先拿到那个 id，等复用的行插进来就撞主键了。全部显式指定
    就没有这个顺序陷阱。
    """
    planned = []
    used = set()
    for row in rows:
        queue = spare.get(key_of(row))
        reused = queue.pop(0) if queue else None
        planned.append([reused, row])
        if reused is not None:
            used.add(reused)
    next_id = max(used) + 1 if used else 1
    for entry in planned:
        if entry[0] is None:
            entry[0] = next_id
            next_id += 1
    return planned


def apply_payload(db: Session, lst: ItemList, payload: dict) -> dict:
    """用 payload 整份替换本地清单的内容，返回「服务器 id → 本地 id」的映射。

    映射必须留着：本地主键是自增的，跟服务器的 id 没有对应关系，下次同步要靠它
    认出「这两边哪一行是同一行」，才谈得上三方合并。

    清单本身的名字不动 —— 覆盖的是内容，不是这份清单的归属。调用方负责 commit。
    """
    rooms = db.query(Room).filter(Room.list_id == lst.id).all()
    categories = db.query(Category).filter(Category.list_id == lst.id).all()
    items = db.query(Item).filter(Item.list_id == lst.id).all()
    expenses = db.query(ExtraExpense).filter(ExtraExpense.list_id == lst.id).all()

    spare_rooms = _spare_ids(rooms, lambda r: r.name)
    spare_categories = _spare_ids(categories, lambda c: c.name)
    spare_items = _spare_ids(items, lambda i: i.name)
    spare_expenses = _spare_ids(expenses, lambda e: _expense_key(e))

    _clear_local(db, lst)

    mapping: dict = {}
    room_map: dict = {}
    category_map: dict = {}
    item_map: dict = {}

    for local_id, row in _plan(payload.get("rooms", []), spare_rooms,
                               lambda r: r.get("name", "")):
        room = Room(id=local_id, list_id=lst.id, name=row.get("name", ""),
                    sort=row.get("sort") or 0,
                    created_at=_ts_or_now(row.get("created_at")),
                    updated_at=_ts_or_now(row.get("updated_at")))
        db.add(room)
        db.flush()
        remote_id = row.get("id")
        if remote_id is not None:
            room_map[remote_id] = local_id
            mapping[f"room:{remote_id}"] = local_id

    for local_id, row in _plan(payload.get("categories", []), spare_categories,
                               lambda c: c.get("name", "")):
        category = Category(id=local_id, list_id=lst.id, name=row.get("name", ""),
                            sort=row.get("sort") or 0,
                            created_at=_ts_or_now(row.get("created_at")),
                            updated_at=_ts_or_now(row.get("updated_at")))
        db.add(category)
        db.flush()
        remote_id = row.get("id")
        if remote_id is not None:
            category_map[remote_id] = local_id
            mapping[f"category:{remote_id}"] = local_id

    for local_id, row in _plan(payload.get("items", []), spare_items,
                               lambda i: i.get("name", "")):
        item = Item(
            id=local_id, list_id=lst.id, name=row.get("name", ""),
            category_id=category_map.get(row.get("category_id")),
            unit=row.get("unit") or "个", brand=row.get("brand") or "",
            model=row.get("model") or "", qty_total=row.get("qty_total") or 0,
            price=row.get("price") or 0, discount_price=row.get("discount_price"),
            note=row.get("note") or "", sort=row.get("sort") or 0,
            deleted_at=_parse_dt(row.get("deleted_at")),
            created_at=_ts_or_now(row.get("created_at")),
            updated_at=_ts_or_now(row.get("updated_at")),
        )
        db.add(item)
        db.flush()
        remote_id = row.get("id")
        if remote_id is not None:
            item_map[remote_id] = local_id
            mapping[f"item:{remote_id}"] = local_id

        for alloc in row.get("allocations", []):
            room_id = room_map.get(alloc.get("room_id"))
            if room_id is None:
                # 分组没跟着来（客户端数据不一致）：跳过这条分配，不让整份落地失败
                continue
            db.add(Allocation(item_id=local_id, room_id=room_id,
                              qty=alloc.get("qty") or 0,
                              price_override=alloc.get("price_override"),
                              note=alloc.get("note") or ""))

        for record in row.get("records", []):
            row_record = PurchaseRecord(
                item_id=local_id, qty=record.get("qty") or 0,
                amount=record.get("amount") or 0, date=record.get("date") or "",
                note=record.get("note") or "", vendor=record.get("vendor") or "",
                order_no=record.get("order_no") or "",
                is_deposit=bool(record.get("is_deposit")),
                created_at=_ts_or_now(record.get("created_at")),
                updated_at=_ts_or_now(record.get("updated_at")),
            )
            db.add(row_record)
            db.flush()
            for remote_room_id in record.get("room_ids") or []:
                mapped = room_map.get(remote_room_id)
                if mapped is not None:
                    db.add(RecordRoom(record_id=row_record.id, room_id=mapped))
        db.flush()

    for local_id, row in _plan(payload.get("expenses", []), spare_expenses,
                               lambda e: _expense_key(e)):
        db.add(ExtraExpense(
            id=local_id, list_id=lst.id, kind=row.get("kind") or "其他",
            amount=row.get("amount") or 0, date=row.get("date") or "",
            vendor=row.get("vendor") or "", order_no=row.get("order_no") or "",
            note=row.get("note") or "", item_id=item_map.get(row.get("item_id")),
            created_at=_ts_or_now(row.get("created_at")),
            updated_at=_ts_or_now(row.get("updated_at")),
        ))
        remote_id = row.get("id")
        if remote_id is not None:
            mapping[f"expense:{remote_id}"] = local_id
    db.flush()

    # 编号跟着服务器那份走，两边显示同一个码，用户才对得上是同一份清单。
    # **该编号已被别的清单占用时保持原样** —— 与 import_list 的处理一致：
    # 直接写会撞上编号唯一索引，整份同步以 IntegrityError 收场，用户的数据
    # 完全落不了地。
    wanted = codes.normalize(payload.get("list", {}).get("code"))
    if wanted and wanted != lst.code and not (
            db.query(ItemList).filter(ItemList.code == wanted,
                                      ItemList.id != lst.id).first()):
        lst.code = wanted
        db.flush()

    return mapping


def _expense_key(expense) -> str:
    """费用的配对键。费用没有名字，用「类别 + 金额 + 日期 + 商家 + 单号」认它 ——
    与手机单机版一致，两端配对结果才一样。"""
    if isinstance(expense, dict):
        return "{}|{}|{}|{}|{}".format(
            expense.get("kind") or "", expense.get("amount") or 0,
            expense.get("date") or "", expense.get("vendor") or "",
            expense.get("order_no") or "")
    return "{}|{}|{}|{}|{}".format(
        expense.kind or "", expense.amount or 0, expense.date or "",
        expense.vendor or "", expense.order_no or "")


def create_list_from_payload(db: Session, payload: dict, name: str) -> tuple:
    """在本地新建一份清单并灌入内容。

    返回 `(清单, 服务器 id → 本地 id 映射)` —— 映射要给绑定关系留着，下次同步
    靠它认出「哪一行是同一行」。
    """
    list_meta = payload.get("list", {})
    created = _parse_dt_ts(list_meta.get("created_at"))
    updated = _parse_dt_ts(list_meta.get("updated_at"))
    lst = ItemList(
        name=(name or list_meta.get("name") or "未命名清单").strip()[:50],
        note=list_meta.get("note") or "", sort=list_meta.get("sort") or 0,
        code=codes.normalize(list_meta.get("code")) or codes.new_code(),
        created_at=created or _ts_or_now(None),
        updated_at=updated or _ts_or_now(None),
    )
    db.add(lst)
    db.flush()
    mapping = apply_payload(db, lst, payload)
    return lst, mapping


def _parse_dt_ts(text):
    parsed = _parse_dt(text)
    if parsed is None:
        return None
    if isinstance(parsed, datetime.datetime):
        return parsed
    return None

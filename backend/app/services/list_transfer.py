"""清单级的全量搬运：导出一份 JSON、整份灌回数据库。

手机单机版靠它把本地清单搬到服务器、或把服务器上的清单带回手机。整份清单
一起走、不做行级增量 —— 一份家用清单就几百行，全量最简单，也不会出现
"少了某个字段，两边悄悄不一致"这种事。

**只搬语义字段**：派生值和遗留列（`rev`、`bought`、`paid_qty`、`paid_amount`、
`bought_qty`、`allocations.paid_qty`）既不导出、也不参与指纹。它们要么能算
出来、要么是历史包袱，带上只会让"内容没变"被误判成"变过"。

创建/修改时间（`created_at` / `updated_at`）**会**随 payload 搬运 —— 界面要
显示、客户端拿它判冲突；但**不参与指纹**（见 `_strip_ts`），理由同上。
"""

import datetime
import hashlib
import json

from sqlalchemy.orm import Session

from ..models import (Allocation, Category, ExtraExpense, Item, ItemList,
                      PurchaseRecord, RecordRoom, Room, utcnow)
from . import codes

FORMAT_VERSION = 1


# ---------------------------------------------------------------- 导出

def _ts(value) -> str:
    """时间戳统一成 `YYYY-MM-DD HH:MM:SS`（与客户端 nowStamp 同格式）。"""
    return value.strftime("%Y-%m-%d %H:%M:%S") if value else ""


def _parse_ts(text) -> datetime.datetime | None:
    """把 payload 里的时间戳解析回来；看不懂（老客户端没带）就返回 None。

    手机 `nowStamp()` 写的是带微秒的 `yyyy-MM-dd HH:mm:ss.SSSSSS`，服务器存的是
    不带微秒的 `%Y-%m-%d %H:%M:%S`。两种都得认 —— 只认后者的话，手机传来的
    时间戳会被当成"没带"，回退成导入时刻，两端判"谁改得更近"就会永远偏向
    服务器（手机上较新的改动会被静默覆盖）。解析结果一律截到秒，与库里的
    精度一致，来回搬不累积偏差。
    """
    if not text:
        return None
    text = str(text).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.datetime.strptime(text, fmt).replace(microsecond=0)
        except ValueError:
            continue
    # ISO 风格（带 T、可能带时区）也认一下：别家客户端或脚本可能这么发
    try:
        return datetime.datetime.fromisoformat(text).replace(microsecond=0)
    except ValueError:
        return None


def _ts_or_now(text) -> datetime.datetime:
    """payload 没带时间戳（老客户端）就用当下 —— 总比留空强，后面对得上。

    截到秒：库里存的精度与 `_ts()` 导出的格式一致，来回搬不会累积偏差。
    """
    return _parse_ts(text) or utcnow()


def export_list(db: Session, lst: ItemList) -> dict:
    """把一份清单连它的分组、分类、物料、分配、采购记录、费用整份取出来。"""
    rooms = (db.query(Room).filter(Room.list_id == lst.id)
             .order_by(Room.sort, Room.id).all())
    categories = (db.query(Category).filter(Category.list_id == lst.id)
                  .order_by(Category.sort, Category.id).all())
    items = (db.query(Item).filter(Item.list_id == lst.id)
             .order_by(Item.sort, Item.id).all())
    expenses = (db.query(ExtraExpense).filter(ExtraExpense.list_id == lst.id)
                .order_by(ExtraExpense.id).all())

    return {
        "version": FORMAT_VERSION,
        "list": {"name": lst.name, "note": lst.note or "", "sort": lst.sort or 0,
                 "code": lst.code or "",
                 "created_at": _ts(lst.created_at), "updated_at": _ts(lst.updated_at)},
        "rooms": [{"id": r.id, "name": r.name, "sort": r.sort or 0,
                   "created_at": _ts(r.created_at), "updated_at": _ts(r.updated_at)}
                  for r in rooms],
        "categories": [{"id": c.id, "name": c.name, "sort": c.sort or 0,
                        "created_at": _ts(c.created_at), "updated_at": _ts(c.updated_at)}
                       for c in categories],
        "items": [_item_payload(item) for item in items],
        "expenses": [_expense_payload(e) for e in expenses],
    }


def _item_payload(item: Item) -> dict:
    return {
        "id": item.id,
        "name": item.name,
        "category_id": item.category_id,
        "unit": item.unit or "个",
        "brand": item.brand or "",
        "model": item.model or "",
        "qty_total": item.qty_total or 0,
        "price": item.price or 0,
        "discount_price": item.discount_price,
        "note": item.note or "",
        "sort": item.sort or 0,
        # 回收站里的也一起走：两端回收站保持一致，捞回来的东西才不会一边有一边没有
        "deleted_at": item.deleted_at.isoformat() if item.deleted_at else None,
        "created_at": _ts(item.created_at),
        "updated_at": _ts(item.updated_at),
        "allocations": [
            {
                "id": a.id,
                "room_id": a.room_id,
                "qty": a.qty or 0,
                "price_override": a.price_override,
                "note": a.note or "",
            }
            for a in item.allocations
        ],
        "records": [
            {
                "id": r.id,
                "qty": r.qty or 0,
                "amount": r.amount or 0,
                "date": r.date or "",
                "note": r.note or "",
                "vendor": r.vendor or "",
                "order_no": r.order_no or "",
                # 多选的分组；老记录只写了单值 room_id 的也一并带上
                "room_ids": _record_room_ids(r),
                "created_at": _ts(r.created_at),
                "updated_at": _ts(r.updated_at),
            }
            for r in item.records
        ],
    }


def _record_room_ids(record: PurchaseRecord) -> list:
    ids = [rr.room_id for rr in record.rooms if rr.room_id]
    if not ids and record.room_id:
        ids = [record.room_id]
    return ids


def _expense_payload(expense: ExtraExpense) -> dict:
    return {
        "id": expense.id,
        "kind": expense.kind or "运费",
        "amount": expense.amount or 0,
        "date": expense.date or "",
        "vendor": expense.vendor or "",
        "order_no": expense.order_no or "",
        "note": expense.note or "",
        "item_id": expense.item_id,
        "created_at": _ts(expense.created_at),
        "updated_at": _ts(expense.updated_at),
    }


# ---------------------------------------------------------------- 指纹

def fingerprint(payload: dict) -> str:
    """内容指纹：把语义字段规范化后算 SHA256。

    行序不影响结果 —— 同一份数据无论是手机新建上来的、还是覆盖重写过的，
    都应该得到同一个指纹，否则每次同步都会误判成"服务器又变过了"。
    """
    return hashlib.sha256(_canonical(payload).encode("utf-8")).hexdigest()


_TS_KEYS = ("created_at", "updated_at")


def _strip_ts(row: dict) -> dict:
    """算指纹时把时间戳剔除。

    同步本身会刷新 `updated_at`（哪怕内容一字未改），带进指纹就会每次同步后
    抖动、"服务器又变过"被误判出来 —— 指纹只该反映**内容**。
    """
    return {k: v for k, v in row.items() if k not in _TS_KEYS}


def _canonical(payload: dict) -> str:
    """语义内容指纹。

    **剔除 id 与全部引用 id**（`id`、`category_id`、`room_id`、`item_id`，
    引用改用**被指方的名字**参与指纹）：覆盖时 `_clear` 加重插会让数据库
    重新分配这些 id —— 从前行 id 有空洞（删过东西）的内容，覆盖一次 id 就
    重排一次，指纹跟着漂，另一端会误判"服务器又变过了"、其 id 映射也随之
    失配。名字才是这种清单里的天然身份：分组/分类在本清单内本来按名字唯一，
    物料重名时两行的分配/记录等其余字段仍参与区分。
    """
    def key(row) -> str:
        return json.dumps(row, ensure_ascii=False, sort_keys=True,
                          separators=(",", ":"))

    def _name_of(table: str, rows: list) -> dict:
        return {row.get("id"): row.get("name", "") for row in rows
                if row.get("id") is not None}

    rooms = payload.get("rooms", [])
    categories = payload.get("categories", [])
    items = payload.get("items", [])
    room_name = _name_of("rooms", rooms)
    category_name = _name_of("categories", categories)
    item_name = _name_of("items", items)

    def _ref(table: str, mapping: dict, ref_id):
        """引用翻译成名字；找不到（悬空引用）用原值占位，别把不同引用混同。"""
        if ref_id is None:
            return None
        return mapping.get(ref_id, f"#unresolved-{table}-{ref_id}")

    canonical_items = []
    for item in items:
        records = [
            {**_strip_ts(r),
             "room_ids": sorted(_ref("rooms", room_name, rid)
                                for rid in (r.get("room_ids") or []))}
            for r in item.get("records", [])
        ]
        canonical_items.append({
            **{k: v for k, v in _strip_ts(item).items()
               if k not in ("id", "category_id")},
            "category": _ref("categories", category_name,
                             item.get("category_id")),
            "allocations": sorted(
                ({**_strip_ts(a), "room": _ref("rooms", room_name,
                                               a.get("room_id"))}
                 for a in item.get("allocations", [])), key=key),
            "records": sorted(records, key=key),
        })

    canonical_expenses = sorted(
        ({**_strip_ts(e), "item": _ref("items", item_name, e.get("item_id"))}
         for e in payload.get("expenses", [])), key=key)

    normalized = {
        "version": payload.get("version", FORMAT_VERSION),
        "list": _strip_ts(payload.get("list", {})),
        "rooms": sorted(({k: v for k, v in _strip_ts(r).items() if k != "id"}
                         for r in rooms), key=key),
        "categories": sorted(({k: v for k, v in _strip_ts(c).items() if k != "id"}
                              for c in categories), key=key),
        "items": sorted(canonical_items, key=key),
        "expenses": canonical_expenses,
    }
    return json.dumps(normalized, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"))


# ---------------------------------------------------------------- 导入

def import_list(db: Session, payload: dict, target: ItemList | None = None,
                name: str | None = None) -> ItemList:
    """把一份 payload 灌进数据库，返回目标清单。

    - `target=None`：新建一份清单（名字取 payload 里的，重名也照样并存）
    - 给了 `target`：**整份替换**它的内容 —— 手机上没有的东西，服务器上也会
      消失。这正是"以我为准"该有的样子。

    调用方负责 `db.commit()`。
    """
    incoming_code = payload["list"].get("code")
    if target is None:
        target = ItemList(
            name=_clean_name(name or payload["list"].get("name")),
            note=payload["list"].get("note", ""),
            sort=payload["list"].get("sort", 0),
            code=_free_code(db, incoming_code),
            created_at=_ts_or_now(payload["list"].get("created_at")),
            updated_at=_ts_or_now(payload["list"].get("updated_at")),
        )
        db.add(target)
        db.flush()
    else:
        _clear(db, target)
        target.note = payload["list"].get("note", "")
        target.sort = payload["list"].get("sort", 0)
        # 覆盖的是内容：清单的"创建时间"还是原来那个，修改时间**跟推送方走**。
        # 从前无条件刷成 now()，等于说"服务器这份永远比手机新" —— 两端判
        # "谁改得更近"时手机上较新的改动反而会被静默覆盖掉。
        target.updated_at = _ts_or_now(payload["list"].get("updated_at"))
        # 名字不动：覆盖的是内容，清单还是原来那一份。
        # 编号跟推送方走 —— 手机覆盖之后两边编号要一致，对不上就看不出是同一份了；
        # 该编号已被别的清单占用时保持原样，免得撞号。
        wanted = codes.normalize(incoming_code)
        if wanted and wanted != target.code and not (
                db.query(ItemList).filter(ItemList.code == wanted,
                                          ItemList.id != target.id).first()):
            target.code = wanted
        if not target.code:
            target.code = _free_code(db, None)

    room_map: dict = {}
    room_id_by_name: dict = {}
    for row in payload.get("rooms", []):
        name = row["name"]
        # 同名分组/分类在清单内只允许一份（表上有唯一约束）。正常客户端不会
        # 发来重复的，但一旦发来，整份同步会以 IntegrityError 失败 —— 那等于
        # 用户的数据完全传不上来。重复的并到先出现的那份上，让数据能传上去。
        if name in room_id_by_name:
            room_map[row.get("id")] = room_id_by_name[name]
            continue
        room = Room(list_id=target.id, name=name, sort=row.get("sort", 0),
                    created_at=_ts_or_now(row.get("created_at")),
                    updated_at=_ts_or_now(row.get("updated_at")))
        db.add(room)
        db.flush()
        room_id_by_name[name] = room.id
        room_map[row.get("id")] = room.id

    category_map: dict = {}
    category_id_by_name: dict = {}
    for row in payload.get("categories", []):
        name = row["name"]
        if name in category_id_by_name:
            category_map[row.get("id")] = category_id_by_name[name]
            continue
        category = Category(list_id=target.id, name=name,
                            sort=row.get("sort", 0),
                            created_at=_ts_or_now(row.get("created_at")),
                            updated_at=_ts_or_now(row.get("updated_at")))
        db.add(category)
        db.flush()
        category_id_by_name[name] = category.id
        category_map[row.get("id")] = category.id

    item_map: dict = {}
    for row in payload.get("items", []):
        item = Item(
            list_id=target.id,
            name=row["name"],
            category_id=category_map.get(row.get("category_id")),
            unit=row.get("unit") or "个",
            brand=row.get("brand") or "",
            model=row.get("model") or "",
            qty_total=row.get("qty_total") or 0,
            price=row.get("price") or 0,
            discount_price=row.get("discount_price"),
            note=row.get("note") or "",
            sort=row.get("sort") or 0,
            deleted_at=_parse_dt(row.get("deleted_at")),
            created_at=_ts_or_now(row.get("created_at")),
            updated_at=_ts_or_now(row.get("updated_at")),
        )
        db.add(item)
        db.flush()
        item_map[row.get("id")] = item.id

        for alloc in row.get("allocations", []):
            room_id = room_map.get(alloc.get("room_id"))
            if room_id is None:
                # 分组没跟着来（客户端数据不一致）：跳过这条分配，不让整份同步失败
                continue
            db.add(Allocation(item_id=item.id, room_id=room_id,
                              qty=alloc.get("qty") or 0,
                              price_override=alloc.get("price_override"),
                              note=alloc.get("note") or ""))

        for rec in row.get("records", []):
            record = PurchaseRecord(
                item_id=item.id,
                qty=rec.get("qty") or 0,
                amount=rec.get("amount") or 0,
                date=rec.get("date") or "",
                note=rec.get("note") or "",
                vendor=rec.get("vendor") or "",
                order_no=rec.get("order_no") or "",
                created_at=_ts_or_now(rec.get("created_at")),
                updated_at=_ts_or_now(rec.get("updated_at")),
            )
            db.add(record)
            db.flush()
            for room_id in rec.get("room_ids") or []:
                mapped = room_map.get(room_id)
                if mapped is not None:
                    db.add(RecordRoom(record_id=record.id, room_id=mapped))
        db.flush()

    for row in payload.get("expenses", []):
        db.add(ExtraExpense(
            list_id=target.id,
            kind=row.get("kind") or "其他",
            amount=row.get("amount") or 0,
            date=row.get("date") or "",
            vendor=row.get("vendor") or "",
            order_no=row.get("order_no") or "",
            note=row.get("note") or "",
            item_id=item_map.get(row.get("item_id")),
            created_at=_ts_or_now(row.get("created_at")),
            updated_at=_ts_or_now(row.get("updated_at")),
        ))
    db.flush()
    return target


def _clear(db: Session, lst: ItemList) -> None:
    """清空一份清单的内容（覆盖前先腾地方）。先删挂在下层的，再删主表。"""
    item_ids = [row[0] for row in db.query(Item.id).filter(Item.list_id == lst.id)]
    if item_ids:
        record_ids = [row[0] for row in db.query(PurchaseRecord.id)
                      .filter(PurchaseRecord.item_id.in_(item_ids))]
        if record_ids:
            (db.query(RecordRoom).filter(RecordRoom.record_id.in_(record_ids))
             .delete(synchronize_session=False))
        (db.query(PurchaseRecord).filter(PurchaseRecord.item_id.in_(item_ids))
         .delete(synchronize_session=False))
        (db.query(Allocation).filter(Allocation.item_id.in_(item_ids))
         .delete(synchronize_session=False))
    (db.query(ExtraExpense).filter(ExtraExpense.list_id == lst.id)
     .delete(synchronize_session=False))
    (db.query(Item).filter(Item.list_id == lst.id).delete(synchronize_session=False))
    (db.query(Room).filter(Room.list_id == lst.id).delete(synchronize_session=False))
    (db.query(Category).filter(Category.list_id == lst.id)
     .delete(synchronize_session=False))
    db.flush()


def _clean_name(base: str) -> str:
    """清单名只做清理，**不查重、不加后缀**。

    名字允许重复，编号才是身份：从手机搬一份叫「采购清单」的上来的，服务器上
    本来就有一份同名的，那就并存两份、各自独立 —— 从前会给新的加「 2」后缀，
    那是把名字当身份用的后遗症，客户端反而认不出哪份是哪份了。
    """
    return (base or "").strip()[:50] or "未命名清单"


def _free_code(db: Session, raw) -> str:
    """能给就用传来的编号，否则现发一个不撞的。"""
    wanted = codes.normalize(raw)
    if wanted and not db.query(ItemList).filter(ItemList.code == wanted).first():
        return wanted
    for _ in range(50):
        candidate = codes.new_code()
        if not db.query(ItemList).filter(ItemList.code == candidate).first():
            return candidate
    raise ValueError("清单编号生成失败，请稍后再试")


def _parse_dt(text):
    if not text:
        return None
    try:
        return datetime.datetime.fromisoformat(str(text))
    except ValueError:
        return None

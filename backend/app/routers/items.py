import datetime

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import category_in_list, current_list, item_in_list
from ..models import (Allocation, Category, Item, ItemList, PurchaseRecord, utcnow,
                      RecordRoom, Room)
from ..schemas import ItemIn, ItemOut, ItemPatchIn, RecordIn, RecordPatchIn, BatchDeleteIn
from ..services import compute

router = APIRouter(prefix="/api/items", tags=["items"])
records_router = APIRouter(prefix="/api/records", tags=["records"])


def _room_of_list(db: Session, room_id: int, lst: ItemList) -> Room:
    room = db.get(Room, room_id)
    if room is None or room.list_id != lst.id:
        raise HTTPException(400, f"分组 {room_id} 不属于当前清单")
    return room


def _apply_allocations(db: Session, item: Item, allocs, lst: ItemList):
    item.allocations.clear()
    for a in allocs:
        if not a.qty:
            continue
        _room_of_list(db, a.room_id, lst)
        item.allocations.append(Allocation(
            room_id=a.room_id, qty=a.qty,
            price_override=a.price_override, note=a.note or ""))


def _record_rooms(db: Session, data, lst: ItemList) -> list[RecordRoom]:
    """这笔付款涉及的分组（去重、校验归属）。老客户端的单值 room_id 也认。"""
    ids = list(dict.fromkeys(
        list(data.room_ids or []) + ([data.room_id] if data.room_id else [])))
    for room_id in ids:
        _room_of_list(db, room_id, lst)
    return [RecordRoom(room_id=room_id) for room_id in ids]


def _apply_records(db: Session, item: Item, records, lst: ItemList):
    item.records.clear()
    for r in records:
        item.records.append(PurchaseRecord(
            qty=r.qty or 0, amount=r.amount or 0, date=r.date or "", note=r.note or "",
            vendor=r.vendor or "", order_no=r.order_no or "",
            rooms=_record_rooms(db, r, lst)))
    item.bought = compute.item_status(item) == "done"


@router.get("", response_model=list[ItemOut])
def list_items(category_id: int | None = None, q: str | None = None,
               status: str | None = Query(default=None),
               lst: ItemList = Depends(current_list),
               db: Session = Depends(get_db)):
    query = db.query(Item).filter(Item.list_id == lst.id, Item.alive())
    if category_id is not None:
        query = query.filter(Item.category_id == category_id)
    if q:
        query = query.filter(Item.name.contains(q))
    items = query.order_by(Item.sort, Item.id).all()
    views = [compute.item_dict(i) for i in items]
    if status is not None:
        views = [v for v in views if v["status"] == status]
    return views


@router.post("", response_model=ItemOut)
def create_item(data: ItemIn, lst: ItemList = Depends(current_list),
                db: Session = Depends(get_db)):
    item = Item(list_id=lst.id, name=data.name,
                category_id=category_in_list(db, data.category_id, lst),
                unit=data.unit,
                brand=data.brand or "", model=data.model or "",
                qty_total=data.qty_total, price=data.price,
                discount_price=data.discount_price, note=data.note)
    if data.allocations is not None:
        _apply_allocations(db, item, data.allocations, lst)
    if data.records is not None:
        _apply_records(db, item, data.records, lst)
    db.add(item)
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.get("/{item_id}", response_model=ItemOut)
def get_item(item_id: int, lst: ItemList = Depends(current_list),
             db: Session = Depends(get_db)):
    return compute.item_dict(item_in_list(db, item_id, lst))


@router.put("/{item_id}", response_model=ItemOut)
def update_item(item_id: int, data: ItemIn, lst: ItemList = Depends(current_list),
                db: Session = Depends(get_db)):
    item = item_in_list(db, item_id, lst)
    # 整条替换（含采购记录与分配）会覆盖掉这期间别处写进去的内容，
    # 所以客户端必须带回读取时的 rev；没带就按老客户端处理，不做校验
    if data.base_rev is not None and data.base_rev != (item.rev or 1):
        raise HTTPException(
            409, "这条物料在别处已经被修改过了（可能是另一台设备记了一笔采购），"
                 "请刷新后重新编辑，以免覆盖掉那边的改动")
    item.name = data.name
    item.category_id = category_in_list(db, data.category_id, lst)
    item.brand = data.brand or ""
    item.model = data.model or ""
    item.unit = data.unit
    item.qty_total = data.qty_total
    item.price = data.price
    item.discount_price = data.discount_price
    item.note = data.note
    if data.allocations is not None:
        _apply_allocations(db, item, data.allocations, lst)
    if data.records is not None:
        _apply_records(db, item, data.records, lst)
    item.bought = compute.item_status(item) == "done"
    item.touch()
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.patch("/{item_id}", response_model=ItemOut)
def patch_item(item_id: int, data: ItemPatchIn, lst: ItemList = Depends(current_list),
               db: Session = Depends(get_db)):
    item = item_in_list(db, item_id, lst)
    fields = data.model_dump(exclude_unset=True)
    if fields.get("category_id") is not None:
        category_in_list(db, fields["category_id"], lst)
    for field, value in fields.items():
        setattr(item, field, value)
    item.bought = compute.item_status(item) == "done"
    item.touch()
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.delete("/{item_id}")
def delete_item(item_id: int, lst: ItemList = Depends(current_list),
                db: Session = Depends(get_db)):
    """软删：移进回收站。它的分配与采购记录都留着 —— 一条物料的付款历史
    常常是几笔真实转账，删错了一次性清光代价太大（要彻底删去回收站里清）。"""
    item = item_in_list(db, item_id, lst)
    item.deleted_at = utcnow()
    item.touch()
    db.commit()
    return {"ok": True, "trashed": True}


# ---------- 采购记录 ----------

@router.post("/{item_id}/records", response_model=ItemOut)
def add_record(item_id: int, data: RecordIn, lst: ItemList = Depends(current_list),
               db: Session = Depends(get_db)):
    item = item_in_list(db, item_id, lst)
    item.records.append(PurchaseRecord(
        qty=data.qty or 0, amount=data.amount or 0,
        date=data.date or datetime.date.today().isoformat(),
        note=data.note or "", vendor=data.vendor or "", order_no=data.order_no or "",
        rooms=_record_rooms(db, data, lst)))
    item.bought = compute.item_status(item) == "done"
    item.touch()
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.delete("/{item_id}/records", response_model=ItemOut)
def clear_records(item_id: int, lst: ItemList = Depends(current_list),
                  db: Session = Depends(get_db)):
    item = item_in_list(db, item_id, lst)
    item.records.clear()
    item.bought = compute.item_status(item) == "done"
    item.touch()
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


def _record_in_list(db: Session, record_id: int, lst: ItemList) -> PurchaseRecord:
    rec = db.get(PurchaseRecord, record_id)
    if rec is None or rec.item is None or rec.item.list_id != lst.id:
        raise HTTPException(404, "采购记录不存在")
    return rec


@records_router.put("/{record_id}", response_model=ItemOut)
def update_record(record_id: int, data: RecordPatchIn,
                  lst: ItemList = Depends(current_list),
                  db: Session = Depends(get_db)):
    rec = _record_in_list(db, record_id, lst)
    fields = data.model_dump(exclude_unset=True)
    # 涉及的分组单独处理（涉及另一张表）：
    #   传了 room_ids（哪怕空列表）→ 按它整体替换；
    #   只传了老的单值 room_id → 换成"只有这一间"；两个都不传 → 不动
    if "room_ids" in fields and fields["room_ids"] is not None:
        ids = list(dict.fromkeys(fields["room_ids"]))
        for room_id in ids:
            _room_of_list(db, room_id, lst)
        rec.rooms = [RecordRoom(room_id=room_id) for room_id in ids]
    elif "room_id" in fields:
        ids = [fields["room_id"]] if fields["room_id"] else []
        if ids:
            _room_of_list(db, ids[0], lst)
        rec.rooms = [RecordRoom(room_id=room_id) for room_id in ids]
    fields.pop("room_ids", None)
    fields.pop("room_id", None)
    # 显式传 null 一律当成"这个字段不改"：Kotlin/Swift 这类客户端会把整个对象
    # 序列化出去（含没动过的 null 字段），不这样兜的话，一次"只改金额"的保存
    # 会把数量、日期一起清空。要清空就传空串或空列表。
    for field in [f for f, v in fields.items() if v is None]:
        fields.pop(field)
    for field, value in fields.items():
        setattr(rec, field, value)
    item = rec.item
    item.bought = compute.item_status(item) == "done"
    item.touch()
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.post("/batch/delete")
def batch_delete(data: BatchDeleteIn, lst: ItemList = Depends(current_list),
                 db: Session = Depends(get_db)):
    if not data.ids:
        raise HTTPException(400, "未选择任何物料")
    # 只认当前清单里的 id：别的清单即使 id 撞上了也不动
    ids = [row[0] for row in db.query(Item.id)
           .filter(Item.id.in_(data.ids), Item.list_id == lst.id,
                   Item.alive()).all()]
    if ids:
        # 批量软删：一次 UPDATE 搞定，rev 一起顶上去，让正在编辑这台设备的
        # 其它客户端保存时拿到 409 而不是把删掉的条目又写回来
        db.query(Item).filter(Item.id.in_(ids)).update(
            {"deleted_at": utcnow(), "rev": Item.rev + 1},
            synchronize_session=False)
        db.commit()
    return {"deleted": len(ids), "trashed": True}


@records_router.delete("/{record_id}", response_model=ItemOut)
def delete_record(record_id: int, lst: ItemList = Depends(current_list),
                  db: Session = Depends(get_db)):
    rec = _record_in_list(db, record_id, lst)
    item = rec.item
    db.delete(rec)
    item.bought = compute.item_status(item) == "done"
    item.touch()
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)

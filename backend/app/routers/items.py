import datetime

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Allocation, Item, PurchaseRecord
from ..schemas import ItemIn, ItemOut, ItemPatchIn, RecordIn, RecordPatchIn, BatchDeleteIn
from ..services import compute

router = APIRouter(prefix="/api/items", tags=["items"])
records_router = APIRouter(prefix="/api/records", tags=["records"])


def _apply_allocations(db: Session, item: Item, allocs):
    item.allocations.clear()
    for a in allocs:
        if not a.qty:
            continue
        item.allocations.append(Allocation(
            room_id=a.room_id, qty=a.qty,
            price_override=a.price_override, note=a.note or ""))


def _apply_records(item: Item, records):
    item.records.clear()
    for r in records:
        item.records.append(PurchaseRecord(
            qty=r.qty or 0, amount=r.amount or 0, date=r.date or "", note=r.note or ""))
    item.bought = compute.item_status(item) == "done"


@router.get("", response_model=list[ItemOut])
def list_items(category_id: int | None = None, q: str | None = None,
               status: str | None = Query(default=None),
               db: Session = Depends(get_db)):
    query = db.query(Item)
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
def create_item(data: ItemIn, db: Session = Depends(get_db)):
    item = Item(name=data.name, category_id=data.category_id, unit=data.unit,
                brand=data.brand or "", model=data.model or "",
                qty_total=data.qty_total, price=data.price,
                discount_price=data.discount_price, note=data.note)
    if data.allocations is not None:
        _apply_allocations(db, item, data.allocations)
    if data.records is not None:
        _apply_records(item, data.records)
    db.add(item)
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.get("/{item_id}", response_model=ItemOut)
def get_item(item_id: int, db: Session = Depends(get_db)):
    item = db.get(Item, item_id)
    if not item:
        raise HTTPException(404, "物料不存在")
    return compute.item_dict(item)


@router.put("/{item_id}", response_model=ItemOut)
def update_item(item_id: int, data: ItemIn, db: Session = Depends(get_db)):
    item = db.get(Item, item_id)
    if not item:
        raise HTTPException(404, "物料不存在")
    item.name = data.name
    item.category_id = data.category_id
    item.brand = data.brand or ""
    item.model = data.model or ""
    item.unit = data.unit
    item.qty_total = data.qty_total
    item.price = data.price
    item.discount_price = data.discount_price
    item.note = data.note
    if data.allocations is not None:
        _apply_allocations(db, item, data.allocations)
    if data.records is not None:
        _apply_records(item, data.records)
    item.bought = compute.item_status(item) == "done"
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.patch("/{item_id}", response_model=ItemOut)
def patch_item(item_id: int, data: ItemPatchIn, db: Session = Depends(get_db)):
    item = db.get(Item, item_id)
    if not item:
        raise HTTPException(404, "物料不存在")
    for field, value in data.model_dump(exclude_unset=True).items():
        setattr(item, field, value)
    item.bought = compute.item_status(item) == "done"
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.delete("/{item_id}")
def delete_item(item_id: int, db: Session = Depends(get_db)):
    item = db.get(Item, item_id)
    if not item:
        raise HTTPException(404, "物料不存在")
    from ..models import PurchaseRecord, Allocation
    db.query(PurchaseRecord).filter(PurchaseRecord.item_id == item_id).delete()
    db.query(Allocation).filter(Allocation.item_id == item_id).delete()
    db.delete(item)
    db.commit()
    return {"ok": True}


# ---------- 采购记录 ----------

@router.post("/{item_id}/records", response_model=ItemOut)
def add_record(item_id: int, data: RecordIn, db: Session = Depends(get_db)):
    item = db.get(Item, item_id)
    if not item:
        raise HTTPException(404, "物料不存在")
    item.records.append(PurchaseRecord(
        qty=data.qty or 0, amount=data.amount or 0,
        date=data.date or datetime.date.today().isoformat(),
        note=data.note or ""))
    item.bought = compute.item_status(item) == "done"
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.delete("/{item_id}/records", response_model=ItemOut)
def clear_records(item_id: int, db: Session = Depends(get_db)):
    item = db.get(Item, item_id)
    if not item:
        raise HTTPException(404, "物料不存在")
    item.records.clear()
    item.bought = compute.item_status(item) == "done"
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@records_router.put("/{record_id}", response_model=ItemOut)
def update_record(record_id: int, data: RecordPatchIn, db: Session = Depends(get_db)):
    rec = db.get(PurchaseRecord, record_id)
    if not rec:
        raise HTTPException(404, "采购记录不存在")
    for field, value in data.model_dump(exclude_unset=True).items():
        setattr(rec, field, value)
    item = rec.item
    item.bought = compute.item_status(item) == "done"
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)


@router.post("/batch/delete")
def batch_delete(data: BatchDeleteIn, db: Session = Depends(get_db)):
    count = len(data.ids)
    if not count:
        raise HTTPException(400, "未选择任何物料")
    from ..models import PurchaseRecord, Allocation
    # 先删关联数据，再删物料
    db.query(PurchaseRecord).filter(PurchaseRecord.item_id.in_(data.ids)).delete(synchronize_session=False)
    db.query(Allocation).filter(Allocation.item_id.in_(data.ids)).delete(synchronize_session=False)
    db.query(Item).filter(Item.id.in_(data.ids)).delete(synchronize_session=False)
    db.commit()
    return {"deleted": count}


@records_router.delete("/{record_id}", response_model=ItemOut)
def delete_record(record_id: int, db: Session = Depends(get_db)):
    rec = db.get(PurchaseRecord, record_id)
    if not rec:
        raise HTTPException(404, "采购记录不存在")
    item = rec.item
    db.delete(rec)
    item.bought = compute.item_status(item) == "done"
    db.commit()
    db.refresh(item)
    return compute.item_dict(item)

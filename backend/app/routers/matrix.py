from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_list, item_in_list, room_in_list
from ..models import Allocation, Item, ItemList, Room
from ..schemas import MatrixCellIn
from ..services import compute

router = APIRouter(prefix="/api/matrix", tags=["matrix"])


@router.get("")
def get_matrix(lst: ItemList = Depends(current_list),
               db: Session = Depends(get_db)):
    rooms = (db.query(Room).filter(Room.list_id == lst.id)
             .order_by(Room.sort, Room.id).all())
    items = (db.query(Item).filter(Item.list_id == lst.id, Item.alive())
             .order_by(Item.sort, Item.id).all())
    item_views = []
    for i in items:
        cover = compute.allocation_paid_cover(i)
        status = compute.item_status(i)
        item_views.append({
            "id": i.id,
            "name": i.name,
            "model": i.model,
            "category_name": i.category.name if i.category else None,
            "unit": i.unit,
            "price": i.price,
            "discount_price": i.discount_price,
            "bought": status == "done",
            "status": status,
            "paid_qty": compute.item_paid_qty(i),
            "total_qty": compute.item_total_qty(i),
            "total_qty_source": "allocations" if i.allocations else "item",
            "list_total": compute.item_list_total(i),
            "cells": {
                a.room_id: {
                    "qty": a.qty,
                    "price_override": a.price_override,
                    "paid_qty": cover.get(a.id, 0),
                    "note": a.note,
                }
                for a in i.allocations
            },
        })
    return {
        "rooms": [{"id": r.id, "name": r.name} for r in rooms],
        "items": item_views,
    }


@router.put("/cell")
def put_cell(data: MatrixCellIn, lst: ItemList = Depends(current_list),
             db: Session = Depends(get_db)):
    item = item_in_list(db, data.item_id, lst)
    room_in_list(db, data.room_id, lst)
    alloc = (db.query(Allocation)
             .filter(Allocation.item_id == data.item_id,
                     Allocation.room_id == data.room_id)
             .first())
    if not data.qty:  # 0 或空 → 清除该格
        if alloc:
            db.delete(alloc)
            item.touch()
            db.commit()
        return {"ok": True, "deleted": bool(alloc)}
    if not alloc:
        alloc = Allocation(item_id=data.item_id, room_id=data.room_id)
        db.add(alloc)
    alloc.qty = data.qty
    alloc.price_override = data.price_override
    alloc.note = data.note or ""
    item.touch()
    db.commit()
    return {"ok": True, "deleted": False}

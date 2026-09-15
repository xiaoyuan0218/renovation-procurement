from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Allocation, Item, Room
from ..schemas import MatrixCellIn
from ..services import compute

router = APIRouter(prefix="/api/matrix", tags=["matrix"])


@router.get("")
def get_matrix(db: Session = Depends(get_db)):
    rooms = db.query(Room).order_by(Room.sort, Room.id).all()
    items = db.query(Item).order_by(Item.sort, Item.id).all()
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
def put_cell(data: MatrixCellIn, db: Session = Depends(get_db)):
    item = db.get(Item, data.item_id)
    if not item:
        raise HTTPException(404, "物料不存在")
    if not db.get(Room, data.room_id):
        raise HTTPException(404, "房间不存在")
    alloc = (db.query(Allocation)
             .filter(Allocation.item_id == data.item_id,
                     Allocation.room_id == data.room_id)
             .first())
    if not data.qty:  # 0 或空 → 清除该格
        if alloc:
            db.delete(alloc)
            db.commit()
        return {"ok": True, "deleted": bool(alloc)}
    if not alloc:
        alloc = Allocation(item_id=data.item_id, room_id=data.room_id)
        db.add(alloc)
    alloc.qty = data.qty
    alloc.price_override = data.price_override
    alloc.note = data.note or ""
    db.commit()
    return {"ok": True, "deleted": False}

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Category, Item, Room
from ..services import compute

router = APIRouter(prefix="/api/summary", tags=["summary"])


@router.get("", response_model=None)
def get_summary(db: Session = Depends(get_db)):
    items = db.query(Item).all()
    views = [compute.item_dict(i) for i in items]

    totals = {
        "list_total": round(sum(v["list_total"] for v in views), 2),
        "discount_total": round(sum(v["discount_total"] for v in views), 2),
        "paid_total": round(sum(v["paid"] for v in views), 2),
        "item_count": len(views),
    }
    # 未付 = Σ(未付数量 × 单价)，与单条物料的口径一致
    totals["unpaid_total"] = round(sum(v["unpaid"] for v in views), 2)

    # 采购状态统计（数量维度）：done 已买完 / partial 部分已买 / unbought 未买 / none 无需采购
    status_count = {"done": 0, "partial": 0, "unbought": 0, "none": 0}
    for v in views:
        status_count[v["status"]] = status_count.get(v["status"], 0) + 1
    totals["status_count"] = status_count
    totals["bought_count"] = status_count["done"]
    totals["partial_count"] = status_count["partial"]

    cats = db.query(Category).order_by(Category.sort, Category.id).all()
    by_category = []
    for c in cats:
        sub = [v for v in views if v["category_id"] == c.id]
        by_category.append({
            "id": c.id, "name": c.name,
            "list_total": round(sum(v["list_total"] for v in sub), 2),
            "discount_total": round(sum(v["discount_total"] for v in sub), 2),
            "paid_total": round(sum(v["paid"] for v in sub), 2),
        })
    uncategorized = [v for v in views if v["category_id"] is None]
    if uncategorized:
        by_category.append({
            "id": None, "name": "未分类",
            "list_total": round(sum(v["list_total"] for v in uncategorized), 2),
            "discount_total": round(sum(v["discount_total"] for v in uncategorized), 2),
            "paid_total": round(sum(v["paid"] for v in uncategorized), 2),
        })

    rooms = db.query(Room).order_by(Room.sort, Room.id).all()
    by_room = []
    for r in rooms:
        qty, total = 0.0, 0.0
        for i in items:
            for a in i.allocations:
                if a.room_id == r.id:
                    qty += a.qty or 0
                    unit = a.price_override if a.price_override is not None else (i.price or 0)
                    total += (a.qty or 0) * unit
        by_room.append({"id": r.id, "name": r.name,
                        "qty": round(qty, 2), "list_total": round(total, 2)})

    unbought = [v for v in views if v["status"] in ("unbought", "partial")]
    unbought.sort(key=lambda v: -v["discount_total"])

    return {
        "totals": totals,
        "by_category": by_category,
        "by_room": by_room,
        "unbought": unbought,
    }

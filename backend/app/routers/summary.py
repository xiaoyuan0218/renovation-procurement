from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_list
from ..models import Category, ExtraExpense, Item, ItemList, Room
from ..services import compute, dates

router = APIRouter(prefix="/api/summary", tags=["summary"])


@router.get("", response_model=None)
def get_summary(lst: ItemList = Depends(current_list),
                db: Session = Depends(get_db)):
    items = db.query(Item).filter(Item.list_id == lst.id, Item.alive()).all()
    views = [compute.item_dict(i) for i in items]

    totals = {
        "list_total": round(sum(v["list_total"] for v in views), 2),
        "discount_total": round(sum(v["discount_total"] for v in views), 2),
        "paid_total": round(sum(v["paid"] for v in views), 2),
        "item_count": len(views),
    }
    # 未付 = Σ(未付数量 × 单价)，与单条物料的口径一致
    totals["unpaid_total"] = round(sum(v["unpaid"] for v in views), 2)
    # 两个口径各自的三段拆分，聚合后等式依然严格成立：
    #   已付 + 实际优惠 + 未付 = 原价合计
    #   已付 + 日常价优惠 + 日常价未付 = 日常价合计
    totals["daily_unpaid_total"] = round(sum(v["daily_unpaid"] for v in views), 2)
    totals["actual_discount_total"] = round(sum(v["actual_discount"] for v in views), 2)
    totals["daily_discount_total"] = round(sum(v["daily_discount"] for v in views), 2)

    # 采购状态统计（数量维度）：done 已买完 / partial 部分已买 / unbought 未买 / none 无需采购
    status_count = {"done": 0, "partial": 0, "unbought": 0, "none": 0}
    for v in views:
        status_count[v["status"]] = status_count.get(v["status"], 0) + 1
    totals["status_count"] = status_count
    totals["bought_count"] = status_count["done"]
    totals["partial_count"] = status_count["partial"]

    cats = (db.query(Category).filter(Category.list_id == lst.id)
            .order_by(Category.sort, Category.id).all())
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

    rooms = (db.query(Room).filter(Room.list_id == lst.id)
             .order_by(Room.sort, Room.id).all())
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

    # 按月已付：采购是跨月推进的，付款记录里的日期就是时间线的原料。
    # 老数据里可能存着 "2026-09-14 00:00:00" 这类脏值，统一走读出口径归一化。
    months: dict[str, float] = {}
    undated = 0.0
    for item in items:
        for r in item.records:
            month = dates.month_of(r.date)
            if month:
                months[month] = months.get(month, 0.0) + (r.amount or 0)
            else:
                undated += r.amount or 0
    by_month = [{"month": m, "paid": round(v, 2)} for m, v in sorted(months.items())]

    # 额外费用（运费/安装费/辅料）：不参与上面的两个口径，单独汇总。
    # 三段拆分等式说的始终是"货款"，这块钱在等式之外。
    expenses = (db.query(ExtraExpense)
                .filter(ExtraExpense.list_id == lst.id).all())
    kind_sum: dict[str, float] = {}
    for e in expenses:
        kind = (e.kind or "其他").strip() or "其他"
        kind_sum[kind] = kind_sum.get(kind, 0.0) + (e.amount or 0)
    by_kind = [{"kind": k, "amount": round(v, 2)}
               for k, v in sorted(kind_sum.items(), key=lambda kv: -kv[1])]

    return {
        "totals": totals,
        "by_category": by_category,
        "by_room": by_room,
        "unbought": unbought,
        "by_month": by_month,
        # 没填日期的那些付款合计 —— 界面用来提示"还有多少钱没记日期"
        "by_month_undated": round(undated, 2),
        "expenses_total": round(sum(kind_sum.values()), 2),
        "expenses_by_kind": by_kind,
        "expenses_count": len(expenses),
    }

"""金额口径计算，全部取两位小数。

数量维度：
- 总数量：有布点明细时 = Σ布点数量，否则用物料自身的总量字段
- 实付数量 = Σ采购记录数量；采购状态由 实付数量 vs 总数量 推导
  （done 已买完 / partial 部分已买 / unbought 未买 / none 无需采购）

金额维度：
- 原价小计 = Σ(布点数量 × (房间覆盖单价 ?? 物料单价))；无布点 = 总量 × 单价
- 优惠小计 = 总数量 × (优惠单价 ?? 物料单价)
- 已付金额 = Σ采购记录金额；实付单价（均价）= 已付金额 ÷ 实付数量
- 未付金额 = 未付数量 × 单价（原价口径）
"""

ROUND = 2


def r2(x):
    return round(x or 0, ROUND)


def item_total_qty(item) -> float:
    if item.allocations:
        return r2(sum(a.qty or 0 for a in item.allocations))
    return r2(item.qty_total or 0)


def item_list_total(item) -> float:
    if item.allocations:
        total = 0.0
        for a in item.allocations:
            unit = a.price_override if a.price_override is not None else (item.price or 0)
            total += (a.qty or 0) * unit
        return r2(total)
    return r2((item.qty_total or 0) * (item.price or 0))


def item_discount_total(item) -> float:
    unit = item.discount_price if item.discount_price is not None else (item.price or 0)
    return r2(item_total_qty(item) * (unit or 0))


def item_paid_qty(item) -> float:
    return r2(sum(r.qty or 0 for r in item.records))


def item_paid(item) -> float:
    return r2(sum(r.amount or 0 for r in item.records))


def item_paid_price(item):
    """实付单价（均价）= 已付金额 ÷ 实付数量；数量为 0 时返回 None。"""
    qty = item_paid_qty(item)
    amt = item_paid(item)
    if qty > 0 and amt:
        return r2(amt / qty)
    return None


def item_unpaid_qty(item) -> float:
    return r2(max(0.0, item_total_qty(item) - item_paid_qty(item)))


def item_unpaid(item) -> float:
    """未付金额 = 未付数量 × 单价（原价口径）。"""
    return r2(item_unpaid_qty(item) * (item.price or 0))


def item_status(item) -> str:
    qty = item_total_qty(item)
    paid = item_paid_qty(item)
    if qty <= 0:
        return "none"
    if paid >= qty - 1e-9:
        return "done"
    if paid > 0:
        return "partial"
    return "unbought"


def allocation_paid_cover(item) -> dict:
    """按布点顺序用累计实付数量覆盖布点行，返回 {布点id: 已付数量}。

    采购记录不绑定房间：买回的数量按布点顺序逐行抵扣，
    被覆盖满的行即"该房间已买齐"。
    """
    remaining = item_paid_qty(item)
    result = {}
    for a in item.allocations:
        take = min(a.qty or 0, max(0.0, remaining))
        result[a.id] = r2(take)
        remaining -= take
    return result


def item_dict(item) -> dict:
    """统一的物料计算结果视图。"""
    return {
        "id": item.id,
        "name": item.name,
        "category_id": item.category_id,
        "category_name": item.category.name if item.category else None,
        "unit": item.unit,
        "brand": item.brand or "",
        "model": item.model or "",
        "qty_total": item.qty_total,
        "price": item.price,
        "discount_price": item.discount_price,
        "bought": item_status(item) == "done",
        "note": item.note,
        "total_qty": item_total_qty(item),
        "list_total": item_list_total(item),
        "discount_total": item_discount_total(item),
        "paid_qty": item_paid_qty(item),
        "paid_price": item_paid_price(item),
        "paid": item_paid(item),
        "unpaid_qty": item_unpaid_qty(item),
        "unpaid": item_unpaid(item),
        "status": item_status(item),
        "records": [
            {
                "id": r.id,
                "item_id": r.item_id,
                "qty": r.qty,
                "amount": r.amount,
                "unit_price": (r2(r.amount / r.qty) if (r.qty or 0) > 0 and r.amount else None),
                "date": r.date or "",
                "note": r.note or "",
            }
            for r in item.records
        ],
        "allocations": [
            {
                "id": a.id,
                "item_id": a.item_id,
                "room_id": a.room_id,
                "qty": a.qty,
                "price_override": a.price_override,
                "note": a.note,
            }
            for a in item.allocations
        ],
    }

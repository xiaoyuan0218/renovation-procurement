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
- 日常价未付 = 未付数量 × (优惠单价 ?? 物料单价)

两个口径各自都能被拆成三段，且严格成立（到分）：
- 已付 + 实际优惠 + 未付 = 原价小计
- 已付 + 日常价优惠 + 日常价未付 = 优惠小计

两个优惠都是"已买部分省下的钱"：已买数量按原价（或日常价）折出来的钱，
减去实际付出去的钱。实付价高于原价/日常价时为负，不是错误值。
"""

from . import dates

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


def item_daily_unpaid(item) -> float:
    """日常价未付 = 未付数量 × (优惠单价 ?? 物料单价)。"""
    unit = item.discount_price if item.discount_price is not None else (item.price or 0)
    return r2(item_unpaid_qty(item) * (unit or 0))


def item_actual_discount(item) -> float:
    """实际优惠 = 原价小计 − 已付 − 未付，即已买部分相对原价省下的钱。

    取残差而非独立计算，这样「已付 + 实际优惠 + 未付 = 原价小计」恒成立 ——
    有房间覆盖价时 原价小计 − 未付 并不等于 已买数量 × 物料单价。
    """
    return r2(item_list_total(item) - item_paid(item) - item_unpaid(item))


def item_daily_discount(item) -> float:
    """日常价优惠 = 日常价小计 − 已付 − 日常价未付，即已买部分相对日常价省下的钱。

    实付单价高于日常单价时该值为负，表示比日常价还多花了钱。
    """
    return r2(item_discount_total(item) - item_paid(item) - item_daily_unpaid(item))


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
    """各分组的已付覆盖 {分配id: 已付数量}。

    优先认**付款时写明的归属**（`PurchaseRecord.room_id`）——那是用户明确告诉
    我们的，按它算就是精确的：先买了餐厅的灯、记录也写了餐厅，客厅就不会被误标。

    没写归属的那些（老记录，或者记的时候懒得选）仍按老规矩处理：单分组、或整条
    已全部买齐时按分配顺序抵扣；多分组只买了一部分时一个都不标 —— 那种情况下
    "先买哪间"系统无从知晓，猜错比不显示更坏。
    """
    allocations = list(item.allocations)
    if not allocations:
        return {}

    covered: dict[int, float] = {}   # 分组 id → 已经算在它头上的数量
    unassigned = 0.0

    def _fill(room_ids: set, qty: float) -> None:
        """把这笔数量按分配顺序依次抵扣指定分组，扣满一间再下一间。"""
        remain = qty
        for a in allocations:
            if remain <= 0:
                break
            if a.room_id not in room_ids:
                continue
            cap = (a.qty or 0) - covered.get(a.room_id, 0.0)
            take = min(max(0.0, cap), remain)
            covered[a.room_id] = covered.get(a.room_id, 0.0) + take
            remain -= take

    for r in item.records:
        qty = r.qty or 0
        # 勾了分组就按勾的算；只写了单值 room_id 的老记录当成"只勾了那一间"
        room_ids = {rr.room_id for rr in r.rooms if rr.room_id}
        if not room_ids and r.room_id:
            room_ids = {r.room_id}
        if room_ids:
            _fill(room_ids, qty)
        else:
            unassigned += qty

    total = item_total_qty(item)
    paid = item_paid_qty(item)
    everything_done = total > 0 and paid >= total - 1e-9
    # 没写分组的那部分能不能放心按顺序摊开：只有"不存在谁先买"的问题时才行
    can_spread = len(allocations) == 1 or everything_done
    if unassigned and can_spread:
        _fill({a.room_id for a in allocations}, unassigned)

    # 一条都没写分组、又不是上面两种能确定的情况 —— 按老规矩：不猜
    if not covered and not can_spread:
        return {}
    return {a.id: r2(min(a.qty or 0, covered.get(a.room_id, 0.0)))
            for a in allocations}


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
        "daily_unpaid": item_daily_unpaid(item),
        "actual_discount": item_actual_discount(item),
        "daily_discount": item_daily_discount(item),
        "status": item_status(item),
        "rev": item.rev or 1,
        "created_at": item.created_at,
        "updated_at": item.updated_at,
        "records": [
            {
                "id": r.id,
                "item_id": r.item_id,
                "qty": r.qty,
                "amount": r.amount,
                "unit_price": (r2(r.amount / r.qty) if (r.qty or 0) > 0 and r.amount else None),
                # 老数据里可能存着 "2026-09-14 00:00:00" 这类脏值，读出来时归一化
                "date": dates.for_read(r.date),
                "note": r.note or "",
                "vendor": r.vendor or "",
                "order_no": r.order_no or "",
                # room_ids 是现在的写法（可多选）；room_id 留给老客户端看
                "room_ids": [rr.room_id for rr in r.rooms if rr.room_id],
                "room_id": next((rr.room_id for rr in r.rooms if rr.room_id), None),
                "created_at": r.created_at,
                "updated_at": r.updated_at,
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

"""生成口径对照数据：用后端的真实计算函数算出期望值，供手机端单测比对。

手机端 `domain/LocalCompute.kt` 与 `domain/SummaryCompute.kt` 是把
`app/services/compute.py` 与 `app/routers/summary.py` 翻译成 Kotlin 的，
翻译得对不对只有拿同一份数据跑两边才知道。这个脚本在**内存库**里造一批
覆盖各分支的场景，用后端函数算出结果，连同输入一起写成 JSON fixture；
手机端的单元测试再拿同样的输入跑一遍自己的实现，逐字段比对。

    python scripts/gen_compute_fixture.py

输出覆盖 `mobile/app/src/test/resources/compute_fixture.json`。
"""

import json
import sys
from datetime import datetime
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.db import Base  # noqa: E402
from app.models import (Allocation, Category, ExtraExpense, Item,  # noqa: E402
                        ItemList, PurchaseRecord, RecordRoom, Room)
from app.routers.summary import get_summary  # noqa: E402
from app.services import compute  # noqa: E402

OUT = (Path(__file__).resolve().parent.parent.parent
       / "mobile" / "app" / "src" / "test" / "resources" / "compute_fixture.json")

ROOMS = ["客厅", "主卧", "厨房"]   # 索引 = 手机端的 room id
CATEGORY = "照明"


def build_session():
    engine = create_engine("sqlite://")   # 内存库，不碰真实数据
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def seed(db):
    lst = ItemList(name="口径对照", note="", sort=0, created_at=datetime.now())
    db.add(lst)
    db.flush()
    rooms = []
    for i, name in enumerate(ROOMS):
        room = Room(list_id=lst.id, name=name, sort=i)
        db.add(room)
        rooms.append(room)
    cat = Category(list_id=lst.id, name=CATEGORY, sort=0)
    db.add(cat)
    db.flush()

    def item(name, category=True, **kw):
        it = Item(list_id=lst.id, name=name, category_id=cat.id if category else None, **kw)
        db.add(it)
        db.flush()
        return it

    def alloc(it, room_idx, qty, price_override=None):
        db.add(Allocation(item_id=it.id, room_id=rooms[room_idx].id, qty=qty,
                          price_override=price_override))
        db.flush()

    def record(it, qty, amount, room_idxs=None, date="", vendor=""):
        rec = PurchaseRecord(item_id=it.id, qty=qty, amount=amount, date=date, vendor=vendor)
        db.add(rec)
        db.flush()
        for idx in (room_idxs or []):
            db.add(RecordRoom(record_id=rec.id, room_id=rooms[idx].id))
        db.flush()
        return rec

    # 1. 没有布点，买了一半：未付按原价算，实际优惠是残差
    a = item("无布点-部分已付", qty_total=10, price=8.5, discount_price=8.0)
    record(a, 4, 32, date="2026-08-03", vendor="京东")

    # 2. 三间布点、只买一部分、记录没写归属：多分组又不齐 → 一格都不标
    b = item("多分组-未写归属-未买齐", price=12.0)
    for i, q in enumerate((2, 4, 6)):
        alloc(b, i, q)
    record(b, 3, 30)

    # 3. 三间布点、整条买齐、记录没写归属：按分配顺序依次抵扣
    c = item("多分组-未写归属-已买齐", price=10.0)
    for i, q in enumerate((1, 2, 3)):
        alloc(c, i, q)
    record(c, 6, 54)

    # 4. 记录写明了归属：只往勾选的那间算
    d = item("多分组-写明归属", price=20.0)
    for i, q in enumerate((2, 2, 2)):
        alloc(d, i, q)
    record(d, 2, 36, room_idxs=[2])

    # 5. 房间覆盖单价：原价小计要用覆盖价
    e = item("房间覆盖单价", price=30.0, discount_price=28.0)
    alloc(e, 0, 5, price_override=25.0)
    alloc(e, 1, 3)
    record(e, 5, 120)

    # 6. 总额与实付都带尾数：考舍入
    f = item("小数金额", qty_total=7, price=3.33, discount_price=2.99)
    record(f, 3, 9.99)
    record(f, 4, 13.34)

    # 7. 一点没买，而且**没有分类** —— 汇总里的"未分类"分支靠它
    g = item("完全未买-无分类", category=False, price=15.0, discount_price=13.5)
    alloc(g, 2, 4)

    # 8. 总量为 0：状态是 none
    h = item("总量为零", qty_total=0, price=9.0)

    # 9. 实付单价高于原价：优惠为负，不是错误值
    i2 = item("实付高于原价", qty_total=2, price=10.0)
    record(i2, 2, 30, date="2026-09-14 00:00:00")   # 脏日期：读出来要归一化成 09-14

    # 额外费用：不参与两个口径，单独汇总。空 kind 要回退成"其他"。
    def expense(kind, amount, date="", vendor=""):
        db.add(ExtraExpense(list_id=lst.id, kind=kind, amount=amount, date=date, vendor=vendor))
        db.flush()

    expense("运费", 80.0, date="2026-09-01", vendor="德邦")
    expense("安装费", 120.0, date="2026-09-10")
    expense("运费", 35.0)          # 没记日期
    expense("", 20.0)              # 类型空 → 归入"其他"

    db.commit()
    return lst, rooms, [a, b, c, d, e, f, g, h, i2]


def room_index(rooms, room_id):
    return next(i for i, r in enumerate(rooms) if r.id == room_id)


def main():
    db = build_session()
    lst, rooms, items = seed(db)

    cases = []
    for item in items:
        view = compute.item_dict(item)
        cover = compute.allocation_paid_cover(item)
        cases.append({
            "name": item.name,
            "item": {
                "qty_total": item.qty_total,
                "price": item.price,
                "discount_price": item.discount_price,
                "unit": item.unit,
                "brand": item.brand or "",
                "model": item.model or "",
                "note": item.note or "",
                "has_category": item.category_id is not None,
            },
            "allocations": [
                {"room": room_index(rooms, al.room_id), "qty": al.qty,
                 "price_override": al.price_override}
                for al in item.allocations
            ],
            "records": [
                {"qty": r.qty, "amount": r.amount, "date": r.date or "",
                 "vendor": r.vendor or "",
                 "rooms": [room_index(rooms, rr.room_id)
                           for rr in r.rooms if rr.room_id]}
                for r in item.records
            ],
            # 后端算出来的期望值，手机端要一模一样
            "expected": {
                "total_qty": view["total_qty"],
                "list_total": view["list_total"],
                "discount_total": view["discount_total"],
                "paid_qty": view["paid_qty"],
                "paid_price": view["paid_price"],
                "paid": view["paid"],
                "unpaid_qty": view["unpaid_qty"],
                "unpaid": view["unpaid"],
                "daily_unpaid": view["daily_unpaid"],
                "actual_discount": view["actual_discount"],
                "daily_discount": view["daily_discount"],
                "status": view["status"],
            },
            # 各分配的已付覆盖，键是分配在列表里的序号（手机端 id 从 1 开始顺排）。
            # 只列出后端真正标了的那些 —— "一个都不标"（空字典）本身就是要考的语义。
            "expected_cover": {str(idx + 1): cover[al.id]
                               for idx, al in enumerate(item.allocations)
                               if al.id in cover},
        })

    # 汇总：直接调后端的接口函数，拿到的是它就是会发给客户端的那份数据结构
    raw = get_summary(lst, db)
    summary = {
        "totals": raw["totals"],
        "by_category": [
            {"id": (idx + 1 if c["id"] is not None else None), "name": c["name"],
             "list_total": c["list_total"], "discount_total": c["discount_total"],
             "paid_total": c["paid_total"]}
            for idx, c in enumerate(raw["by_category"])
        ],
        "by_room": [
            {"id": room_index(rooms, r["id"]) + 1, "name": r["name"],
             "qty": r["qty"], "list_total": r["list_total"]}
            for r in raw["by_room"]
        ],
        # ids 用物料在 cases 里的下标 + 1，与手机端构造的 id 对得上；顺序也要一致
        "unbought_ids": [cases_index(cases, v["name"]) for v in raw["unbought"]],
        "by_month": raw["by_month"],
        "by_month_undated": raw["by_month_undated"],
        "expenses_total": raw["expenses_total"],
        "expenses_by_kind": raw["expenses_by_kind"],
        "expenses_count": raw["expenses_count"],
    }

    payload = {"rooms": ROOMS, "category": CATEGORY, "cases": cases, "summary": summary}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已写出 {len(cases)} 个物料场景 + 汇总 → {OUT}")
    t = summary["totals"]
    print(f"  合计：原价 {t['list_total']} 日常价 {t['discount_total']} 已付 {t['paid_total']} "
          f"未付 {t['unpaid_total']}")
    print(f"        实际优惠 {t['actual_discount_total']} 日常价优惠 {t['daily_discount_total']} "
          f"日常价未付 {t['daily_unpaid_total']}")
    print(f"  状态：{t['status_count']}  未买齐 {len(summary['unbought_ids'])} 项")
    print(f"  按月：{summary['by_month']}（无日期 {summary['by_month_undated']}）")
    print(f"  费用：合计 {summary['expenses_total']} 共 {summary['expenses_count']} 笔 "
          f"{summary['expenses_by_kind']}")
    db.close()


def cases_index(cases, name):
    """物料在 cases 里的下标 + 1 —— 手机端的物料 id 就是按这个顺序排的。"""
    return next(i + 1 for i, c in enumerate(cases) if c["name"] == name)


if __name__ == "__main__":
    main()

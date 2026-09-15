#!/usr/bin/env python
"""把历史表格与当前数据库做逐项对比，输出差异报告。

用法：
    .venv/Scripts/python backend/scripts/compare_with_excel.py [xlsx路径]

做法：先用应用自己的导入器把 Excel 灌进一个临时库（匹配逻辑与正式导入一致，
并复现「实付 → 采购记录」折算），再与实库逐项配对比较。这样对比结果和界面口径一致。
"""

import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from sqlalchemy import create_engine            # noqa: E402
from sqlalchemy.orm import sessionmaker         # noqa: E402

from app.db import Base, SessionLocal           # noqa: E402
from app.models import Item, PurchaseRecord     # noqa: E402
from app.services import compute                # noqa: E402
from app.services.excel_io import _apply_original, _parse_original  # noqa: E402

SEED_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "seed")


def _default_xlsx():
    """seed 目录里的表格：取第一个 xlsx。"""
    import glob
    files = sorted(glob.glob(os.path.join(SEED_DIR, "*.xlsx")))
    return files[0] if files else None

# 表格与应用的房间写法差异（表格把两处区域并成一列）
ROOM_ALIAS = {"玄关/阳台": "玄关", "过道": "阳台/过道"}


def norm(s):
    return re.sub(r"\s+", "", s or "").replace("（", "(").replace("）", ")")


def load_items(session):
    out = []
    for it in session.query(Item).all():
        out.append({
            "obj": it, "name": it.name, "n": norm(it.name),
            "cat": it.category.name if it.category else None,
            "qty": compute.item_total_qty(it),
            "price": it.price, "disc": it.discount_price,
            "list": compute.item_list_total(it),
            "dsc": compute.item_discount_total(it),
            "paid": compute.item_paid(it),
            "allocs": {a.room.name: (a.qty or 0, a.price_override) for a in it.allocations},
            "used": False,
        })
    return out


def build_excel_db(xlsx_path):
    """把 Excel 导入临时库，返回该库的会话工厂。"""
    parsed = _parse_original(xlsx_path)
    tmp = os.path.join(tempfile.mkdtemp(prefix="renovation_cmp_"), "excel.db")
    engine = create_engine(f"sqlite:///{tmp}")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)()
    report = {k: 0 for k in ("items_created", "items_matched", "allocations",
                             "records", "rooms_created", "categories_created")}
    report["warnings"] = []
    _apply_original(session, parsed, "replace", report)
    # 复现启动时的折算：把 Excel 的「实付」列变成一笔采购记录
    for item in session.query(Item).all():
        if item.records:
            continue
        amount = item.paid_amount or 0
        qty = (item.paid_qty or 0) or (item.bought_qty or 0)
        if not qty and amount and item.allocations:
            qty = sum(a.qty or 0 for a in item.allocations)
        elif not qty and amount:
            qty = item.qty_total or 0
        if amount or qty:
            item.records.append(PurchaseRecord(qty=qty, amount=amount))
    session.commit()
    return session


def match(excel_item, pool):
    """配对：同名 → 名称包含 → 单价相同。"""
    for a in pool:
        if not a["used"] and a["n"] == excel_item["n"]:
            return a
    cands = [a for a in pool if not a["used"]
             and (a["n"] in excel_item["n"] or excel_item["n"] in a["n"])]
    if not cands:
        p = excel_item["price"]
        cands = [a for a in pool if not a["used"] and p and a["price"] and abs(a["price"] - p) < 0.01]
    if not cands:
        return None
    if excel_item["price"]:
        cands.sort(key=lambda a: abs((a["price"] or 0) - excel_item["price"]))
    return cands[0]


def main():
    xlsx = sys.argv[1] if len(sys.argv) > 1 else _default_xlsx()
    if not xlsx or not os.path.isfile(xlsx):
        print(f"表格文件不存在：{xlsx}；请把 xlsx 放到 {SEED_DIR} 或传入路径")
        return
    xl = load_items(build_excel_db(xlsx))
    app = load_items(SessionLocal())

    pairs = []
    for e in xl:
        a = match(e, app)
        if a:
            a["used"] = True
        pairs.append((e, a))

    money_diff, room_diff, extra, missing = [], [], [], []
    for e, a in pairs:
        if a is None:
            missing.append(e)
            continue
        d = {"excel": e["name"], "app": a["name"], "qty": (e["qty"], a["qty"]),
             "d_list": a["list"] - e["list"], "d_dsc": a["dsc"] - e["dsc"],
             "d_paid": a["paid"] - e["paid"]}
        if abs(d["d_list"]) > 0.005 or abs(d["d_dsc"]) > 0.005 or abs(d["d_paid"]) > 0.005:
            money_diff.append(d)
        # 房间归类（总数一致时的差异）
        ex_allocs = {ROOM_ALIAS.get(k, k): v for k, v in e["allocs"].items()}
        rd = []
        for r in sorted(set(ex_allocs) | set(a["allocs"])):
            eq = ex_allocs.get(r, (0, None))[0]
            aq = a["allocs"].get(r, (0, None))[0]
            if abs(eq - aq) > 1e-6:
                rd.append((r, eq, aq))
        if rd:
            room_diff.append({"excel": e["name"], "app": a["name"], "rooms": rd})
        if not e["allocs"] and a["allocs"]:
            pass  # Excel 无房间布点、应用补了——属正常细化
    extra = [a for a in app if not a["used"]]

    def sortkey(d):
        return -max(abs(d["d_list"]), abs(d["d_dsc"]), abs(d["d_paid"]))

    print("=" * 92)
    print("一、金额 / 数量差异（需核对）")
    print("=" * 92)
    if not money_diff:
        print("  无")
    for d in sorted(money_diff, key=sortkey):
        print(f"\n▸ {d['excel']} → {d['app']}")
        if abs(d["qty"][0] - d["qty"][1]) > 1e-6:
            print(f"    数量 {d['qty'][0]:g} → {d['qty'][1]:g}")
        print(f"    原价 {d['d_list']:+.2f}　日常价 {d['d_dsc']:+.2f}　已付 {d['d_paid']:+.2f}")

    print("\n" + "=" * 92)
    print("二、房间归类差异（总数一致，只是挂在哪个房间不同）")
    print("=" * 92)
    for d in room_diff:
        txt = "，".join(f"{r} {eq:g}→{aq:g}" for r, eq, aq in d["rooms"])
        print(f"  {d['excel']} → {d['app']}：{txt}")

    print("\n" + "=" * 92)
    print("三、表格里有、数据库里没有")
    print("=" * 92)
    print("  无" if not missing else "")
    for e in missing:
        print(f"  ❌ {e['name']}（数量 {e['qty']:g}，原价 {e['list']:.2f}）")

    print("\n" + "=" * 92)
    print("四、数据库里有、表格里没有")
    print("=" * 92)
    print("  无" if not extra else "")
    for a in extra:
        print(f"  ＋ {a['name']}（数量 {a['qty']:g}，原价 {a['list']:.2f}）")

    xt = sum(e["list"] for e in xl)
    xd = sum(e["dsc"] for e in xl)
    xp = sum(e["paid"] for e in xl)
    at = sum((a["list"] if a else e["list"]) for e, a in pairs)
    ad = sum((a["dsc"] if a else e["dsc"]) for e, a in pairs)
    ap = sum((a["paid"] if a else e["paid"]) for e, a in pairs)
    print("\n" + "=" * 92)
    print("五、汇总额")
    print("=" * 92)
    print(f"{'':16s}{'原价':>14s}{'日常价':>14s}{'已付':>14s}")
    print(f"{'表格文件':16s}{xt:>14.2f}{xd:>14.2f}{xp:>14.2f}")
    print(f"{'当前数据库':16s}{at:>14.2f}{ad:>14.2f}{ap:>14.2f}")
    print(f"{'差额':16s}{at - xt:>+14.2f}{ad - xd:>+14.2f}{ap - xp:>+14.2f}")


if __name__ == "__main__":
    main()

"""xlsx 导入 / 导出 / 模板。

- 运行期导入：只认系统模板格式（"物料汇总" + "布点明细" 两个平表 sheet），
  即本系统导出的格式，可完整回灌。
- 三分区格式的历史表格（产品×房间矩阵 + 物料汇总 + 类目汇总）
  仅由 backend/scripts/seed_from_excel.py 预置数据时解析一次，不作为运行期功能。

模板金额口径：
- 物料汇总的"数量"为总量；若"布点明细"里也填了该物料，则总量以布点合计为准
- 优惠价 = 数量 × 优惠单价（日常单价留空则按原价计）
"""

import io
import re
from collections import Counter

from openpyxl import Workbook, load_workbook

from . import compute

PRODUCT_HEADER = {"灯具", "面板", "物料", "产品"}


def _v(ws, row, col):
    return ws.cell(row=row, column=col).value


def _vo(ws, row, col):
    """列号可能为 None（旧文件缺少可选列）时安全读取。"""
    if not col:
        return None
    return ws.cell(row=row, column=col).value


def _num(x):
    if x is None or x == "":
        return None
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def _txt(x):
    return str(x).strip() if x is not None else ""


# ---------------------------------------------------------------- 解析格式 A

def _find_subtables(ws):
    """找到所有 产品×房间 子表（以"房间"表头定位）。"""
    subs = []
    for row in ws.iter_rows():
        for cell in row:
            if _txt(cell.value) == "房间":
                r, c = cell.row, cell.column
                kind = "7col" if _txt(_v(ws, r, c + 2)) == "单价" else "3col"
                subs.append({
                    "header_row": r, "room_col": c, "kind": kind,
                    "product_col": c - 1, "qty_col": c + 1,
                    "price_col": c + 2 if kind == "7col" else None,
                    "total_col": c + 3 if kind == "7col" else None,
                    "discount_col": c + 4 if kind == "7col" else None,
                    "paid_col": c + 5 if kind == "7col" else None,
                    "note_col": (c + 6 if kind == "7col" else c + 2),
                })
    subs.sort(key=lambda s: (s["header_row"], s["product_col"]))
    return subs


def _read_subtable(ws, sub):
    """读取单个子表：产品名 + 各房间(数量/单价/备注) + 优惠总价 + 实付。"""
    rows = []
    r = sub["header_row"] + 1
    product_name = None
    total_row = None
    while r <= ws.max_row:
        p = _txt(_v(ws, r, sub["product_col"]))
        if "合计" in p:
            total_row = r
            break
        room = _txt(_v(ws, r, sub["room_col"]))
        qty = _num(_v(ws, r, sub["qty_col"]))
        price = _num(_v(ws, r, sub["price_col"])) if sub["kind"] == "7col" else None
        note = _txt(_v(ws, r, sub["note_col"]))
        if product_name is None and p:
            product_name = p
        if room:
            rows.append({"row": r, "room": room, "qty": qty or 0,
                         "price": price, "note": note})
        elif product_name and not room and qty is None and not note:
            break  # 空行且已读到产品 → 提前结束（防止滑入下一个分区）
        r += 1
    discount_total = None
    paid_total = None
    if sub["kind"] == "7col" and rows:
        discount_total = _num(_v(ws, sub["header_row"] + 1, sub["discount_col"]))
        paid_total = _num(_v(ws, sub["header_row"] + 1, sub["paid_col"]))
    return {"product": product_name, "rows": rows,
            "total_row": total_row, "discount_total": discount_total,
            "paid_total": paid_total, "kind": sub["kind"]}


def _find_summary_header(ws):
    for row in ws.iter_rows():
        for cell in row:
            if _txt(cell.value) == "物料" and _txt(_v(ws, cell.row, cell.column + 1)) == "数量":
                return cell.row, cell.column
    return None, None


def _parse_category_map(ws, ws_f, summary_row, subtables):
    """从"类目汇总"的合计公式反推：物料汇总行 → 类目；矩阵子表 → 类目。"""
    cat_of_summary_row = {}
    cat_of_subtable = {}
    warnings = []
    head = None
    for row in ws.iter_rows():
        for cell in row:
            if _txt(cell.value) == "类目":
                head = (cell.row, cell.column)
                break
        if head:
            break
    if not head:
        return cat_of_summary_row, cat_of_subtable, warnings
    r0, c0 = head
    total_cols = {(s["total_col"], s["total_row"]): s for s in subtables
                  if s["kind"] == "7col" and s["total_row"]}
    r = r0 + 1
    while r <= ws.max_row:
        name = _txt(_v(ws, r, c0))
        if not name or name in ("总计", "合计"):
            if not name:
                break
            r += 1
            continue
        formula = _v(ws_f, r, c0 + 1)
        refs = re.findall(r"(\$?[A-Z]{1,2})\$?(\d+)", str(formula or ""))
        for col_letter, ref_row in refs:
            col_letter = col_letter.replace("$", "")
            ref_row = int(ref_row)
            # 矩阵子表合计引用（如 E14/M14/U14）
            sub = total_cols.get(
                (_col_index(col_letter), ref_row))
            if sub:
                cat_of_subtable[(sub["header_row"], sub["product_col"])] = name
            # 物料汇总行引用（D32 等）
            elif col_letter == "D" and summary_row and ref_row > summary_row:
                cat_of_summary_row[ref_row] = name
        r += 1
    return cat_of_summary_row, cat_of_subtable, warnings


def _col_index(letters):
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - 64)
    return n


def _parse_original(path_or_bytes):
    if isinstance(path_or_bytes, (bytes, bytearray)):
        wb = load_workbook(io.BytesIO(path_or_bytes), data_only=True)
        wb_f = load_workbook(io.BytesIO(path_or_bytes), data_only=False)
    else:
        wb = load_workbook(path_or_bytes, data_only=True)
        wb_f = load_workbook(path_or_bytes, data_only=False)
    ws, ws_f = wb.worksheets[0], wb_f.worksheets[0]

    warnings = []
    subtables = _find_subtables(ws)
    parsed_subs = [_read_subtable(ws, s) for s in subtables]
    for s, p in zip(subtables, parsed_subs):
        s["parsed"] = p
        s["total_row"] = p["total_row"]

    sum_row, sum_col = _find_summary_header(ws)
    cat_of_summary_row, cat_of_subtable, _w = _parse_category_map(
        ws, ws_f, sum_row, subtables)

    # 房间（按首次出现顺序）；不同子表对同一行房间写法不一致时（如
    # "玄关"+"阳台/过道" 与 "玄关/阳台"+"过道"），按行位置对齐归并为一种写法
    seq_counter = Counter(tuple(r["room"] for r in p["rows"])
                          for p in parsed_subs if p["rows"])
    canonical = max(seq_counter.items(),
                    key=lambda kv: (kv[1], "/" in kv[0][0]))[0]
    aliases = {}
    for seq in seq_counter:
        if seq == canonical:
            continue
        if len(seq) == len(canonical):
            for a, b in zip(seq, canonical):
                if a != b:
                    aliases[a] = b
    if aliases:
        warnings.append(
            "原表各子表房间名写法不一致，已按行归并：" +
            "、".join(f"「{a}」→「{b}」" for a, b in aliases.items()))
        for p in parsed_subs:
            for row in p["rows"]:
                row["room"] = aliases.get(row["room"], row["room"])

    rooms = []
    for p in parsed_subs:
        for row in p["rows"]:
            if row["room"] not in rooms:
                rooms.append(row["room"])

    # 矩阵产品
    matrix_products = []
    for s, p in zip(subtables, parsed_subs):
        if not p["product"]:
            continue
        if p["kind"] == "7col":
            prices = [row["price"] for row in p["rows"]
                      if row["qty"] and row["price"]]
            base = Counter(prices).most_common(1)[0][0] if prices else None
            total_qty = sum(row["qty"] for row in p["rows"])
            unit_disc = None
            if p["discount_total"] is not None and total_qty:
                unit_disc = p["discount_total"] / total_qty  # 保留全精度
            allocs = []
            for row in p["rows"]:
                if not row["qty"]:
                    continue
                override = row["price"]
                if override is not None and base is not None and abs(override - base) < 1e-9:
                    override = None
                allocs.append({"room": row["room"], "qty": row["qty"],
                               "price_override": override,
                               "note": row["note"]})
            matrix_products.append({
                "name": p["product"], "kind": "7col", "price": base,
                "discount_price": unit_disc,
                "paid_amount": p["paid_total"],
                "allocations": allocs,
                "category": cat_of_subtable.get((s["header_row"], s["product_col"])),
            })
        else:  # 3col：价格等物料汇总行补充
            allocs = [{"room": row["room"], "qty": row["qty"],
                       "price_override": None, "note": row["note"]}
                      for row in p["rows"] if row["qty"]]
            matrix_products.append({
                "name": p["product"], "kind": "3col", "price": None,
                "discount_price": None, "paid_amount": None,
                "allocations": allocs, "category": None,
            })

    # 物料汇总行
    summary_rows = []
    if sum_row:
        r = sum_row + 1
        while r <= ws.max_row:
            name = _txt(_v(ws, r, sum_col))
            if name == "合计":
                break
            qty = _num(_v(ws, r, sum_col + 1))
            if name and qty is not None:
                summary_rows.append({
                    "row": r, "name": name, "qty": qty,
                    "price": _num(_v(ws, r, sum_col + 2)),
                    "discount_unit": _num(_v(ws, r, sum_col + 4)),
                    "paid": _num(_v(ws, r, sum_col + 6)),
                    "category": cat_of_summary_row.get(r),
                })
            r += 1

    return {"rooms": rooms, "matrix_products": matrix_products,
            "summary_rows": summary_rows, "warnings": warnings}


# ---------------------------------------------------------------- 解析格式 B

def _parse_flat(path_or_bytes):
    if isinstance(path_or_bytes, (bytes, bytearray)):
        wb = load_workbook(io.BytesIO(path_or_bytes), data_only=True)
    else:
        wb = load_workbook(path_or_bytes, data_only=True)
    warnings = []
    ws_items = None
    ws_alloc = None
    for sheet in wb.worksheets:
        headers = {_txt(c.value) for row in sheet.iter_rows(max_row=1) for c in row}
        # 物料汇总页有"类目/优惠单价"列，布点明细页没有，以此区分
        if "物料名称" in headers and ("类目" in headers or "日常单价" in headers or "优惠单价" in headers):
            ws_items = sheet
        if sheet.title == "布点明细":
            ws_alloc = sheet
    if ws_items is None:
        raise ValueError("无法识别的文件格式：既不是原清单格式，也不是本系统导出格式")

    def col_of(sheet, title):
        for cell in sheet[1]:
            if _txt(cell.value) == title:
                return cell.column
        return None

    c_cat = col_of(ws_items, "类目")
    c_name = col_of(ws_items, "物料名称")
    c_brand = col_of(ws_items, "品牌")
    c_model = col_of(ws_items, "型号")
    c_unit = col_of(ws_items, "单位")
    c_qty = col_of(ws_items, "数量")
    c_price = col_of(ws_items, "单价")
    c_disc = col_of(ws_items, "日常单价") or col_of(ws_items, "优惠单价")
    c_paid_qty = col_of(ws_items, "实付数量")
    c_paid_amount = col_of(ws_items, "实付金额")
    c_bought = col_of(ws_items, "已购")
    c_note = col_of(ws_items, "备注")

    items = []
    for r in range(2, ws_items.max_row + 1):
        name = _txt(_v(ws_items, r, c_name))
        if not name:
            continue
        bought_cell = _txt(_v(ws_items, r, c_bought)) if c_bought else ""
        items.append({
            "name": name,
            "brand": _txt(_vo(ws_items, r, c_brand)),
            "model": _txt(_vo(ws_items, r, c_model)),
            "category": _txt(_v(ws_items, r, c_cat)) or None,
            "unit": _txt(_v(ws_items, r, c_unit)) or "个",
            "qty_total": _num(_v(ws_items, r, c_qty)) or 0,
            "price": _num(_v(ws_items, r, c_price)) or 0,
            "discount_price": _num(_v(ws_items, r, c_disc)),
            "paid_qty": _num(_vo(ws_items, r, c_paid_qty)) or 0,
            "paid_amount": _num(_vo(ws_items, r, c_paid_amount)),
            "bought": bought_cell in ("是", "TRUE", "True", "1"),
            "note": _txt(_v(ws_items, r, c_note)),
        })

    allocs = []  # {item_name, room, qty, price_override, paid_qty, note}
    if ws_alloc is not None:
        cols = {t: col_of(ws_alloc, t) for t in
                ("物料名称", "房间", "数量", "单价", "实付数量", "备注")}
        for r in range(2, ws_alloc.max_row + 1):
            name = _txt(_v(ws_alloc, r, cols["物料名称"]))
            room = _txt(_v(ws_alloc, r, cols["房间"]))
            qty = _num(_v(ws_alloc, r, cols["数量"])) or 0
            if not name or not room or not qty:
                continue
            allocs.append({
                "item_name": name, "room": room, "qty": qty,
                "price_override": _num(_v(ws_alloc, r, cols["单价"])),
                "paid_qty": min(_num(_vo(ws_alloc, r, cols.get("实付数量"))) or 0, qty),
                "note": _txt(_v(ws_alloc, r, cols["备注"])),
            })
    records = []
    ws_rec = None
    for sheet in wb.worksheets:
        if sheet.title == "采购记录":
            ws_rec = sheet
    if ws_rec is not None:
        rcols = {t: col_of(ws_rec, t) for t in
                 ("物料名称", "实付数量", "实付金额", "付款日期", "备注")}
        for r in range(2, ws_rec.max_row + 1):
            name = _txt(_v(ws_rec, r, rcols["物料名称"]))
            if not name:
                continue
            qty = _num(_vo(ws_rec, r, rcols.get("实付数量"))) or 0
            amount = _num(_vo(ws_rec, r, rcols.get("实付金额"))) or 0
            if not qty and not amount:
                continue  # 空记录忽略
            records.append({
                "item_name": name,
                "qty": qty,
                "amount": amount,
                "date": _txt(_vo(ws_rec, r, rcols.get("付款日期"))),
                "note": _txt(_vo(ws_rec, r, rcols.get("备注"))),
            })
    return {"items": items, "allocs": allocs, "records": records, "warnings": warnings}


# ---------------------------------------------------------------- 入库

def _norm(name):
    return re.sub(r"\s+", "", name or "")


def _match_item(name, pool):
    """精确 → 包含（长度差最小者优先），只匹配未消耗的物料。"""
    n = _norm(name)
    for it in pool:
        if it["_norm"] == n:
            return it
    cands = [it for it in pool if not it["_consumed"]
             and (it["_norm"] in n or n in it["_norm"])]
    if cands:
        return min(cands, key=lambda it: abs(len(it["_norm"]) - len(n)))
    return None


def _get_or_create_room(db, name, rooms_cache, report):
    if name in rooms_cache:
        return rooms_cache[name]
    from ..models import Room
    room = db.query(Room).filter(Room.name == name).first()
    if not room:
        room = Room(name=name, sort=len(rooms_cache))
        db.add(room)
        db.flush()
        report["rooms_created"] += 1
    rooms_cache[name] = room
    return room


def _get_or_create_category(db, name, cats_cache, report):
    if not name:
        return None
    if name in cats_cache:
        return cats_cache[name]
    from ..models import Category
    cat = db.query(Category).filter(Category.name == name).first()
    if not cat:
        cat = Category(name=name, sort=len(cats_cache))
        db.add(cat)
        db.flush()
        report["categories_created"] += 1
    cats_cache[name] = cat
    return cat


KEYWORD_CATS = [("照明", ["灯"]), ("开关插座", ["开关", "插座"]),
                ("网络", ["网口", "网线", "网路", "路由", "交换机", "模块", "hdmi"])]


def _guess_unit(name):
    """原表无单位列：按名称给出合理默认（网线/灯带按米，其余按个）。"""
    n = name or ""
    if "网线" in n:
        return "米"
    if "灯带" in n and "控制器" not in n and "配件" not in n:
        return "米"
    return "个"


def _fallback_category(name):
    low = (name or "").lower()
    for cat_name, keys in KEYWORD_CATS:
        if any(k in low for k in keys):
            return cat_name
    return None


def _apply_original(db, parsed, mode, report):
    from ..models import Allocation, Category, Item, PurchaseRecord, Room

    db.expire_all()  # 同 session 二次导入时避免关系集合缓存过期不失效

    if mode == "replace":
        db.query(PurchaseRecord).delete()
        db.query(Allocation).delete()
        db.query(Item).delete()
        db.commit()

    rooms_cache, cats_cache = {}, {}
    for room in db.query(Room).order_by(Room.sort).all():
        rooms_cache[room.name] = room
    for cat in db.query(Category).order_by(Category.sort).all():
        cats_cache[cat.name] = cat

    # 全量创建房间（没有布点的房间也是真实房间，矩阵页需要完整列）
    for name in parsed["rooms"]:
        _get_or_create_room(db, name, rooms_cache, report)
    db.commit()

    # 1) 矩阵产品
    existing = {} if mode == "replace" else {
        _norm(i.name): {"_obj": i, "_consumed": False, "_norm": _norm(i.name)}
        for i in db.query(Item).all()}
    for p in parsed["matrix_products"]:
        report.setdefault("allocations", 0)
        record_amount = None
        if mode == "replace":
            item = Item(name=p["name"])
        else:
            m = _match_item(p["name"], list(existing.values()))
            if m:
                m["_consumed"] = True
                item = m["_obj"]
                report["items_matched"] += 1
            else:
                item = Item(name=p["name"])
                report["items_created"] += 1
        if p["price"] is not None:
            item.price = p["price"]
        if p["discount_price"] is not None:
            item.discount_price = p["discount_price"]
        record_qty = None
        if p["paid_amount"] is not None:
            record_amount = p["paid_amount"]
        if p["category"]:
            item.category_id = _get_or_create_category(
                db, p["category"], cats_cache, report).id
        db.add(item)
        db.flush()
        if p["allocations"]:
            item.qty_total = 0
            for a in p["allocations"]:
                room = _get_or_create_room(db, a["room"], rooms_cache, report)
                # 通过关系集合添加：autoflush=False 下 pending 对象才能被后续计算看到
                item.allocations.append(Allocation(
                    room_id=room.id, qty=a["qty"], price_override=a["price_override"],
                    paid_qty=0, note=a["note"] or ""))
                report["allocations"] += 1
        if record_amount is not None:
            # 原表"实付"为总金额：转成一笔采购记录（数量=总量）
            item.records.append(PurchaseRecord(
                qty=compute.item_total_qty(item), amount=record_amount))
            item.bought = True
    db.commit()

    # 2) 物料汇总行
    pool = [{"_obj": i, "_consumed": False, "_norm": _norm(i.name)}
            for i in db.query(Item).all()]
    for row in parsed["summary_rows"]:
        m = _match_item(row["name"], pool)
        if m:
            item = m["_obj"]
            m["_consumed"] = True
            report["items_matched"] += 1
            if not item.price:
                item.price = row["price"] or 0
            if item.discount_price is None and row["discount_unit"] is not None:
                item.discount_price = row["discount_unit"]
            if item.paid_amount is None and row["paid"] is not None:
                item.records.append(PurchaseRecord(
                    qty=compute.item_total_qty(item), amount=row["paid"]))
                item.paid_amount = row["paid"]  # 旧字段留档
                item.bought = True
            if item.category_id is None:
                cat = _get_or_create_category(
                    db, row["category"], cats_cache, report) if row["category"] else None
                if cat:
                    item.category_id = cat.id
        else:
            cat_name = row["category"] or _fallback_category(row["name"])
            item = Item(name=row["name"], qty_total=row["qty"],
                        price=row["price"] or 0,
                        discount_price=row["discount_unit"],
                        unit=_guess_unit(row["name"]))
            if row["paid"] is not None:
                item.records.append(PurchaseRecord(
                    qty=row["qty"] or 0, amount=row["paid"]))
                item.paid_amount = row["paid"]  # 旧字段留档
                item.bought = True
            if cat_name:
                item.category_id = _get_or_create_category(
                    db, cat_name, cats_cache, report).id
            else:
                report["warnings"].append(f"「{row['name']}」未能归类，请手动选择类目")
            db.add(item)
            report["items_created"] += 1
    db.commit()


def item_bought_sync(db):
    from ..models import Item
    from . import compute
    for item in db.query(Item).all():
        item.bought = compute.item_status(item) == "done"


def _apply_flat(db, parsed, mode, report):
    from ..models import Allocation, Category, Item, PurchaseRecord, Room

    db.expire_all()  # 同 session 二次导入时避免关系集合缓存过期不失效
    if mode == "replace":
        db.query(PurchaseRecord).delete()
        db.query(Allocation).delete()
        db.query(Item).delete()
        db.commit()
    rooms_cache, cats_cache = {}, {}
    for room in db.query(Room).order_by(Room.sort).all():
        rooms_cache[room.name] = room
    for cat in db.query(Category).order_by(Category.sort).all():
        cats_cache[cat.name] = cat

    rec_names = {_norm(r["item_name"]) for r in parsed.get("records", [])}
    items_by_name = {}
    for data in parsed["items"]:
        if mode == "merge":
            item = db.query(Item).filter(Item.name == data["name"]).first()
        else:
            item = None
        if not item:
            item = Item(name=data["name"])
            report["items_created"] += 1
        else:
            report["items_matched"] += 1
        item.category_id = (_get_or_create_category(
            db, data["category"], cats_cache, report).id
            if data["category"] else item.category_id)
        item.unit = data["unit"]
        item.qty_total = data["qty_total"]
        item.price = data["price"]
        item.discount_price = data["discount_price"]
        item.brand = data["brand"] or ""
        item.model = data["model"] or ""
        item.note = data["note"]
        if (data["paid_qty"] or data["paid_amount"]) and _norm(data["name"]) not in rec_names:
            item.records.append(PurchaseRecord(
                qty=data["paid_qty"] or 0, amount=data["paid_amount"] or 0))
        db.add(item)
        db.flush()
        items_by_name[_norm(data["name"])] = item
    # 本次导入的布点明细涉及的物料：先清掉旧布点再写入，避免 merge 时重复叠加
    alloc_names = {_norm(a["item_name"]) for a in parsed["allocs"]}
    for item in items_by_name.values():
        if _norm(item.name) in alloc_names:
            db.expire(item, ["allocations"])  # 确保拿到库里最新布点
            item.allocations.clear()
            db.flush()  # 立即删除孤儿行
    db.flush()
    for a in parsed["allocs"]:
        item = items_by_name.get(_norm(a["item_name"]))
        if not item:
            report["warnings"].append(f"布点明细中的物料「{a['item_name']}」不存在，已跳过")
            continue
        room = _get_or_create_room(db, a["room"], rooms_cache, report)
        db.add(Allocation(item_id=item.id, room_id=room.id, qty=a["qty"],
                          price_override=a["price_override"],
                          note=a["note"] or ""))
        report["allocations"] += 1
    # 采购记录 sheet 涉及的物料：先清旧记录再写入（sheet 是该物料的完整付款历史）
    for item in items_by_name.values():
        if _norm(item.name) in rec_names:
            item.records.clear()
    for r in parsed.get("records", []):
        item = items_by_name.get(_norm(r["item_name"]))
        if not item:
            report["warnings"].append(f"采购记录中的物料「{r['item_name']}」不存在，已跳过")
            continue
        item.records.append(PurchaseRecord(
            qty=r["qty"] or 0, amount=r["amount"] or 0,
            date=r["date"] or "", note=r["note"] or ""))
        report["records"] = report.get("records", 0) + 1
    item_bought_sync(db)
    db.commit()


def import_original(db, file_bytes: bytes, mode: str = "replace") -> dict:
    """解析三分区格式的历史表格并入库（供 seed_from_excel.py 使用）。"""
    parsed = _parse_original(file_bytes)
    report = {"mode": mode, "items_created": 0, "items_matched": 0,
              "allocations": 0, "records": 0, "rooms_created": 0, "categories_created": 0,
              "warnings": list(parsed["warnings"]), "format": "original"}
    _apply_original(db, parsed, mode, report)
    return report


def import_template(db, file_bytes: bytes, mode: str = "replace") -> dict:
    """按系统模板（物料汇总+布点明细平表）导入；格式不符时抛 ValueError。"""
    report = {"mode": mode, "items_created": 0, "items_matched": 0,
              "allocations": 0, "records": 0, "rooms_created": 0, "categories_created": 0,
              "warnings": [], "format": "flat"}
    parsed = _parse_flat(file_bytes)
    _apply_flat(db, parsed, mode, report)
    return report


# ---------------------------------------------------------------- 导出

def export_xlsx(db) -> bytes:
    from ..models import Category, Item, Room
    from . import compute

    db.expire_all()  # 同一 session 先导入后导出时，避免读到过期关系缓存
    wb = Workbook()
    ws = wb.active
    ws.title = "物料汇总"
    ws.append(["类目", "物料名称", "品牌", "型号", "单位", "数量", "单价", "优惠单价",
               "日常价", "实付数量", "实付金额", "未付数量", "未付金额",
               "已购", "备注"])
    rooms = {r.id: r.name for r in db.query(Room).all()}
    cats = {c.id: c.name for c in db.query(Category).all()}
    for i in db.query(Item).order_by(Item.sort, Item.id).all():
        d = compute.item_dict(i)
        ws.append([
            cats.get(i.category_id, ""), i.name, i.brand or "", i.model or "",
            i.unit, d["total_qty"],
            i.price, i.discount_price, d["discount_total"],
            d["paid_qty"], d["paid"], d["unpaid_qty"], d["unpaid"],
            "是" if d["bought"] else "否", i.note,
        ])

    ws2 = wb.create_sheet("布点明细")
    ws2.append(["物料名称", "房间", "数量", "单价", "备注"])
    for i in db.query(Item).order_by(Item.sort, Item.id).all():
        for a in i.allocations:
            unit = a.price_override if a.price_override is not None else (i.price or 0)
            ws2.append([i.name, rooms.get(a.room_id, ""), a.qty, unit, a.note])

    ws3 = wb.create_sheet("采购记录")
    ws3.append(["物料名称", "实付数量", "实付金额", "付款日期", "备注"])
    for i in db.query(Item).order_by(Item.sort, Item.id).all():
        for r in i.records:
            ws3.append([i.name, r.qty or 0, r.amount or 0, r.date or "", r.note or ""])

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def build_template() -> bytes:
    """生成导入模板：与导出格式一致，另加一页填写说明。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "物料汇总"
    ws.append(["类目", "物料名称", "品牌", "型号", "单位", "数量", "单价", "优惠单价",
               "日常价", "实付数量", "实付金额", "未付数量", "未付金额",
               "已购", "备注"])
    ws.append(["照明", "示例筒灯（导入前请删除本行）", "个", 4, 99, 79.4,
               None, 0, None, None, None, None, "否", "日常单价留空则按原价计"])
    ws.append(["网络", "示例网线（导入前请删除本行）", "米", 150, 4, None,
               None, 150, 4.41, None, None, None, "是", "实付单价自动=实付金额÷实付数量"])

    ws2 = wb.create_sheet("布点明细")
    ws2.append(["物料名称", "房间", "数量", "单价", "实付数量", "备注"])
    ws2.append(["示例筒灯（导入前请删除本行）", "客厅", 2, 1199, 0,
                "单价留空=用物料单价；填了布点的物料，总量以布点合计为准"])

    ws3 = wb.create_sheet("采购记录")
    ws3.append(["物料名称", "实付数量", "实付金额", "付款日期", "备注"])
    ws3.append(["示例网线（导入前请删除本行）", 150, 661.2, "2026-09-14", "一笔可覆盖多个物料，分批买就分多行"])

    ws4 = wb.create_sheet("填写说明")
    for line in [
        "1. 「物料汇总」每行一种物料：类目/名称必填，数量、单价必填；优惠单价留空按原价计。",
        "2. 「布点明细」可选：物料按房间拆分数量时填写；填了布点的物料，总量以布点合计为准。",
        "3. 布点明细的单价留空 = 使用物料单价；单独填价可覆盖（如某房间装更贵的型号）。",
        "4. 「采购记录」推荐：每笔付款一行（数量+金额+日期），同一物料可多笔；实付单价自动=金额÷数量。",
        "5. 也可以不填采购记录，直接在物料汇总里填实付数量/实付金额，会生成一笔记录。",
        "6. 未付自动=剩余数量×单价；「已购」列由实付数量决定，可留空。",
        "7. 导入前请删除示例行。导入方式支持两种：覆盖现有数据 / 与现有数据按名称合并。",
    ]:
        ws4.append([line])

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()

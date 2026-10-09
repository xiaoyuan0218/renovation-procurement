"""三方合并：拿「上次同步时的基线」「本地现在的样子」「服务器现在的样子」算出该变成什么。

这是 `mobile/` 单机版那套合并逻辑的 Python 版（原版是 Kotlin 的
`data/sync/Merger.kt`）。两端行为必须一致 —— 同一份清单在手机上和电脑上同步，
合并结果得一样，否则会来回打架。

做得到的先说清楚：
  - 只有一边改过的行 → 直接用改过的那边（绝大多数情况：电脑上补一笔账、
    手机上改了另一条物料的单价，两条改动都保住）
  - 两边改成一样的 → 视为没冲突
  - 一边删、另一边没动 → 删
  - 同一行两边都改得不一样 → 报冲突，交给用户在界面上裁决

两边都翻译到**本地 id 空间**再比对：本地主键是自增的，跟服务器的 id 没有
对应关系，翻译靠上次同步存下来的映射（`Baseline.local_map`）。服务器上新增的
行在本地还没有 id，用负数占位 —— 反正推送后服务器会重新分配。
"""

from dataclasses import dataclass, field

# 行级比较用的中间结构。Kotlin 那边是 data class，靠值相等判"两边改成一样"；
# 这里用 tuple（不可变、可哈希、按元素比较），嵌套的分配/记录列表先排序再入
# tuple，让"顺序不同但内容相同"不会被误判成改动过。
# 排序键用 repr：元素里混着 None 和数字，直接排序会 TypeError。


@dataclass
class Baseline:
    """上次同步时的服务器状态，外加「服务器 id → 本地 id」的映射。"""

    payload: dict = field(default_factory=dict)
    local_map: dict = field(default_factory=dict)


@dataclass
class MergeResult:
    payload: dict
    conflicts: list


def _to_second(value) -> str:
    """把时间戳归一到「秒」再比。

    手机写的是 `yyyy-MM-dd HH:mm:ss.SSSSSS`（带微秒），服务器存的是
    `YYYY-MM-DD HH:MM:SS`。直接比字符串的话，同一秒内手机的值因为多一截小数
    而「更大」，判谁更新就偏向本地；而服务器把手机的值截断存下来后又反过来
    偏向服务器。先截到秒，两端才是同一把尺子。
    """
    text = (value or "").strip()
    if not text:
        return ""
    dot = text.find(".")
    base = text[:dot] if dot >= 0 else text
    return base.rstrip("Z")[:19]


def _newer_side(local, remote) -> int:
    """比两个时间戳谁新。等宽 `YYYY-MM-DD HH:MM:SS` 的字典序就是时间序。

    返回 1 本地新、-1 服务器新、0 判不出来（任一方没有时间戳、或两者相同）。
    """
    left = _to_second(local)
    right = _to_second(remote)
    if not left or not right:
        return 0
    if left > right:
        return 1
    if left < right:
        return -1
    return 0


def _pick_created(*values) -> str:
    """创建时间取较早的：两边都可能动过这行。"""
    filled = [v for v in values if v]
    return min(filled) if filled else ""


def _pick_updated(*values) -> str:
    """修改时间取较新的。结果 payload 要带上它推给服务器，缺了的话服务端只能
    用「当下」兜底，于是没改过的行也显得刚改过（判冲突就成了本地永远赢）。"""
    filled = [v for v in values if v]
    return max(filled) if filled else ""


def _local_of(local_map: dict, kind: str, remote_id) -> int:
    """服务器 id → 本地 id；没有映射说明是服务器新增的，用负数占位。"""
    key = f"{kind}:{remote_id}"
    if key in local_map:
        return local_map[key]
    return -max(remote_id or 0, 1)


def _align_theirs(rows, name_of, mine_id_by_name, mapped_id):
    """把服务器的一批行对齐到本地 id 空间。

    返回 `(本地 id → 服务器行, 服务器原 id → 本地 id)`。

    local_map 命中的直接用；**没命中的按名字回退配对** —— 服务器发生过覆盖
    重插时行 id 会重排（删过东西留下的空洞被填掉），另一台设备手里的旧映射就
    对不上了，这些行会被当成「服务器新增」跟本地同名行合并出重复（同一份清单里
    冒出两条「示例分组」，推上去还撞服务端唯一约束）。名字是这种清单里最自然的
    身份，按它配对最贴近事实。
    """
    used = set()
    by_key = {}
    remote_to_local = {}
    for row in rows:
        remote_id = row.get("id") or 0
        key = mapped_id(row)
        if key is None:
            candidate = mine_id_by_name.get(name_of(row))
            if candidate is not None and candidate not in used:
                key = candidate
        if key is not None:
            used.add(key)
            remote_to_local[remote_id] = key
            by_key[key] = row
        else:
            placeholder = -max(remote_id, 1)
            remote_to_local[remote_id] = placeholder
            by_key[placeholder] = row
    return by_key, remote_to_local


def _merge_simple(base, mine, theirs, label, name_of, prefer_local, conflicts,
                  local_ts=None, remote_ts=None):
    """按行合并（分组/分类/物料/费用走同一套判定）。"""
    local_ts = local_ts or {}
    remote_ts = remote_ts or {}
    result = {}
    for key in set(base) | set(mine) | set(theirs):
        b = base.get(key)
        m = mine.get(key)
        t = theirs.get(key)

        if m is not None and t is not None:
            if m == b:
                result[key] = t                       # 我没动
            elif t == b:
                result[key] = m                       # 他没动
            elif m == t:
                result[key] = m                       # 都改成一样
            else:
                # 两边都改过：谁的时间新听谁的。时间戳是写入方自己盖的，比
                # 「谁点了保存」更接近事实；判不出来（老数据没时间戳、或两端
                # 同秒）才记冲突，由界面让用户拍板
                side = _newer_side(local_ts.get(key), remote_ts.get(key))
                if side == 1:
                    result[key] = m
                elif side == -1:
                    result[key] = t
                else:
                    conflicts.append(_conflict(label, name_of(m)))
                    result[key] = m if prefer_local else t

        elif m is not None:                           # 服务器那边没有
            if b is None:
                result[key] = m                       # 我新增的
            elif m == b:
                pass                                  # 我没动、那边删了 → 跟着删
            else:
                conflicts.append(_conflict(label, name_of(m)))
                if prefer_local:
                    result[key] = m

        elif t is not None:                           # 本地这边没有
            if b is None:
                result[key] = t                       # 服务器新增的
            elif t == b:
                pass                                  # 他没动、我删了 → 保持删除
            else:
                conflicts.append(_conflict(label, name_of(t)))
                if not prefer_local:
                    result[key] = t
    return result


def _conflict(label: str, name: str) -> dict:
    return {"label": label, "name": name,
            "description": f"{label}「{name}」两边都改过，改得还不一样"}


# ---------------------------------------------------------------- 行 → 可比较的中间结构

def _simple_row(row) -> tuple:
    return (row.get("name", ""), row.get("sort") or 0)


def _alloc_row(alloc, translate) -> tuple:
    room_id = alloc.get("room_id")
    return (translate("room", room_id) if room_id is not None else None,
            alloc.get("qty") or 0,
            alloc.get("price_override"),
            alloc.get("note") or "")


def _record_row(record, translate) -> tuple:
    room_ids = sorted(translate("room", rid) for rid in (record.get("room_ids") or []))
    # 定金字放在最后：前面几个位置手机端也按序号读，动前面的会整体错位。
    # 值保留原样（缺字段就是 None）—— 归一成 False 的话，"老服务器没带这个
    # 信息"就变成"它说不是定金"，落地前按业务键回填旧值那一步就认不出来了。
    return (record.get("qty") or 0, record.get("amount") or 0,
            record.get("date") or "", record.get("note") or "",
            record.get("vendor") or "", record.get("order_no") or "",
            tuple(room_ids), record.get("is_deposit"))


def _item_row(item, translate) -> tuple:
    category_id = item.get("category_id")
    allocations = [_alloc_row(a, translate) for a in item.get("allocations", [])]
    records = [_record_row(r, translate) for r in item.get("records", [])]
    return (
        item.get("name", ""),
        translate("category", category_id) if category_id is not None else None,
        item.get("unit") or "个",
        item.get("brand") or "",
        item.get("model") or "",
        item.get("qty_total") or 0,
        item.get("price") or 0,
        item.get("discount_price"),
        item.get("note") or "",
        item.get("sort") or 0,
        item.get("deleted_at"),
        tuple(sorted(allocations, key=repr)),
        tuple(sorted(records, key=repr)),
    )


def _expense_row(expense, translate=None) -> tuple:
    item_id = expense.get("item_id")
    if item_id is not None and translate is not None:
        item_id = translate(item_id)
    return (expense.get("kind") or "运费", expense.get("amount") or 0,
            expense.get("date") or "", expense.get("vendor") or "",
            expense.get("order_no") or "", expense.get("note") or "", item_id)


def _expense_name(expense) -> str:
    return f"{expense.get('kind') or '运费'} {expense.get('amount') or 0}"


def _same_record(original, row: tuple) -> bool:
    """是不是同一条采购记录（payload 里的原身 vs 合并后的一行）。

    记录跟着物料整条合并，合并后要按内容把原身找回来才拿得到时间戳 —— 按
    room_ids 比会失配：两端的组 id 空间不同。
    """
    return (original.get("qty") or 0) == row[0] and (original.get("amount") or 0) == row[1] \
        and (original.get("date") or "") == row[2] and (original.get("note") or "") == row[3] \
        and (original.get("vendor") or "") == row[4] and (original.get("order_no") or "") == row[5] \
        and bool(original.get("is_deposit")) == bool(row[7])


# ---------------------------------------------------------------- 三方合并

def merge(base: Baseline, mine: dict, theirs: dict, prefer_local: bool = False) -> MergeResult:
    conflicts: list = []
    base_map = base.local_map
    base_payload = base.payload

    # ---------- 分组 ----------
    aligned_rooms, room_translate = _align_theirs(
        theirs.get("rooms", []),
        name_of=lambda r: r.get("name", ""),
        mine_id_by_name={r["name"]: r["id"] for r in mine.get("rooms", [])
                         if r.get("id") is not None},
        mapped_id=lambda r: base_map.get(f"room:{r.get('id')}"),
    )
    merged_rooms = _merge_simple(
        base={_local_of(base_map, "room", r.get("id")): _simple_row(r)
              for r in base_payload.get("rooms", [])},
        mine={(r.get("id") or 0): _simple_row(r) for r in mine.get("rooms", [])},
        theirs={k: _simple_row(r) for k, r in aligned_rooms.items()},
        label="分组", name_of=lambda row: row[0], prefer_local=prefer_local,
        conflicts=conflicts,
        local_ts={(r.get("id") or 0): r.get("updated_at") for r in mine.get("rooms", [])},
        remote_ts={k: r.get("updated_at") for k, r in aligned_rooms.items()},
    )
    rooms = [_rebuild_room(local_id, row, mine.get("rooms", []), aligned_rooms)
             for local_id, row in merged_rooms.items()]

    # ---------- 分类 ----------
    aligned_categories, category_translate = _align_theirs(
        theirs.get("categories", []),
        name_of=lambda r: r.get("name", ""),
        mine_id_by_name={r["name"]: r["id"] for r in mine.get("categories", [])
                         if r.get("id") is not None},
        mapped_id=lambda r: base_map.get(f"category:{r.get('id')}"),
    )
    merged_categories = _merge_simple(
        base={_local_of(base_map, "category", r.get("id")): _simple_row(r)
              for r in base_payload.get("categories", [])},
        mine={(r.get("id") or 0): _simple_row(r) for r in mine.get("categories", [])},
        theirs={k: _simple_row(r) for k, r in aligned_categories.items()},
        label="分类", name_of=lambda row: row[0], prefer_local=prefer_local,
        conflicts=conflicts,
        local_ts={(r.get("id") or 0): r.get("updated_at") for r in mine.get("categories", [])},
        remote_ts={k: r.get("updated_at") for k, r in aligned_categories.items()},
    )
    categories = [_rebuild_category(local_id, row, mine.get("categories", []), aligned_categories)
                  for local_id, row in merged_categories.items()]

    # ---------- 物料（连同它的分配与采购记录一起比：它们是一体的）----------
    aligned_items, item_translate = _align_theirs(
        theirs.get("items", []),
        name_of=lambda r: r.get("name", ""),
        mine_id_by_name={r["name"]: r["id"] for r in mine.get("items", [])
                         if r.get("id") is not None},
        mapped_id=lambda r: base_map.get(f"item:{r.get('id')}"),
    )

    def base_translate(kind, ref_id):
        return _local_of(base_map, kind, ref_id)

    def theirs_translate(kind, ref_id):
        if kind == "room":
            return room_translate.get(ref_id) or _local_of(base_map, kind, ref_id)
        if kind == "category":
            return category_translate.get(ref_id) or _local_of(base_map, kind, ref_id)
        return _local_of(base_map, kind, ref_id)

    merged_items = _merge_simple(
        base={_local_of(base_map, "item", it.get("id")): _item_row(it, base_translate)
              for it in base_payload.get("items", [])},
        mine={(it.get("id") or 0): _item_row(it, lambda kind, rid: rid)
              for it in mine.get("items", [])},
        theirs={k: _item_row(it, theirs_translate) for k, it in aligned_items.items()},
        label="物料", name_of=lambda row: row[0], prefer_local=prefer_local,
        conflicts=conflicts,
        local_ts={(it.get("id") or 0): it.get("updated_at") for it in mine.get("items", [])},
        remote_ts={k: it.get("updated_at") for k, it in aligned_items.items()},
    )
    items = [_rebuild_item(local_id, row, mine.get("items", []), aligned_items)
             for local_id, row in merged_items.items()]

    # ---------- 额外费用 ----------
    aligned_expenses, _ = _align_theirs(
        theirs.get("expenses", []),
        name_of=_expense_name,
        mine_id_by_name={_expense_name(r): r["id"] for r in mine.get("expenses", [])
                         if r.get("id") is not None},
        mapped_id=lambda r: base_map.get(f"expense:{r.get('id')}"),
    )
    merged_expenses = _merge_simple(
        base={_local_of(base_map, "expense", e.get("id")): _expense_row(e)
              for e in base_payload.get("expenses", [])},
        mine={(e.get("id") or 0): _expense_row(e) for e in mine.get("expenses", [])},
        theirs={k: _expense_row(e, lambda rid: item_translate.get(rid)
                                or _local_of(base_map, "item", rid))
                for k, e in aligned_expenses.items()},
        label="费用", name_of=lambda row: f"{row[0]} {row[1]}", prefer_local=prefer_local,
        conflicts=conflicts,
        local_ts={(e.get("id") or 0): e.get("updated_at") for e in mine.get("expenses", [])},
        remote_ts={k: e.get("updated_at") for k, e in aligned_expenses.items()},
    )
    expenses = [_rebuild_expense(local_id, row, mine.get("expenses", []), aligned_expenses)
                for local_id, row in merged_expenses.items()]

    payload = {
        "version": mine.get("version", 1),
        "list": mine.get("list", {}),
        "rooms": sorted(rooms, key=lambda r: r.get("sort") or 0),
        "categories": sorted(categories, key=lambda c: c.get("sort") or 0),
        "items": sorted(items, key=lambda i: i.get("sort") or 0),
        "expenses": expenses,
    }
    return MergeResult(payload=payload, conflicts=conflicts)


def _rebuild_room(local_id, row, mine_rows, aligned):
    mine_row = next((r for r in mine_rows if (r.get("id") or 0) == local_id), None)
    remote_row = aligned.get(local_id)
    return {"id": local_id, "name": row[0], "sort": row[1],
            "created_at": _pick_created(mine_row.get("created_at") if mine_row else None,
                                        remote_row.get("created_at") if remote_row else None),
            "updated_at": _pick_updated(mine_row.get("updated_at") if mine_row else None,
                                        remote_row.get("updated_at") if remote_row else None)}


def _rebuild_category(local_id, row, mine_rows, aligned):
    return _rebuild_room(local_id, row, mine_rows, aligned)


def _rebuild_item(local_id, row, mine_rows, aligned):
    mine_item = next((r for r in mine_rows if (r.get("id") or 0) == local_id), None)
    remote_item = aligned.get(local_id)
    allocations = [{"room_id": a[0], "qty": a[1], "price_override": a[2], "note": a[3]}
                   for a in row[11]]
    records = []
    for r in row[12]:
        # 按内容把这条记录在两边的原身找回来，取它的时间戳
        mine_rec = next((x for x in (mine_item or {}).get("records", [])
                         if _same_record(x, r)), None)
        remote_rec = next((x for x in (remote_item or {}).get("records", [])
                           if _same_record(x, r)), None)
        records.append({
            "qty": r[0], "amount": r[1], "date": r[2], "note": r[3],
            "vendor": r[4], "order_no": r[5], "room_ids": list(r[6]),
            # 保留 None：那是"这一头没带这个信息"（老服务器），落地前会按业务键
            # 回填本地旧值；bool() 一把就变成"不是定金"，回填认不出来
            "is_deposit": r[7],
            "created_at": _pick_created(mine_rec.get("created_at") if mine_rec else None,
                                        remote_rec.get("created_at") if remote_rec else None),
            "updated_at": _pick_updated(mine_rec.get("updated_at") if mine_rec else None,
                                        remote_rec.get("updated_at") if remote_rec else None),
        })
    return {
        "id": local_id, "name": row[0], "category_id": row[1], "unit": row[2],
        "brand": row[3], "model": row[4], "qty_total": row[5], "price": row[6],
        "discount_price": row[7], "note": row[8], "sort": row[9],
        "deleted_at": row[10], "allocations": allocations, "records": records,
        "created_at": _pick_created(mine_item.get("created_at") if mine_item else None,
                                    remote_item.get("created_at") if remote_item else None),
        "updated_at": _pick_updated(mine_item.get("updated_at") if mine_item else None,
                                    remote_item.get("updated_at") if remote_item else None),
    }


def _rebuild_expense(local_id, row, mine_rows, aligned):
    mine_row = next((r for r in mine_rows if (r.get("id") or 0) == local_id), None)
    remote_row = aligned.get(local_id)
    return {"id": local_id, "kind": row[0], "amount": row[1], "date": row[2],
            "vendor": row[3], "order_no": row[4], "note": row[5], "item_id": row[6],
            "created_at": _pick_created(mine_row.get("created_at") if mine_row else None,
                                        remote_row.get("created_at") if remote_row else None),
            "updated_at": _pick_updated(mine_row.get("updated_at") if mine_row else None,
                                        remote_row.get("updated_at") if remote_row else None)}


# ---------------------------------------------------------------- 无基线合并

def _pick_by_name(mine, theirs, name_of, updated_of):
    """按名字把两边配对：同名的算同一条、取改动较新的那份，各自独有的都留着。

    同一名字在一侧出现多次时按出现顺序一一配，多出来的那些不会被丢掉。
    """
    pending: dict = {}
    for index, row in enumerate(theirs):
        pending.setdefault(name_of(row), []).append(index)
    used = [False] * len(theirs)
    out = []
    for m in mine:
        bucket = pending.get(name_of(m))
        index = bucket.pop(0) if bucket else None
        if index is None:
            out.append({"row": m, "from_mine": True})
            continue
        used[index] = True
        t = theirs[index]
        # 谁改得更近听谁的（时间戳先归一到秒再比）；判不出来（任一方没有时间戳）
        # 就用本地这边 —— 用户此刻正看着的是它
        m_ts = _to_second(updated_of(m))
        t_ts = _to_second(updated_of(t))
        remote_newer = bool(m_ts) and bool(t_ts) and t_ts > m_ts
        out.append({"row": t, "from_mine": False} if remote_newer
                   else {"row": m, "from_mine": True})
    for index, t in enumerate(theirs):
        if not used[index]:
            out.append({"row": t, "from_mine": False})
    return out


def merge_without_base(mine: dict, theirs: dict) -> dict:
    """两边从没同步过（没有共同基线）时的合并 —— 「两边的都留着」。

    没有基线就分不清「谁改了什么」，三方合并那套判定完全用不上：它会把两边
    每一行都当成「新增」，同名行各留一份（服务器 id 落进负数占位、本地 id 是
    正数，两个键永远对不上），推上去还会撞服务端的同名唯一约束，整份传不上去。

    这里只做**按名字配对**：同名的视为同一条、取改动较新的那份内容，各自独有
    的都留着。名字是这种清单里最自然的身份 —— 分组/分类在服务端本来就按名字
    唯一，物料用户也是按名字认的。

    结果会**重新编号**（分组/分类/物料都从 1 起）：两边的 id 各自独立、互不
    对应，混着用会让物料挂到错误的分组/分类上。引用（物料的分类、分配的分组、
    采购记录的分组、费用的物料）跟着一起重写。
    """
    rooms = _pick_by_name(mine.get("rooms", []), theirs.get("rooms", []),
                          lambda r: r.get("name", ""), lambda r: r.get("updated_at"))
    categories = _pick_by_name(mine.get("categories", []), theirs.get("categories", []),
                               lambda r: r.get("name", ""), lambda r: r.get("updated_at"))

    room_id_by_name: dict = {}
    for index, picked in enumerate(rooms):
        room_id_by_name.setdefault(picked["row"].get("name", ""), index + 1)
    category_id_by_name: dict = {}
    for index, picked in enumerate(categories):
        category_id_by_name.setdefault(picked["row"].get("name", ""), index + 1)

    # 物料/费用上的引用是 id，得先按 id 找回名字，才能改到新编号上
    local_room_name = {r.get("id") or 0: r.get("name", "") for r in mine.get("rooms", [])}
    remote_room_name = {r.get("id") or 0: r.get("name", "") for r in theirs.get("rooms", [])}
    local_category_name = {c.get("id") or 0: c.get("name", "") for c in mine.get("categories", [])}
    remote_category_name = {c.get("id") or 0: c.get("name", "") for c in theirs.get("categories", [])}
    local_item_name = {i.get("id") or 0: i.get("name", "") for i in mine.get("items", [])}
    remote_item_name = {i.get("id") or 0: i.get("name", "") for i in theirs.get("items", [])}

    def room_id_of(from_mine, ref_id):
        name = (local_room_name if from_mine else remote_room_name).get(ref_id or 0)
        return room_id_by_name.get(name) if name is not None else None

    def category_id_of(from_mine, ref_id):
        name = (local_category_name if from_mine else remote_category_name).get(ref_id or 0)
        return category_id_by_name.get(name) if name is not None else None

    items = _pick_by_name(mine.get("items", []), theirs.get("items", []),
                          lambda i: i.get("name", ""), lambda i: i.get("updated_at"))
    item_id_by_name: dict = {}
    for index, picked in enumerate(items):
        item_id_by_name.setdefault(picked["row"].get("name", ""), index + 1)

    merged_items = []
    for index, picked in enumerate(items):
        row = picked["row"]
        from_mine = picked["from_mine"]
        merged_items.append({
            "id": index + 1,
            "name": row.get("name", ""),
            "category_id": category_id_of(from_mine, row.get("category_id")),
            "unit": row.get("unit") or "个",
            "brand": row.get("brand") or "",
            "model": row.get("model") or "",
            "qty_total": row.get("qty_total") or 0,
            "price": row.get("price") or 0,
            "discount_price": row.get("discount_price"),
            "note": row.get("note") or "",
            "sort": row.get("sort") or 0,
            "deleted_at": row.get("deleted_at"),
            "created_at": row.get("created_at"),
            "updated_at": row.get("updated_at"),
            "allocations": [{"room_id": room_id_of(from_mine, a.get("room_id")),
                             "qty": a.get("qty") or 0,
                             "price_override": a.get("price_override"),
                             "note": a.get("note") or ""}
                            for a in row.get("allocations", [])],
            "records": [{"qty": r.get("qty") or 0, "amount": r.get("amount") or 0,
                         # 缺字段保持 None（老服务器），落地前回填本地旧值
                         "is_deposit": r.get("is_deposit"),
                         "date": r.get("date") or "", "note": r.get("note") or "",
                         "vendor": r.get("vendor") or "", "order_no": r.get("order_no") or "",
                         "room_ids": [x for x in (room_id_of(from_mine, rid)
                                                  for rid in (r.get("room_ids") or []))
                                      if x is not None],
                         "created_at": r.get("created_at"), "updated_at": r.get("updated_at")}
                        for r in row.get("records", [])],
        })

    merged_expenses = []
    for picked in _pick_by_name(mine.get("expenses", []), theirs.get("expenses", []),
                                _expense_name, lambda e: e.get("updated_at")):
        row = picked["row"]
        from_mine = picked["from_mine"]
        name = (local_item_name if from_mine else remote_item_name).get(row.get("item_id") or 0)
        merged_expenses.append({
            "kind": row.get("kind") or "运费", "amount": row.get("amount") or 0,
            "date": row.get("date") or "", "vendor": row.get("vendor") or "",
            "order_no": row.get("order_no") or "", "note": row.get("note") or "",
            "item_id": item_id_by_name.get(name) if name is not None else None,
            "created_at": row.get("created_at"), "updated_at": row.get("updated_at"),
        })

    list_meta = dict(mine.get("list", {}))
    stamps = [v for v in (mine.get("list", {}).get("updated_at"),
                          theirs.get("list", {}).get("updated_at")) if v]
    list_meta["updated_at"] = max(stamps) if stamps else ""

    return {
        "version": mine.get("version", 1),
        "list": list_meta,
        "rooms": [{"id": i + 1, "name": p["row"].get("name", ""), "sort": p["row"].get("sort") or 0,
                   "created_at": p["row"].get("created_at"),
                   "updated_at": p["row"].get("updated_at")}
                  for i, p in enumerate(rooms)],
        "categories": [{"id": i + 1, "name": p["row"].get("name", ""),
                        "sort": p["row"].get("sort") or 0,
                        "created_at": p["row"].get("created_at"),
                        "updated_at": p["row"].get("updated_at")}
                       for i, p in enumerate(categories)],
        "items": merged_items,
        "expenses": merged_expenses,
    }

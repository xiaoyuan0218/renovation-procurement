"""操作日志：记录人与 API 密钥的每一次写操作，常用三类还能一键回退。

分两层，职责不同：

- **中间件统一写日志**（main.py 里的 audit_middleware）：来源（人 / 密钥）、
  动作名、结果状态码。动作名查下面的 ACTIONS 表 —— 路径参数先归一化成
  {id} 再匹配，思路与 api_docs.py 相同。没收录的接口兜底记成「方法 路径」。
- **路由补中间件不知道的细节**：`request.state.audit_subject` 是刚操作的
  对象叫什么（让描述从「新增物料」变成「新增物料 · 物料「筒灯」」）；
  `request.state.audit_undo` 是可回退操作的操作前快照。

回退的语义：把数据放回**这条操作之前**的样子。只允许回退最近一条 ——
中间只要夹了任何别的写操作，更早的就不再开放回退，否则会连中间的改动
一起覆盖掉。
"""

import json
import re
from datetime import datetime

from sqlalchemy.orm import Session

from . import auth
from .models import (Allocation, ApiKey, Category, ExtraExpense, Item,
                     OperationLog, PurchaseRecord, RecordRoom, utcnow)
from .services import compute

# 动作名：路径参数归一化成 {id} 之后按 (方法, 路径) 查
ACTIONS = {
    ("POST", "/api/items"): "新增物料",
    ("PUT", "/api/items/{id}"): "修改物料",
    ("PATCH", "/api/items/{id}"): "修改物料",
    ("DELETE", "/api/items/{id}"): "删除物料（进回收站）",
    ("POST", "/api/items/{id}/records"): "记一笔采购",
    ("DELETE", "/api/items/{id}/records"): "清空采购记录",
    ("POST", "/api/items/batch/delete"): "批量删除物料",
    ("PUT", "/api/records/{id}"): "修改采购记录",
    ("DELETE", "/api/records/{id}"): "删除采购记录",
    ("POST", "/api/rooms"): "新增分组",
    ("PUT", "/api/rooms/{id}"): "修改分组",
    ("DELETE", "/api/rooms/{id}"): "删除分组",
    ("POST", "/api/categories"): "新增分类",
    ("PUT", "/api/categories/{id}"): "修改分类",
    ("DELETE", "/api/categories/{id}"): "删除分类",
    ("POST", "/api/lists"): "新建清单",
    ("PUT", "/api/lists/{id}"): "修改清单",
    ("DELETE", "/api/lists/{id}"): "删除清单",
    ("PUT", "/api/matrix/cell"): "修改分配矩阵",
    ("POST", "/api/trash/{id}/restore"): "从回收站恢复物料",
    ("DELETE", "/api/trash/{id}"): "彻底删除物料",
    ("DELETE", "/api/trash"): "清空回收站",
    ("POST", "/api/expenses"): "记一笔费用",
    ("PUT", "/api/expenses/{id}"): "修改费用",
    ("DELETE", "/api/expenses/{id}"): "删除费用",
    ("POST", "/api/import"): "导入表格",
    ("POST", "/api/backup/restore"): "恢复整库备份",
    ("PUT", "/api/sync/lists/{id}"): "同步覆盖清单",
    ("POST", "/api/sync/lists"): "同步新建清单",
    ("POST", "/api/keys"): "生成 API 密钥",
    ("DELETE", "/api/keys/{id}"): "撤销 API 密钥",
    ("POST", "/api/auth/setup"): "创建管理员账号",
    ("POST", "/api/auth/login"): "登录",
    ("POST", "/api/auth/password"): "修改密码",
    ("POST", "/api/logs/{id}/undo"): "回退操作",
    # 桌面端连服务器同步（自动同步不在此列，见 SKIPPED）
    ("POST", "/api/desktop/login"): "连接服务器",
    ("POST", "/api/desktop/sync"): "与服务器同步",
    ("POST", "/api/desktop/upload"): "上传清单到服务器",
    ("POST", "/api/desktop/resolve-upload"): "处理清单冲突",
    ("POST", "/api/desktop/pull"): "从服务器拉取清单",
    ("POST", "/api/desktop/unbind"): "解除服务器绑定",
}

# 不碰业务数据、因此也不阻断回退的操作：最新一条若是它们，往前找写操作。
# 回退操作也在这一档 —— 它是把数据放回某个历史快照，不影响更早快照的有效性，
# 用户连着撤两步（先撤记账、再撤新增）是合理的
NON_DATA = {
    ("POST", "/api/auth/setup"),
    ("POST", "/api/auth/login"),
    ("POST", "/api/auth/password"),
    ("POST", "/api/logs/{id}/undo"),
}

# 明确不记的：退出登录没留下值得翻的痕迹；**自动同步**是后台行为，本地一改动
# 就防抖触发一次，记进去只会把用户真正的操作淹掉（手动同步照记，见 ACTIONS）。
SKIPPED = {
    ("POST", "/api/auth/logout"),
    ("POST", "/api/desktop/auto"),
}

# 日志只留最近 LIMIT 条（写满就裁到 KEEP）——它是翻最近发生的事用的，不是审计归档
LOG_LIMIT = 2000
LOG_KEEP = 1800

_NUMBERS = re.compile(r"/\d+(?=/|$)")


def normalize_path(path: str) -> str:
    """把路径里的数字段换成 {id}，动作名才能按模式匹配。"""
    return _NUMBERS.sub("/{id}", path)


def action_name(method: str, path: str) -> str | None:
    return ACTIONS.get((method, normalize_path(path)))


# 日志按接口前缀归类，供「按类别筛选」用。放查询侧而不是落库：
# operation_logs 的表结构已经定型，按 path 前缀推导同样能筛，省一次迁移
CATEGORY_PREFIXES = {
    "item": ("/api/items", "/api/records", "/api/trash", "/api/matrix"),
    "expense": ("/api/expenses",),
    "roomcat": ("/api/rooms", "/api/categories"),
    "list": ("/api/lists",),
    "key": ("/api/keys",),
    "auth": ("/api/auth",),
    "log": ("/api/logs",),
}


def actor_of(request, db: Session) -> tuple[str, str]:
    """这次请求是谁发起的：API 密钥认到具体的钥匙，网页/App 认到账号。"""
    key = auth._extract_api_key(request)
    if key:
        row = (db.query(ApiKey)
               .filter(ApiKey.key_hash == auth.hash_api_key(key)).first())
        if row:
            return "api", f"API 密钥「{row.name}」"
    user = auth.current_user_or_none(request, db)
    if user:
        return "human", f"本人（{user.username}）"
    return "human", "匿名"


def write_log(db: Session, request, status_code: int) -> None:
    """中间件在响应完成后调用：落一条日志，超出上限就裁旧的。"""
    method, path = request.method, request.url.path
    if (method, path) in SKIPPED:
        return
    action = action_name(method, path) or f"{method} {normalize_path(path)}"
    subject = getattr(request.state, "audit_subject", None)
    if subject:
        action = f"{action} · {subject}"
    undo = getattr(request.state, "audit_undo", None)
    # 改了什么：路由能说清的自己说（audit_detail），说不清的（物料/费用那几类）
    # 拿操作前快照和当前状态比一次，把差异补上
    detail = getattr(request.state, "audit_detail", None) or _auto_detail(db, undo)
    if detail:
        action = f"{action} · {detail}"
    kind, name = actor_of(request, db)
    db.add(OperationLog(
        actor_kind=kind, actor_name=name, action=action[:150],
        method=method, path=path[:200], status_code=status_code,
        undo_kind=undo["kind"] if undo else None,
        undo_data=json.dumps(undo["data"], ensure_ascii=False) if undo else None,
    ))
    db.commit()
    _trim(db)


# ---------------------------------------------------------------- 变更明细
#
# 字段名到中文的对照。改了什么要说人话：「价格 88 → 78」而不是「price 88 → 78」

_ITEM_LABELS = (("name", "名称"), ("category_id", "分类"), ("unit", "单位"),
                ("brand", "品牌"), ("model", "型号"), ("qty_total", "总量"),
                ("price", "单价"), ("discount_price", "日常单价"), ("note", "备注"))

_EXPENSE_LABELS = (("kind", "类型"), ("amount", "金额"), ("date", "日期"),
                   ("vendor", "商家"), ("order_no", "订单号"), ("note", "备注"),
                   ("item_id", "关联物料"))


def _text(value) -> str:
    """数值原样，文本加书名号 —— 「名称 筒灯 → 筒灯新款」读起来分不清哪截是值。"""
    if value is None or value == "":
        return "空"
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(int(value)) if float(value) == int(value) else str(value)
    return f"「{value}」"


def _category_name(db: Session, category_id) -> str:
    if category_id is None:
        return ""
    row = db.get(Category, category_id)
    return row.name if row else str(category_id)


def _item_name(db: Session, item_id) -> str:
    if item_id is None:
        return ""
    row = db.get(Item, item_id)
    return row.name if row else str(item_id)


def _auto_detail(db: Session, undo: dict | None) -> str:
    """拿操作前的快照和库里现在的状态比一比，用中文说出改了什么。

    只对带快照的操作有效（物料、费用的增删改）；分组改名之类没有快照，
    由路由自己往 request.state.audit_detail 里写一句。
    """
    if not undo:
        return ""
    kind, before = undo.get("kind"), undo.get("data")
    if kind == "item_restore":
        after = snapshot_item(db, before["item"]["id"])
        return diff_item(db, before, after) if after else ""
    if kind == "expense_restore":
        after = snapshot_expense(db, before["id"])
        return diff_expense(db, before, after) if after else ""
    return ""


def diff_item(db: Session, before: dict, after: dict) -> str:
    """两次物料快照之间的差异。布点与采购记录按条数比 —— 逐条列出来太长。"""
    b, a = before["item"], after["item"]
    # 软删：deleted_at 从空变成有值，说「移入回收站」比甩一个时间戳强
    if not b.get("deleted_at") and a.get("deleted_at"):
        return "移入回收站"
    parts = []
    for field, label in _ITEM_LABELS:
        bv, av = b.get(field), a.get(field)
        if bv == av:
            continue
        if field == "category_id":
            parts.append(f"{label}「{_category_name(db, bv)}」→「{_category_name(db, av)}」")
        else:
            parts.append(f"{label} {_text(bv)} → {_text(av)}")
    for key, label in (("allocations", "布点"), ("records", "采购记录")):
        nb, na = len(before[key]), len(after[key])
        if nb != na:
            parts.append(f"{label} {nb} → {na} 条")
    return "；".join(parts)


def diff_expense(db: Session, before: dict, after: dict) -> str:
    parts = []
    for field, label in _EXPENSE_LABELS:
        bv, av = before.get(field), after.get(field)
        if bv == av:
            continue
        if field == "item_id":
            parts.append(f"{label}「{_item_name(db, bv)}」→「{_item_name(db, av)}」")
        else:
            parts.append(f"{label} {_text(bv)} → {_text(av)}")
    return "；".join(parts)


def _trim(db: Session) -> None:
    count = db.query(OperationLog.id).count()
    if count <= LOG_LIMIT:
        return
    extra = count - LOG_KEEP
    old = [r[0] for r in db.query(OperationLog.id)
           .order_by(OperationLog.id).limit(extra).all()]
    if old:
        db.query(OperationLog).filter(
            OperationLog.id.in_(old)).delete(synchronize_session=False)
        db.commit()


def undoable_id(db: Session) -> int | None:
    """当前能回退的那条日志 id；没有就返回 None。

    从最新往回找：登录、改密码这类不碰业务数据的不阻断；**已经被回退过
    的也不阻断**（它的快照消费掉了，但更早的快照仍然有效）；找到的第一条
    写操作若带快照就是可回退的，不带（导入、同步、恢复整库）则说明中间
    夹了无法撤销的大动作，更早的都不能再回退。
    """
    rows = (db.query(OperationLog)
            .order_by(OperationLog.id.desc()).limit(50).all())
    for row in rows:
        if row.undone_at is not None:
            continue  # 快照已被回退消费掉，跳过它继续往前找
        if (row.method, normalize_path(row.path)) in NON_DATA:
            continue
        return row.id if row.undo_kind else None
    return None


def _row(obj) -> dict:
    out = {}
    for col in obj.__table__.columns:
        value = getattr(obj, col.name)
        out[col.name] = (value.isoformat(sep=" ")
                         if isinstance(value, datetime) else value)
    return out


def snapshot_item(db: Session, item_id: int) -> dict | None:
    """物料完整快照：本体 + 布点 + 采购记录（含每笔涉及的分组）。

    记一笔、改一笔、删一笔这类操作影响的都是「这条物料」的一部分，统一
    抓整条物料的快照，回退逻辑就只有一种。
    """
    item = db.get(Item, item_id)
    if item is None:
        return None
    return {
        "item": _row(item),
        "allocations": [_row(a) for a in item.allocations],
        "records": [{**_row(r), "room_ids": [rr.room_id for rr in r.rooms]}
                    for r in item.records],
    }


def snapshot_expense(db: Session, expense_id: int) -> dict | None:
    row = db.get(ExtraExpense, expense_id)
    return _row(row) if row else None


def undo_log(db: Session, row: OperationLog) -> str:
    """执行回退，返回给人看的结果描述。"""
    data = json.loads(row.undo_data)
    kind = row.undo_kind
    if kind == "item_delete":
        item = db.get(Item, data["item_id"])
        if item is None:
            return "物料已经不存在，无需回退"
        name = item.name
        db.delete(item)
        db.commit()
        return f"已把「{name}」删掉，回到这次新增之前"
    if kind == "item_restore":
        return _restore_item(db, data)
    if kind == "expense_delete":
        exp = db.get(ExtraExpense, data["expense_id"])
        if exp is None:
            return "这笔费用已经不存在，无需回退"
        db.delete(exp)
        db.commit()
        return "已把那笔费用删掉，回到这次记账之前"
    if kind == "expense_restore":
        return _restore_expense(db, data)
    return "这条操作不支持回退"


def _parse_maybe_datetime(value):
    """快照里的时间戳存成了字符串，写回 DateTime 列前要转回来。

    只认完整时间戳（≥19 字符）；「2026-10-07」这种 10 位的是日期文本字段
    （如采购记录的 date），保持字符串原样。
    """
    if isinstance(value, str) and len(value) >= 19:
        try:
            return datetime.strptime(value[:19], "%Y-%m-%d %H:%M:%S")
        except ValueError:
            return value
    return value


def _restore_item(db: Session, snap: dict) -> str:
    data = snap["item"]
    item = db.get(Item, data["id"])
    if item is None:
        # 连 id 一起按快照重建：布点和记录引用的是这个 id，换了就断
        item = Item(id=data["id"])
        db.add(item)
    # 除主键、版本号、更新时间之外的字段全部还原；快照里有什么恢复什么，
    # 不手列字段名 —— 加了新列也自动跟上
    for field, value in data.items():
        if field not in ("id", "rev", "updated_at"):
            setattr(item, field, _parse_maybe_datetime(value))
    item.rev = (data.get("rev") or 1) + 1   # 让手机端同步把这次回退当新改动
    item.updated_at = utcnow()
    item.allocations.clear()
    item.records.clear()
    db.flush()
    for a in snap["allocations"]:
        db.add(Allocation(id=a["id"], item_id=item.id, room_id=a["room_id"],
                          qty=a["qty"], price_override=a["price_override"],
                          note=a["note"]))
    for r in snap["records"]:
        rec = PurchaseRecord(id=r["id"], item_id=item.id, qty=r["qty"],
                             amount=r["amount"], date=r["date"], note=r["note"],
                             vendor=r["vendor"], order_no=r["order_no"],
                             created_at=_parse_maybe_datetime(r["created_at"]),
                             updated_at=_parse_maybe_datetime(r["updated_at"]))
        db.add(rec)
        db.flush()
        for room_id in r["room_ids"]:
            db.add(RecordRoom(record_id=rec.id, room_id=room_id))
    item.bought = compute.item_status(item) == "done"
    db.commit()
    return (f"已把「{item.name}」恢复到这次操作之前"
            f"（布点 {len(snap['allocations'])} 条、采购记录 {len(snap['records'])} 笔一起还原）")


def _restore_expense(db: Session, snap: dict) -> str:
    row = db.get(ExtraExpense, snap["id"])
    if row is None:
        row = ExtraExpense(id=snap["id"])
        db.add(row)
    for field, value in snap.items():
        if field not in ("id", "updated_at"):
            setattr(row, field, _parse_maybe_datetime(value))
    row.updated_at = utcnow()
    db.commit()
    return f"已把费用「{row.kind}」恢复到这次操作之前"

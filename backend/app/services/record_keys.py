"""记录业务键：缺 is_deposit 的老客户端/老文件覆盖时，用它把旧记录认回来。

定金标记是 1.2.2 才加的。1.2.2 之前的手机/桌面客户端推送的 payload 里没有
这个字段、1.2.2 之前导出的表格里没有这一列 —— 这种"没带这个信息"不等于
"不是定金"：同步、以我为准、导入都是整份覆盖（清空重建），按 False 落库
一次就能把整份清单的定金标记抹平。行 id 覆盖后会重排，指望不上，所以按
两边都有的业务字段配对。
"""

from sqlalchemy.orm import Session

from ..models import Item, PurchaseRecord


def record_key(item_name, qty, amount, date, note, vendor, order_no) -> tuple:
    """一条记录的业务键。数量金额归一到 6 位小数：JSON 与数据库的浮点写法可能有别。"""

    def num(value) -> float:
        try:
            return round(float(value or 0), 6)
        except (TypeError, ValueError):
            return 0.0

    return (
        (item_name or "").strip(),
        num(qty),
        num(amount),
        (date or "").strip(),
        (note or "").strip(),
        (vendor or "").strip(),
        (order_no or "").strip(),
    )


def misses_deposit(records: list) -> bool:
    """这批记录一条都没带 is_deposit（缺字段 → None）：来源是老客户端或老文件。

    Pydantic 把缺字段补成 None、excel 解析对没有「定金」列的老文件也给 None，
    所以"全是 None"就是"这份东西不懂定金"，需要按业务键沿用旧值。
    """
    return bool(records) and all(rec.get("is_deposit") is None for rec in records)


def deposit_index(db: Session, list_id: int) -> dict:
    """覆盖前把旧记录的「业务键 → True」记一份，供缺字段的回填；查不到的当非定金。"""
    rows = (db.query(PurchaseRecord, Item.name)
            .join(Item, Item.id == PurchaseRecord.item_id)
            .filter(Item.list_id == list_id)
            .all())
    index = {}
    for record, item_name in rows:
        if record.is_deposit:
            index[record_key(item_name, record.qty, record.amount, record.date,
                             record.note, record.vendor, record.order_no)] = True
    return index


def fill_missing_deposit(db: Session, list_id: int, payload: dict) -> dict:
    """老服务器/老客户端没带 is_deposit 时，按业务键把本地旧值填进 payload。

    改的是传进来的这份 payload 本身：调用方随后会拿它存基线 —— 基线要跟落地
    后的内容一致，下一轮才不至于把"其实没变的本地内容"再判成新改动、反复推送。
    """
    records = [rec for row in payload.get("items", [])
               for rec in row.get("records", [])]
    if not misses_deposit(records):
        return payload
    index = deposit_index(db, list_id)
    for row in payload.get("items", []):
        for record in row.get("records", []):
            if record.get("is_deposit") is None:
                record["is_deposit"] = bool(index.get(record_key(
                    row.get("name"), record.get("qty"), record.get("amount"),
                    record.get("date"), record.get("note"),
                    record.get("vendor"), record.get("order_no")), False))
    return payload

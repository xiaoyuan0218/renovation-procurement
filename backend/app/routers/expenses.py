"""额外费用（运费 / 安装费 / 辅料）的增删改查。

这类钱独立于物料：它们**不参与**"原价合计 / 日常价合计"两个口径的三段拆分，
只在总览里单独汇总。这样单价不必再摊运费（单价回归真实），
"实际优惠"也不会因为摊了钱而变成负数。
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_list
from ..models import ExtraExpense, Item, ItemList
from ..schemas import ExpenseIn, ExpenseOut

router = APIRouter(prefix="/api/expenses", tags=["expenses"])


def _view(row: ExtraExpense) -> ExpenseOut:
    out = ExpenseOut.model_validate(row)
    out.item_name = row.item.name if row.item else ""
    return out


def _check_item(db: Session, item_id, lst: ItemList):
    """关联的物料得在当前清单里。回收站里的也允许关联 —— 货已经买了、
    只是物料被误删进回收站，这种时候不该拦着记账。"""
    if item_id is None:
        return None
    item = db.get(Item, item_id)
    if item is None or item.list_id != lst.id:
        raise HTTPException(400, f"物料 {item_id} 不属于当前清单")
    return item_id


def _in_list(db: Session, expense_id: int, lst: ItemList) -> ExtraExpense:
    row = db.get(ExtraExpense, expense_id)
    if row is None or row.list_id != lst.id:
        raise HTTPException(404, "费用记录不存在")
    return row


@router.get("", response_model=list[ExpenseOut])
def list_expenses(lst: ItemList = Depends(current_list),
                  db: Session = Depends(get_db)):
    # 有日期的排前面（按日期倒序），没填日期的垫底、按录入顺序
    rows = (db.query(ExtraExpense).filter(ExtraExpense.list_id == lst.id)
            .order_by(ExtraExpense.date.desc(), ExtraExpense.id.desc()).all())
    return [_view(row) for row in rows]


@router.post("", response_model=ExpenseOut)
def create_expense(data: ExpenseIn, lst: ItemList = Depends(current_list),
                   db: Session = Depends(get_db)):
    row = ExtraExpense(list_id=lst.id, kind=(data.kind or "运费").strip() or "运费",
                       amount=data.amount or 0, date=data.date,
                       vendor=data.vendor or "", order_no=data.order_no or "",
                       note=data.note or "",
                       item_id=_check_item(db, data.item_id, lst))
    db.add(row)
    db.commit()
    db.refresh(row)
    return _view(row)


@router.put("/{expense_id}", response_model=ExpenseOut)
def update_expense(expense_id: int, data: ExpenseIn,
                   lst: ItemList = Depends(current_list),
                   db: Session = Depends(get_db)):
    row = _in_list(db, expense_id, lst)
    row.kind = (data.kind or "运费").strip() or "运费"
    row.amount = data.amount or 0
    row.date = data.date
    row.vendor = data.vendor or ""
    row.order_no = data.order_no or ""
    row.note = data.note or ""
    row.item_id = _check_item(db, data.item_id, lst)
    db.commit()
    db.refresh(row)
    return _view(row)


@router.delete("/{expense_id}")
def delete_expense(expense_id: int, lst: ItemList = Depends(current_list),
                   db: Session = Depends(get_db)):
    row = _in_list(db, expense_id, lst)
    db.delete(row)
    db.commit()
    return {"ok": True}

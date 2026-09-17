"""回收站：删掉的物料先放这儿，能捞回来，也能彻底删掉。

配套的约定（改动查询时别忘了）：
  - 所有"正常界面"的查询都带 `Item.alive()`，被删的不会冒出来；
  - 通过 id 直接访问已删物料走正常接口会 404 —— 要动它得先从这儿恢复；
  - 只有这个文件和 items 的删除接口会碰 `deleted_at`。
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_list
from ..models import Allocation, Item, ItemList, PurchaseRecord
from ..schemas import TrashItemOut
from ..services import compute

router = APIRouter(prefix="/api/trash", tags=["trash"])


def _trashed(db: Session, item_id: int, lst: ItemList) -> Item:
    item = db.get(Item, item_id)
    if item is None or item.list_id != lst.id or item.deleted_at is None:
        raise HTTPException(404, "回收站里没有这条")
    return item


def _view(item: Item) -> dict:
    data = compute.item_dict(item)
    data["deleted_at"] = (item.deleted_at.strftime("%Y-%m-%d %H:%M")
                          if item.deleted_at else "")
    return data


@router.get("", response_model=list[TrashItemOut])
def list_trash(lst: ItemList = Depends(current_list),
               db: Session = Depends(get_db)):
    rows = (db.query(Item)
            .filter(Item.list_id == lst.id, Item.deleted_at.isnot(None))
            .order_by(Item.deleted_at.desc(), Item.id.desc()).all())
    return [_view(i) for i in rows]


@router.post("/{item_id}/restore", response_model=TrashItemOut)
def restore_item(item_id: int, lst: ItemList = Depends(current_list),
                 db: Session = Depends(get_db)):
    item = _trashed(db, item_id, lst)
    item.deleted_at = None
    item.touch()  # 让正在编辑这台设备的其它客户端保存时拿到 409
    db.commit()
    db.refresh(item)
    return _view(item)


@router.delete("/{item_id}")
def purge_item(item_id: int, lst: ItemList = Depends(current_list),
               db: Session = Depends(get_db)):
    """彻底删除这一条：连同它的分配与采购记录一起，不可恢复。"""
    item = _trashed(db, item_id, lst)
    db.query(PurchaseRecord).filter(PurchaseRecord.item_id == item_id).delete()
    db.query(Allocation).filter(Allocation.item_id == item_id).delete()
    db.delete(item)
    db.commit()
    return {"ok": True}


@router.delete("")
def purge_all(lst: ItemList = Depends(current_list),
              db: Session = Depends(get_db)):
    """清空回收站。"""
    ids = [row[0] for row in db.query(Item.id)
           .filter(Item.list_id == lst.id, Item.deleted_at.isnot(None)).all()]
    if ids:
        db.query(PurchaseRecord).filter(PurchaseRecord.item_id.in_(ids)) \
            .delete(synchronize_session=False)
        db.query(Allocation).filter(Allocation.item_id.in_(ids)) \
            .delete(synchronize_session=False)
        db.query(Item).filter(Item.id.in_(ids)).delete(synchronize_session=False)
        db.commit()
    return {"deleted": len(ids)}

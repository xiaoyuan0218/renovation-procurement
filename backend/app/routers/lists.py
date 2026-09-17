"""清单的增删改查。

一份清单就是一个独立项目：它有自己的条目、分组、分类，互不干扰。
删除清单会把它名下的东西一并删掉（ORM 级联），所以最后一份不允许删 ——
不然界面上就没有可用的清单了。
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Category, Item, ItemList, Room
from ..schemas import ItemListIn, ItemListOut

router = APIRouter(prefix="/api/lists", tags=["lists"])


def _counts(db: Session) -> dict:
    def by_list(model):
        return dict(db.query(model.list_id, func.count(model.id))
                    .group_by(model.list_id).all())
    # 条目数不算回收站里的那些：界面上看到的和列表里显示的数字要对得上
    items = dict(db.query(Item.list_id, func.count(Item.id))
                 .filter(Item.alive()).group_by(Item.list_id).all())
    return {"item_count": items, "room_count": by_list(Room),
            "category_count": by_list(Category)}


def _view(lst: ItemList, counts: dict) -> ItemListOut:
    out = ItemListOut.model_validate(lst)
    out.item_count = counts["item_count"].get(lst.id, 0)
    out.room_count = counts["room_count"].get(lst.id, 0)
    out.category_count = counts["category_count"].get(lst.id, 0)
    return out


@router.get("", response_model=list[ItemListOut])
def list_lists(db: Session = Depends(get_db)):
    lists = db.query(ItemList).order_by(ItemList.sort, ItemList.id).all()
    counts = _counts(db)
    return [_view(lst, counts) for lst in lists]


@router.post("", response_model=ItemListOut)
def create_list(data: ItemListIn, db: Session = Depends(get_db)):
    if db.query(ItemList).filter(ItemList.name == data.name).first():
        raise HTTPException(400, f"清单「{data.name}」已存在")
    source = None
    if data.copy_from is not None:
        source = db.get(ItemList, data.copy_from)
        if source is None:
            raise HTTPException(400, "要复制的清单不存在")
    lst = ItemList(name=data.name, note=data.note or "", sort=data.sort)
    db.add(lst)
    db.flush()  # 拿到新 id 才能给复制过来的分组/分类挂 list_id
    if source is not None:
        # 只搬结构：分组和分类。物料、分配、采购记录一律不带走 ——
        # 复制结构是为了省去重新建十几个房间的功夫，不是复制一份数据。
        for room in (db.query(Room).filter(Room.list_id == source.id)
                     .order_by(Room.sort, Room.id).all()):
            db.add(Room(list_id=lst.id, name=room.name, sort=room.sort))
        for cat in (db.query(Category).filter(Category.list_id == source.id)
                    .order_by(Category.sort, Category.id).all()):
            db.add(Category(list_id=lst.id, name=cat.name, sort=cat.sort))
    db.commit()
    db.refresh(lst)
    return _view(lst, _counts(db))


@router.put("/{list_id}", response_model=ItemListOut)
def update_list(list_id: int, data: ItemListIn, db: Session = Depends(get_db)):
    lst = db.get(ItemList, list_id)
    if not lst:
        raise HTTPException(404, "清单不存在")
    dup = (db.query(ItemList)
           .filter(ItemList.name == data.name, ItemList.id != list_id).first())
    if dup:
        raise HTTPException(400, f"清单「{data.name}」已存在")
    lst.name = data.name
    lst.note = data.note or ""
    lst.sort = data.sort
    db.commit()
    db.refresh(lst)
    return _view(lst, _counts(db))


@router.delete("/{list_id}")
def delete_list(list_id: int, db: Session = Depends(get_db)):
    lst = db.get(ItemList, list_id)
    if not lst:
        raise HTTPException(404, "清单不存在")
    if db.query(ItemList).count() <= 1:
        raise HTTPException(400, "至少要保留一份清单")
    db.delete(lst)  # 条目、分组、分类连同它们的记录/分配一起走
    db.commit()
    return {"ok": True}

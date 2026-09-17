from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_list, room_in_list
from ..models import Category, ItemList, Room
from ..schemas import CategoryIn, CategoryOut, RoomIn, RoomOut

router = APIRouter(prefix="/api", tags=["base-data"])


def _category_in_list(db: Session, cat_id: int, lst: ItemList) -> Category:
    cat = db.get(Category, cat_id)
    if cat is None or cat.list_id != lst.id:
        raise HTTPException(404, "类目不存在")
    return cat


# ---------- 房间（分组） ----------

@router.get("/rooms", response_model=list[RoomOut])
def list_rooms(lst: ItemList = Depends(current_list),
               db: Session = Depends(get_db)):
    return (db.query(Room).filter(Room.list_id == lst.id)
            .order_by(Room.sort, Room.id).all())


@router.post("/rooms", response_model=RoomOut)
def create_room(data: RoomIn, lst: ItemList = Depends(current_list),
                db: Session = Depends(get_db)):
    dup = (db.query(Room)
           .filter(Room.list_id == lst.id, Room.name == data.name).first())
    if dup:
        raise HTTPException(400, f"分组「{data.name}」已存在")
    room = Room(list_id=lst.id, name=data.name, sort=data.sort)
    db.add(room)
    db.commit()
    db.refresh(room)
    return room


@router.put("/rooms/{room_id}", response_model=RoomOut)
def update_room(room_id: int, data: RoomIn, lst: ItemList = Depends(current_list),
                db: Session = Depends(get_db)):
    room = room_in_list(db, room_id, lst)
    room.name = data.name
    room.sort = data.sort
    db.commit()
    db.refresh(room)
    return room


@router.delete("/rooms/{room_id}")
def delete_room(room_id: int, lst: ItemList = Depends(current_list),
                db: Session = Depends(get_db)):
    room = room_in_list(db, room_id, lst)
    db.delete(room)  # 布点明细级联删除
    db.commit()
    return {"ok": True}


# ---------- 类目（分类） ----------

@router.get("/categories", response_model=list[CategoryOut])
def list_categories(lst: ItemList = Depends(current_list),
                    db: Session = Depends(get_db)):
    return (db.query(Category).filter(Category.list_id == lst.id)
            .order_by(Category.sort, Category.id).all())


@router.post("/categories", response_model=CategoryOut)
def create_category(data: CategoryIn, lst: ItemList = Depends(current_list),
                    db: Session = Depends(get_db)):
    dup = (db.query(Category)
           .filter(Category.list_id == lst.id, Category.name == data.name).first())
    if dup:
        raise HTTPException(400, f"类目「{data.name}」已存在")
    cat = Category(list_id=lst.id, name=data.name, sort=data.sort)
    db.add(cat)
    db.commit()
    db.refresh(cat)
    return cat


@router.put("/categories/{cat_id}", response_model=CategoryOut)
def update_category(cat_id: int, data: CategoryIn,
                    lst: ItemList = Depends(current_list),
                    db: Session = Depends(get_db)):
    cat = _category_in_list(db, cat_id, lst)
    cat.name = data.name
    cat.sort = data.sort
    db.commit()
    db.refresh(cat)
    return cat


@router.delete("/categories/{cat_id}")
def delete_category(cat_id: int, lst: ItemList = Depends(current_list),
                    db: Session = Depends(get_db)):
    cat = _category_in_list(db, cat_id, lst)
    if cat.items:
        raise HTTPException(400, "该类目下仍有物料，请先移动物料再删除")
    db.delete(cat)
    db.commit()
    return {"ok": True}

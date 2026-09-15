from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Category, Room
from ..schemas import CategoryIn, CategoryOut, RoomIn, RoomOut

router = APIRouter(prefix="/api", tags=["base-data"])


# ---------- 房间 ----------

@router.get("/rooms", response_model=list[RoomOut])
def list_rooms(db: Session = Depends(get_db)):
    return db.query(Room).order_by(Room.sort, Room.id).all()


@router.post("/rooms", response_model=RoomOut)
def create_room(data: RoomIn, db: Session = Depends(get_db)):
    if db.query(Room).filter(Room.name == data.name).first():
        raise HTTPException(400, f"房间「{data.name}」已存在")
    room = Room(name=data.name, sort=data.sort)
    db.add(room)
    db.commit()
    db.refresh(room)
    return room


@router.put("/rooms/{room_id}", response_model=RoomOut)
def update_room(room_id: int, data: RoomIn, db: Session = Depends(get_db)):
    room = db.get(Room, room_id)
    if not room:
        raise HTTPException(404, "房间不存在")
    room.name = data.name
    room.sort = data.sort
    db.commit()
    db.refresh(room)
    return room


@router.delete("/rooms/{room_id}")
def delete_room(room_id: int, db: Session = Depends(get_db)):
    room = db.get(Room, room_id)
    if not room:
        raise HTTPException(404, "房间不存在")
    db.delete(room)  # 布点明细级联删除
    db.commit()
    return {"ok": True}


# ---------- 类目 ----------

@router.get("/categories", response_model=list[CategoryOut])
def list_categories(db: Session = Depends(get_db)):
    return db.query(Category).order_by(Category.sort, Category.id).all()


@router.post("/categories", response_model=CategoryOut)
def create_category(data: CategoryIn, db: Session = Depends(get_db)):
    if db.query(Category).filter(Category.name == data.name).first():
        raise HTTPException(400, f"类目「{data.name}」已存在")
    cat = Category(name=data.name, sort=data.sort)
    db.add(cat)
    db.commit()
    db.refresh(cat)
    return cat


@router.put("/categories/{cat_id}", response_model=CategoryOut)
def update_category(cat_id: int, data: CategoryIn, db: Session = Depends(get_db)):
    cat = db.get(Category, cat_id)
    if not cat:
        raise HTTPException(404, "类目不存在")
    cat.name = data.name
    cat.sort = data.sort
    db.commit()
    db.refresh(cat)
    return cat


@router.delete("/categories/{cat_id}")
def delete_category(cat_id: int, db: Session = Depends(get_db)):
    cat = db.get(Category, cat_id)
    if not cat:
        raise HTTPException(404, "类目不存在")
    if cat.items:
        raise HTTPException(400, "该类目下仍有物料，请先移动物料再删除")
    db.delete(cat)
    db.commit()
    return {"ok": True}

"""接口的「当前清单」上下文：每个业务接口先确定自己在操作哪一份清单。

三个来源，优先级从高到低：
  1. 查询参数 ?list_id= —— 导出、下载这类用 <a href> 直接导航的只能走它；
  2. 请求头 X-List-Id —— 网页和安卓正常请求都带；
  3. 都不带 —— 落到第一份清单。升级后还没更新的老版安卓会走这条，
     而第一份清单正是迁移出来的那份、装着升级前的全部数据，所以老客户端
     看到的东西和升级前完全一致。
"""

from fastapi import Depends, HTTPException, Query, Request
from sqlalchemy.orm import Session

from .db import get_db
from .models import Category, Item, ItemList, Room

DEFAULT_LIST_NAME = "采购清单"


def ensure_default_list(db: Session) -> ItemList:
    """兜底：库里的清单被删光了也要有东西可用。"""
    lst = db.query(ItemList).order_by(ItemList.sort, ItemList.id).first()
    if lst is None:
        lst = ItemList(name=DEFAULT_LIST_NAME, sort=0)
        db.add(lst)
        db.commit()
        db.refresh(lst)
    return lst


def current_list(request: Request, list_id: int | None = Query(default=None),
                 db: Session = Depends(get_db)) -> ItemList:
    if list_id is None:
        raw = (request.headers.get("x-list-id") or "").strip()
        if raw:
            try:
                list_id = int(raw)
            except ValueError:
                raise HTTPException(400, "X-List-Id 必须是数字")
    if list_id is not None:
        lst = db.get(ItemList, list_id)
        if lst is None:
            # 客户端拿着的清单在别处被删了，让它重新拉清单列表
            raise HTTPException(404, "清单不存在，可能已被其他设备删除")
        return lst
    return ensure_default_list(db)


def item_in_list(db: Session, item_id: int, lst: ItemList) -> Item:
    """取物料并确认它属于当前清单。不属于时按 404 处理 ——
    别人的清单里有什么，不该从这个接口漏出去。

    已经移进回收站的也按 404：它在回收站里，不该还能被正常编辑
    （要动它得先从回收站恢复）。
    """
    item = db.get(Item, item_id)
    if item is None or item.list_id != lst.id or item.deleted_at is not None:
        raise HTTPException(404, "物料不存在")
    return item


def room_in_list(db: Session, room_id: int, lst: ItemList) -> Room:
    room = db.get(Room, room_id)
    if room is None or room.list_id != lst.id:
        raise HTTPException(404, "分组不存在")
    return room


def category_in_list(db: Session, category_id, lst: ItemList):
    """校验分类归属，返回可直接写进条目的 category_id（None 原样通过）。

    跨清单的 id 是客户端出错的征兆，当场挡住 —— 静默置空会变成"保存后
    分类莫名其妙没了"，更难查。
    """
    if category_id is None:
        return None
    cat = db.get(Category, category_id)
    if cat is None or cat.list_id != lst.id:
        raise HTTPException(400, f"分类 {category_id} 不属于当前清单")
    return category_id

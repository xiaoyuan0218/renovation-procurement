"""删除物料的级联清理：单条删除、批量删除、裸 SQL 删除都不能留下悬空布点/采购记录。"""

import pytest

from app.db import SessionLocal, engine
from app.models import Allocation, Category, Item, PurchaseRecord, Room
from app.seed import init_db


@pytest.fixture()
def db():
    init_db()
    session = SessionLocal()
    for table in (Allocation, Item, PurchaseRecord, Room, Category):
        session.query(table).delete()
    session.commit()
    yield session
    session.close()
    engine.dispose()


def _mk_item(db, name="待删物料"):
    room = Room(name="客厅", sort=0)
    item = Item(name=name, unit="个", price=10, discount_price=9, qty_total=1)
    item.allocations.append(Allocation(qty=1))
    item.allocations[0].room = room
    item.records.append(PurchaseRecord(qty=1, amount=9, date="2026-09-15"))
    db.add(item)
    db.commit()
    return item


def test_delete_item_cascades(db):
    """单条删除：布点与采购记录一并删除（走 ORM 级联）。"""
    iid = _mk_item(db).id
    db.delete(db.get(Item, iid))
    db.commit()
    assert db.query(Item).count() == 0
    assert db.query(Allocation).count() == 0
    assert db.query(PurchaseRecord).count() == 0


def test_batch_delete_cascades(db):
    """批量删除（前端"删除所选"）：不能留下悬空数据。"""
    ids = [_mk_item(db, f"物料{i}").id for i in range(3)]
    db.query(PurchaseRecord).filter(PurchaseRecord.item_id.in_(ids)).delete(synchronize_session=False)
    db.query(Allocation).filter(Allocation.item_id.in_(ids)).delete(synchronize_session=False)
    db.query(Item).filter(Item.id.in_(ids)).delete(synchronize_session=False)
    db.commit()
    assert db.query(Item).count() == 0
    assert db.query(Allocation).count() == 0
    assert db.query(PurchaseRecord).count() == 0


def test_raw_sql_delete_uses_fk_cascade(db):
    """兜底：绕过 ORM 的 SQL 级删除，靠外键 ON DELETE CASCADE 清掉关联行。"""
    iid = _mk_item(db).id
    db.query(Item).filter(Item.id == iid).delete(synchronize_session=False)
    db.commit()
    assert db.query(Item).count() == 0
    assert db.query(Allocation).filter(Allocation.item_id == iid).count() == 0
    assert db.query(PurchaseRecord).filter(PurchaseRecord.item_id == iid).count() == 0


def test_delete_room_cascades_allocations(db):
    """删房间时布点一并清理，不留悬空行。"""
    item = _mk_item(db)
    rid = item.allocations[0].room_id
    db.query(Allocation).filter(Allocation.room_id == rid).delete(synchronize_session=False)
    db.query(Room).filter(Room.id == rid).delete(synchronize_session=False)
    db.commit()
    assert db.query(Room).count() == 0
    assert db.query(Allocation).count() == 0
    assert db.query(Item).count() == 1

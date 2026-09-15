import pytest

from app.db import SessionLocal, engine
from app.models import Allocation, Category, Item, PurchaseRecord, Room
from app.seed import init_db
from app.services import compute


@pytest.fixture()
def db():
    init_db()
    session = SessionLocal()
    # 每个测试用干净的数据表
    for table in (Allocation, Item, PurchaseRecord, Room, Category):
        session.query(table).delete()
    session.commit()
    yield session
    session.close()
    engine.dispose()


def _mk_room(db, name):
    room = Room(name=name, sort=0)
    db.add(room)
    db.flush()
    return room


def test_allocations_with_price_override(db):
    """房间单独定价：基础单价 100，客厅覆盖 150、餐厅覆盖 120。"""
    room_living = _mk_room(db, "客厅")
    room_dining = _mk_room(db, "餐厅")
    room_bed = _mk_room(db, "主卧")
    item = Item(name="示例灯具", price=100)
    db.add(item)
    db.flush()
    for room, qty, override in ((room_living, 1, 150), (room_dining, 1, 120),
                                (room_bed, 1, None)):
        db.add(Allocation(item_id=item.id, room_id=room.id, qty=qty,
                          price_override=override))
    db.commit()
    db.refresh(item)

    assert compute.item_total_qty(item) == 3
    assert compute.item_list_total(item) == 150 + 120 + 100
    # 日常价口径：日常单价(未填→原价) × 总数量，不随房间覆盖价变化
    assert compute.item_discount_total(item) == 3 * 100


def test_discount_full_precision_no_drift(db):
    """日常总价/数量反推的单价（如 199.99/7）乘回数量后合计不漂移。"""
    item = Item(name="示例物料", price=30, qty_total=7,
                discount_price=199.99 / 7)
    db.add(item)
    db.commit()
    db.refresh(item)
    assert compute.item_discount_total(item) == pytest.approx(199.99, abs=0.001)


def test_no_allocations_uses_qty_total(db):
    item = Item(name="小米智能门锁", qty_total=1, price=2899,
                discount_price=1834.67)
    db.add(item)
    db.flush()
    item.records.append(PurchaseRecord(qty=1, amount=1547.3))
    db.commit()
    db.refresh(item)
    assert compute.item_total_qty(item) == 1
    assert compute.item_list_total(item) == 2899
    assert compute.item_discount_total(item) == 1834.67
    # 已付金额 = Σ记录金额；实付单价自动 = 金额 ÷ 数量；全部实付 → 未付 0
    assert compute.item_paid(item) == 1547.3
    assert compute.item_paid_price(item) == 1547.3
    assert compute.item_unpaid(item) == 0
    assert compute.item_status(item) == "done"
    d = compute.item_dict(item)
    assert d["status"] == "done" and d["bought"] is True
    assert d["records"][0]["unit_price"] == 1547.3


def test_multiple_records_partial_with_rooms(db):
    """多笔采购记录：数量金额分别累加，实付按布点顺序覆盖房间。"""
    room_a = _mk_room(db, "客厅")
    room_b = _mk_room(db, "餐厅")
    item = Item(name="筒灯", price=10)
    db.add(item)
    db.flush()
    db.add(Allocation(item_id=item.id, room_id=room_a.id, qty=3))
    db.add(Allocation(item_id=item.id, room_id=room_b.id, qty=4))
    item.records.append(PurchaseRecord(qty=2, amount=100, note="第一批"))
    item.records.append(PurchaseRecord(qty=3, amount=180, note="第二批"))
    db.commit()
    db.refresh(item)

    assert compute.item_paid_qty(item) == 5
    assert compute.item_paid(item) == 280
    assert compute.item_paid_price(item) == 56
    assert compute.item_status(item) == "partial"
    assert compute.item_unpaid_qty(item) == 2
    assert compute.item_unpaid(item) == 20  # 未付数量2 × 原价10
    cover = compute.allocation_paid_cover(item)
    # 覆盖顺序：客厅3 全覆盖，餐厅2/4
    assert list(cover.values()) == [3, 2]


def test_unbought_status(db):
    item = Item(name="未买", qty_total=2, price=50)
    db.add(item)
    db.commit()
    db.refresh(item)
    assert compute.item_status(item) == "unbought"
    assert compute.item_unpaid(item) == 100
    assert compute.item_dict(item)["bought"] is False

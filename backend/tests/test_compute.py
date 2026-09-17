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
    """多笔采购记录：数量金额分别累加；多分组只买一半时不猜哪个分组买齐了。"""
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
    # 分给了两个房间、只买了一半：先买哪一间系统并不知道，按顺序抵扣是猜测，
    # 所以一个都不标（界面据此不标绿），进度由物料行上的「实付 5/7」表达
    assert compute.allocation_paid_cover(item) == {}


def test_paid_cover_exact_for_single_group(db):
    """只分给一个分组：不存在谁先谁后，部分买齐也给准确的覆盖值。"""
    room = _mk_room(db, "客厅")
    item = Item(name="客厅吊灯", price=10)
    db.add(item)
    db.flush()
    db.add(Allocation(item_id=item.id, room_id=room.id, qty=4))
    item.records.append(PurchaseRecord(qty=1, amount=10))
    db.commit()
    db.refresh(item)

    assert compute.item_status(item) == "partial"
    assert list(compute.allocation_paid_cover(item).values()) == [1]


def test_paid_cover_full_when_item_is_done(db):
    """整条物料已全部买齐：每一行都满，这是事实，照实标绿。"""
    room_a = _mk_room(db, "客厅")
    room_b = _mk_room(db, "餐厅")
    item = Item(name="筒灯", price=10)
    db.add(item)
    db.flush()
    db.add(Allocation(item_id=item.id, room_id=room_a.id, qty=3))
    db.add(Allocation(item_id=item.id, room_id=room_b.id, qty=4))
    item.records.append(PurchaseRecord(qty=7, amount=70))
    db.commit()
    db.refresh(item)

    assert compute.item_status(item) == "done"
    assert list(compute.allocation_paid_cover(item).values()) == [3, 4]


def test_unbought_status(db):
    item = Item(name="未买", qty_total=2, price=50)
    db.add(item)
    db.commit()
    db.refresh(item)
    assert compute.item_status(item) == "unbought"
    assert compute.item_unpaid(item) == 100
    assert compute.item_dict(item)["bought"] is False


# ---------------------------------------------------------------- 两个口径的三段拆分

def _assert_split(d):
    """两个口径各自的三段拆分必须严格等于合计（到分，不允许浮点漂移）。"""
    assert round(d["paid"] + d["actual_discount"] + d["unpaid"], 2) == d["list_total"]
    assert round(d["paid"] + d["daily_discount"] + d["daily_unpaid"], 2) == d["discount_total"]


def test_split_without_discount_price(db):
    """没填日常单价时，日常价口径退化成原价口径，两个优惠相等。"""
    item = Item(name="门锁", qty_total=2, price=50)
    db.add(item)
    db.flush()
    item.records.append(PurchaseRecord(qty=1, amount=40))
    db.commit()
    db.refresh(item)

    d = compute.item_dict(item)
    assert d["unpaid"] == 50 and d["daily_unpaid"] == 50
    assert d["actual_discount"] == 10 and d["daily_discount"] == 10
    _assert_split(d)


def test_split_holds_with_price_override(db):
    """房间覆盖价让「原价小计 − 未付」不等于「已买数量 × 原价」，等式仍须成立。"""
    room = _mk_room(db, "客厅")
    item = Item(name="吊灯", price=100, discount_price=80)
    db.add(item)
    db.flush()
    db.add(Allocation(item_id=item.id, room_id=room.id, qty=2, price_override=150))
    item.records.append(PurchaseRecord(qty=1, amount=120))
    db.commit()
    db.refresh(item)

    d = compute.item_dict(item)
    assert d["list_total"] == 300      # 2 × 覆盖价 150
    assert d["discount_total"] == 160  # 2 × 日常单价 80，不看覆盖价
    assert d["unpaid"] == 100          # 未付 1 × 原价
    assert d["daily_unpaid"] == 80     # 未付 1 × 日常单价
    assert d["actual_discount"] == 80  # 300 − 120 − 100
    assert d["daily_discount"] == -40  # 160 − 120 − 80
    _assert_split(d)


def test_daily_discount_negative_when_paid_above_daily_price(db):
    """实付价高于日常价：日常价优惠为负，不夹到 0。"""
    item = Item(name="溢价件", qty_total=1, price=100, discount_price=90)
    db.add(item)
    db.flush()
    item.records.append(PurchaseRecord(qty=1, amount=100))
    db.commit()
    db.refresh(item)

    d = compute.item_dict(item)
    assert d["actual_discount"] == 0    # 正好按原价付
    assert d["daily_discount"] == -10   # 比日常价多花 10
    _assert_split(d)


def test_split_holds_when_overbought(db):
    """买超：实付数量大于总量，未付夹到 0，两个优惠都转负。"""
    item = Item(name="买多了", qty_total=1, price=100, discount_price=80)
    db.add(item)
    db.flush()
    item.records.append(PurchaseRecord(qty=3, amount=300))
    db.commit()
    db.refresh(item)

    d = compute.item_dict(item)
    assert d["unpaid"] == 0 and d["daily_unpaid"] == 0
    assert d["actual_discount"] == -200  # 100 − 300
    assert d["daily_discount"] == -220   # 80 − 300
    _assert_split(d)

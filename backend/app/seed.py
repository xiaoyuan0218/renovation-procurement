"""首次启动时建表并写入默认房间/类目（可在「设置 / 数据」里增改）。"""

from sqlalchemy import text

from .db import Base, SessionLocal, engine
from .models import Category, Item, PurchaseRecord, Room
from .services.compute import item_status

DEFAULT_ROOMS = ["玄关/阳台", "过道", "客厅", "休闲区", "电竞房", "次卧",
                 "主卧", "主卫", "次卫湿区", "次卫干区", "厨房", "餐厅"]
DEFAULT_CATEGORIES = ["照明", "开关插座", "网络", "家装"]


def init_db():
    Base.metadata.create_all(engine)
    # 轻量迁移：旧库补列
    with engine.begin() as conn:
        cols = [row[1] for row in conn.execute(text("PRAGMA table_info(items)"))]
        num_cols = ("bought_qty", "paid_qty", "paid_price", "paid_amount")
        text_cols = ("brand", "model")
        for col in num_cols:
            if col not in cols:
                conn.execute(text(f"ALTER TABLE items ADD COLUMN {col} FLOAT DEFAULT 0"))
        for col in text_cols:
            if col not in cols:
                conn.execute(text(f"ALTER TABLE items ADD COLUMN {col} VARCHAR(100) DEFAULT ''"))
        acols = [row[1] for row in conn.execute(text("PRAGMA table_info(allocations)"))]
        if "paid_qty" not in acols:
            conn.execute(text("ALTER TABLE allocations ADD COLUMN paid_qty FLOAT DEFAULT 0"))
        # 清理悬空行：物料被删后遗留的布点/采购记录（旧版本批量删除曾留下）
        conn.execute(text("DELETE FROM allocations WHERE item_id NOT IN (SELECT id FROM items)"))
        conn.execute(text("DELETE FROM allocations WHERE room_id NOT IN (SELECT id FROM rooms)"))
        conn.execute(text("DELETE FROM purchase_records WHERE item_id NOT IN (SELECT id FROM items)"))
    db = SessionLocal()
    try:
        if db.query(Room).count() == 0:
            for i, name in enumerate(DEFAULT_ROOMS):
                db.add(Room(name=name, sort=i))
        if db.query(Category).count() == 0:
            for i, name in enumerate(DEFAULT_CATEGORIES):
                db.add(Category(name=name, sort=i))
        # 旧数据折算：把旧的实付金额/数量字段转成一笔采购记录
        for item in db.query(Item).all():
            if item.records:
                continue
            amount = item.paid_amount or 0
            qty = (item.paid_qty or 0) or (item.bought_qty or 0)
            if not qty and amount and item.allocations:
                qty = sum(a.qty or 0 for a in item.allocations)
            elif not qty and amount:
                qty = item.qty_total or 0
            if amount or qty:
                item.records.append(PurchaseRecord(qty=qty, amount=amount))
            item.bought = item_status(item) == "done"
        db.commit()
    finally:
        db.close()

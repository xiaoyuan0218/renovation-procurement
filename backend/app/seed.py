"""首次启动时建表、迁移老库、写入默认清单/分组/分类（都可在「设置」里增改）。"""

from sqlalchemy import text

from . import migrations
from .db import Base, SessionLocal, engine
from .models import Category, Item, ItemList, PurchaseRecord, Room
from .services.compute import item_status

DEFAULT_LIST_NAME = migrations.DEFAULT_LIST_NAME
DEFAULT_ROOMS = ["玄关/阳台", "过道", "客厅", "休闲区", "电竞房", "次卧",
                 "主卧", "主卫", "次卫湿区", "次卫干区", "厨房", "餐厅"]
DEFAULT_CATEGORIES = ["照明", "开关插座", "网络", "家装"]


def init_db():
    # 备份要早于 create_all：备份里必须是升级前原封不动的样子，回滚直接换回去
    backup = migrations.backup_if_needed()
    Base.metadata.create_all(engine)
    _add_missing_columns()
    # 老库升级成多清单：单事务、做完自检（详见 migrations 模块）
    migrations.migrate_to_multi_list(backup_path=backup)
    db = SessionLocal()
    try:
        _seed_defaults(db)
    finally:
        db.close()


def _add_missing_columns():
    """轻量迁移：旧库补列。SQLite 加列只能一步到位地补，改约束要走 migrations。"""
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
        if "rev" not in cols:
            conn.execute(text("ALTER TABLE items ADD COLUMN rev INTEGER DEFAULT 1"))
        if "deleted_at" not in cols:
            conn.execute(text("ALTER TABLE items ADD COLUMN deleted_at DATETIME"))
        acols = [row[1] for row in conn.execute(text("PRAGMA table_info(allocations)"))]
        if "paid_qty" not in acols:
            conn.execute(text("ALTER TABLE allocations ADD COLUMN paid_qty FLOAT DEFAULT 0"))
        rcols = [row[1] for row in conn.execute(text("PRAGMA table_info(purchase_records)"))]
        for col in ("vendor", "order_no"):
            if col not in rcols:
                conn.execute(text(
                    f"ALTER TABLE purchase_records ADD COLUMN {col} VARCHAR(50) DEFAULT ''"))
        if "room_id" not in rcols:
            conn.execute(text("ALTER TABLE purchase_records ADD COLUMN room_id INTEGER "
                              "REFERENCES rooms(id) ON DELETE SET NULL"))
        ecols = [row[1] for row in conn.execute(text("PRAGMA table_info(extra_expenses)"))]
        if ecols and "item_id" not in ecols:
            conn.execute(text("ALTER TABLE extra_expenses ADD COLUMN item_id INTEGER "
                              "REFERENCES items(id) ON DELETE SET NULL"))
        # 单选的 room_id 搬进多选表 record_rooms（幂等：只搬还没搬过的）
        if conn.execute(text("SELECT name FROM sqlite_master WHERE type='table' "
                             "AND name='record_rooms'")).fetchone():
            conn.execute(text("""
                INSERT INTO record_rooms (record_id, room_id)
                SELECT r.id, r.room_id FROM purchase_records r
                WHERE r.room_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM record_rooms rr
                                  WHERE rr.record_id = r.id)
            """))
        # 清理悬空行：物料被删后遗留的布点/采购记录（旧版本批量删除曾留下）
        conn.execute(text("DELETE FROM allocations WHERE item_id NOT IN (SELECT id FROM items)"))
        conn.execute(text("DELETE FROM allocations WHERE room_id NOT IN (SELECT id FROM rooms)"))
        conn.execute(text("DELETE FROM purchase_records WHERE item_id NOT IN (SELECT id FROM items)"))


def _seed_defaults(db):
    if db.query(ItemList).count() == 0:
        # 全新库：建一份默认清单，默认分组/分类挂在它名下。
        # （老库走的是迁移，lists 里已经有默认清单了，不会进这个分支）
        lst = ItemList(name=DEFAULT_LIST_NAME, sort=0)
        db.add(lst)
        db.flush()
        for i, name in enumerate(DEFAULT_ROOMS):
            db.add(Room(name=name, sort=i, list_id=lst.id))
        for i, name in enumerate(DEFAULT_CATEGORIES):
            db.add(Category(name=name, sort=i, list_id=lst.id))
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

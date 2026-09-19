"""首次启动时建表、迁移老库、写入默认清单/分组/分类（都可在「设置」里增改）。"""

from datetime import datetime

from sqlalchemy import text

from . import migrations
from .services import codes
from .db import Base, SessionLocal, engine
from .models import Category, Item, ItemList, PurchaseRecord, Room, utcnow
from .services.compute import item_status

DEFAULT_LIST_NAME = migrations.DEFAULT_LIST_NAME
# 默认只给一个示例，不预填一整套行业词汇 —— 这工具是通用的（装修、年货、
# 项目物料都能用），一上来就摆十二个房间名会让人以为它只能干装修这一件事。
# 示例照着用户自己的习惯改掉就行，也可以在「设置」里随时增删。
DEFAULT_ROOMS = ["示例分组"]
DEFAULT_CATEGORIES = ["示例分类"]


def init_db():
    # 备份要早于 create_all：备份里必须是升级前原封不动的样子，回滚直接换回去
    backup = migrations.backup_if_needed()
    Base.metadata.create_all(engine)
    _add_missing_columns()
    # 老库升级成多清单：单事务、做完自检（详见 migrations 模块）
    migrations.migrate_to_multi_list(backup_path=backup)
    _ensure_code_unique_index_safe()
    db = SessionLocal()
    try:
        _seed_defaults(db)
    finally:
        db.close()


def _ensure_code_unique_index_safe() -> None:
    """补建编号唯一索引（幂等）。

    放在迁移**之后**：老库里可能本来就存着重复编号，迁移的 `_dedupe_list_codes`
    会先扫掉它们，这里再建才不会失败。全新库没有迁移过程，就靠这一步补上 ——
    从前这个索引只在迁移里建，新装的实例反而没有，"编号是清单身份"就只剩
    应用层查重，并发下能插进两个同号清单。
    """
    try:
        with engine.begin() as conn:
            _ensure_code_unique_index(conn)
    except Exception as exc:  # 重复编号等历史脏数据：不拦住启动，但要留下痕迹
        print(f"[警告] 编号唯一索引未能建立（编号重复？）：{exc}")


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
        lcols = [row[1] for row in conn.execute(text("PRAGMA table_info(lists)"))]
        if "code" not in lcols:
            conn.execute(text("ALTER TABLE lists ADD COLUMN code VARCHAR(12)"))
        _backfill_list_codes(conn)
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

        # 时间戳（创建/修改）：老表补列并用迁移时刻回填 —— 历史数据没有真实时间，
        # 回填值只表示"从这一刻起开始记录"，界面与同步判冲突都以它为准。
        for table in ("lists", "items", "rooms", "categories",
                      "purchase_records", "extra_expenses"):
            cols = [row[1] for row in conn.execute(text(f"PRAGMA table_info({table})"))]
            if not cols:
                continue
            for col in ("created_at", "updated_at"):
                if col not in cols:
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {col} DATETIME"))
            conn.execute(
                text(f"UPDATE {table} SET created_at = COALESCE(created_at, :now), "
                     f"updated_at = COALESCE(updated_at, :now)"),
                {"now": utcnow()},
            )


def _backfill_list_codes(conn) -> None:
    """给还没有编号的清单各发一个（老库升级、或中途失败留下的空值）。"""
    taken = {row[0] for row in conn.execute(text("SELECT code FROM lists WHERE code IS NOT NULL"))}
    rows = [row[0] for row in conn.execute(
        text("SELECT id FROM lists WHERE code IS NULL OR code = ''"))]
    for list_id in rows:
        code = codes.new_code()
        while code in taken:
            code = codes.new_code()
        taken.add(code)
        conn.execute(text("UPDATE lists SET code = :code WHERE id = :id"),
                     {"code": code, "id": list_id})


def _ensure_code_unique_index(conn) -> None:
    """保证 `uq_lists_code` 存在（编号是清单身份，唯一性得由库兜住）。

    从前这个索引只在"老库迁移"那条路上建，**全新安装的实例反而没有** ——
    编号唯一就只剩应用层"查一次再插入"，并发下能插进两个同号清单，
    手机端就会认错清单。这里在每次启动时无条件补一次（幂等）。

    必须在 `_backfill_list_codes` 之后调用：先让空编号各归其位，
    否则多个空值会一起撞上"空值不参与"之外的部分唯一索引。
    """
    conn.execute(text(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_lists_code "
        "ON lists (code) WHERE code IS NOT NULL AND code != ''"))


def _seed_defaults(db):
    if db.query(ItemList).count() == 0:
        # 全新库：建一份默认清单，默认分组/分类挂在它名下。
        # （老库走的是迁移，lists 里已经有默认清单了，不会进这个分支）
        lst = ItemList(name=DEFAULT_LIST_NAME, sort=0, code=codes.new_code())
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

from datetime import datetime, timezone

from sqlalchemy import (Boolean, Column, DateTime, Float, ForeignKey, Integer,
                        String, UniqueConstraint)
from sqlalchemy.orm import relationship

from .db import Base


def utcnow():
    """当前 UTC 时间（不带时区的 naive datetime，全端统一的时间尺子）。

    时间戳是同步判"谁改得更近"的依据，必须同一把尺：手机存 UTC、服务器存
    UTC。从前各自用设备/宿主的本地时区，两端时区设置不同时（真实部署里
    NAS 容器常是 UTC、手机是本地时区），同一时刻写出的时间戳能差出好几个
    小时，较新的改动反而会被当成旧的覆盖掉。显示时由界面各自转本地时区。
    """
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _first_list_id(context):
    """插入时没指定清单，就落到第一份清单。

    给"不认识 list_id 的老脚本/老测试"兜底：直接建条目时不带清单的话，
    数据会挂成不属于任何清单 —— 界面上既看不到也删不掉，等于凭空消失。
    正常业务代码都显式传 list_id，这里只是不给这种数据留活路。
    """
    row = context.connection.exec_driver_sql(
        "SELECT id FROM lists ORDER BY sort, id LIMIT 1").fetchone()
    return row[0] if row else None


class User(Base):
    """管理员账号。整个应用只用一个账号，首次打开网页时创建。"""

    __tablename__ = "users"

    id = Column(Integer, primary_key=True)
    username = Column(String(50), unique=True, nullable=False)
    password_hash = Column(String(200), nullable=False)
    created_at = Column(DateTime, default=utcnow)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)


class ItemList(Base):
    """一份清单。原来整个库只有装修采购这一份，现在可以建多份、
    彼此的口分组/分类/条目完全隔离（例如「装修采购」和「年货清单」）。

    表名 lists、字段名 list_id 都是外部契约的一部分（老版安卓客户端、导入
    导出的文件格式都按这套叫法），改名词要连着客户端一起改，不要动。

    **清单名允许重复，编号（code）才是身份**：名字是给人看的、随时会改，
    手机与服务器之间"是不是同一份"一律按编号认（见 services/codes.py）。
    同名两份清单可以共存，各自独立。旧库的 name 唯一约束由 migrations 重建表去掉。
    """

    __tablename__ = "lists"

    id = Column(Integer, primary_key=True)
    name = Column(String(50), nullable=False)
    note = Column(String(200), default="")
    sort = Column(Integer, default=0)
    created_at = Column(DateTime, default=utcnow)
    # 清单的唯一编号（见 services/codes.py）：改名、同名都不影响它，两端靠它对认。
    # 可空是为升级路径服务（SQLite 加列不能 NOT NULL 且无默认值），老库由 seed
    # 的轻量迁移回填，应用层建清单时必定赋值。唯一性由迁移里的部分唯一索引
    # uq_lists_code 保证（空值不参与，老库回填前的空值可以共存）。
    code = Column(String(12), index=True, nullable=True)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)

    # 删清单就把它名下的东西一起带走；ORM 级联不依赖数据库的外键开关
    items = relationship("Item", back_populates="item_list",
                         cascade="all, delete-orphan")
    rooms = relationship("Room", back_populates="item_list",
                         cascade="all, delete-orphan")
    categories = relationship("Category", back_populates="item_list",
                              cascade="all, delete-orphan")
    expenses = relationship("ExtraExpense", back_populates="item_list",
                            cascade="all, delete-orphan")


class ExtraExpense(Base):
    """额外费用：运费、安装费、辅料这类不进物料单价的支出。

    刻意不绑定具体物料 —— 装修里的运费通常按订单、按趟算，硬摊到某一两件
    物料上只会让单价失真（还会把"实际优惠"算成负数）。所以它独立记账、
    单独汇总，**不参与**两个口径的三段拆分：预算仍是买货的钱。
    """

    __tablename__ = "extra_expenses"

    id = Column(Integer, primary_key=True)
    list_id = Column(Integer, ForeignKey("lists.id", ondelete="CASCADE"),
                     nullable=True, index=True, default=_first_list_id)
    kind = Column(String(20), default="运费")      # 运费 / 安装费 / 其他
    amount = Column(Float, default=0)              # 金额
    date = Column(String(20), default="")          # 日期（YYYY-MM-DD，选填）
    vendor = Column(String(50), default="")        # 商家（选填）
    order_no = Column(String(50), default="")      # 订单号（选填）
    note = Column(String(200), default="")         # 备注
    # 这笔费用是为哪条物料的货付的（选填，用来对账）。删物料时置空而不是级联删 ——
    # 钱记录不能因为整理物料就消失。
    item_id = Column(Integer, ForeignKey("items.id", ondelete="SET NULL"), nullable=True)
    created_at = Column(DateTime, default=utcnow)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)

    item_list = relationship("ItemList", back_populates="expenses")
    item = relationship("Item")


class Category(Base):
    __tablename__ = "categories"
    # 同名分类只在各自清单里唯一：不同清单可以各有一份「照明」
    __table_args__ = (UniqueConstraint("list_id", "name", name="uq_category_list_name"),)

    id = Column(Integer, primary_key=True)
    # 可空是为升级路径服务：老库的列只能 ALTER 加上（SQLite 加列不允许 NOT NULL
    # 且没有默认值），迁移里统一回填、并断言不留空。应用层建条目时必定赋值。
    list_id = Column(Integer, ForeignKey("lists.id", ondelete="CASCADE"),
                     nullable=True, index=True, default=_first_list_id)
    name = Column(String(50), nullable=False)
    sort = Column(Integer, default=0)
    created_at = Column(DateTime, default=utcnow)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)

    item_list = relationship("ItemList", back_populates="categories")
    items = relationship("Item", back_populates="category")


class Room(Base):
    __tablename__ = "rooms"

    id = Column(Integer, primary_key=True)
    list_id = Column(Integer, ForeignKey("lists.id", ondelete="CASCADE"),
                     nullable=True, index=True, default=_first_list_id)
    name = Column(String(50), nullable=False)
    sort = Column(Integer, default=0)
    created_at = Column(DateTime, default=utcnow)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)

    item_list = relationship("ItemList", back_populates="rooms")
    allocations = relationship("Allocation", back_populates="room",
                               cascade="all, delete-orphan")


class Item(Base):
    __tablename__ = "items"

    id = Column(Integer, primary_key=True)
    list_id = Column(Integer, ForeignKey("lists.id", ondelete="CASCADE"),
                     nullable=True, index=True, default=_first_list_id)
    name = Column(String(100), nullable=False)
    category_id = Column(Integer, ForeignKey("categories.id"), nullable=True)
    unit = Column(String(20), default="个")
    brand = Column(String(50), default="")         # 品牌
    model = Column(String(100), default="")        # 型号
    qty_total = Column(Float, default=0)          # 无布点时的采购总量
    price = Column(Float, default=0)              # 单价（原价）
    discount_price = Column(Float, nullable=True)  # 优惠单价，空=无优惠按原价
    paid_amount = Column(Float, nullable=True)     # 旧字段：已由采购记录取代
    paid_qty = Column(Float, default=0)            # 旧字段：已由采购记录取代
    bought_qty = Column(Float, default=0)          # 旧字段：已由采购记录取代
    bought = Column(Boolean, default=False)        # 兼容字段：是否全部买完（读取时以推导状态为准）
    note = Column(String(500), default="")
    sort = Column(Integer, default=0)
    # 修订号：每次改动（含改这条物料的分配、采购记录）都 +1。
    # 客户端读取时带走、保存时带回，用来发现"我编辑期间别处也改了这条"，
    # 避免整条覆盖把别人的改动悄悄抹掉。
    rev = Column(Integer, default=1)
    # 移进回收站的时间；空 = 正常。删除改成软删是为了"删错了还能捞回来"——
    # 连带清掉的采购记录是历史，删一条物料就永久丢掉那些记录太狠了。
    deleted_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=utcnow)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)

    @classmethod
    def alive(cls):
        """没被移进回收站的条目。所有"正常界面"的查询都要带上这个条件。"""
        return cls.deleted_at.is_(None)

    item_list = relationship("ItemList", back_populates="items")
    category = relationship("Category", back_populates="items")
    records = relationship("PurchaseRecord", back_populates="item",
                           cascade="all, delete-orphan", order_by="PurchaseRecord.id")
    allocations = relationship("Allocation", back_populates="item",
                               cascade="all, delete-orphan",
                               order_by="Allocation.id")

    def touch(self):
        """标记这条物料被改过（含改它的分配或采购记录）。"""
        self.rev = (self.rev or 0) + 1


class PurchaseRecord(Base):
    """采购记录：一个物料可有多笔，每笔记 实付数量 + 实付金额。"""
    __tablename__ = "purchase_records"

    id = Column(Integer, primary_key=True)
    item_id = Column(Integer, ForeignKey("items.id", ondelete="CASCADE"), nullable=False)
    qty = Column(Float, default=0)                 # 这笔实付数量
    amount = Column(Float, default=0)              # 这笔实付金额
    date = Column(String(20), default="")          # 付款日期（选填）
    note = Column(String(200), default="")         # 备注
    vendor = Column(String(50), default="")        # 商家（选填，保价/退换/对账用）
    order_no = Column(String(50), default="")      # 订单号（选填）
    # 这笔钱是给哪个分组花的（选填）。填了它，"这间买齐了没"就是算出来的而不是猜的；
    # 删分组时置空而不是级联删 —— 付款记录是钱，不能因为整理分组就消失。
    room_id = Column(Integer, ForeignKey("rooms.id", ondelete="SET NULL"), nullable=True)
    created_at = Column(DateTime, default=utcnow)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)

    item = relationship("Item", back_populates="records")
    # 这笔钱涉及的分组（可多选）。一次采购常常同时买几间的东西，
    # 也可能只买了某一间的一部分 —— 勾了谁就只往谁身上算。
    rooms = relationship("RecordRoom", back_populates="record",
                         cascade="all, delete-orphan", order_by="RecordRoom.id",
                         lazy="selectin")


class RecordRoom(Base):
    """一笔采购记录涉及的分组（多对多）。

    覆盖计算时把这笔数量按分配顺序依次抵扣这里勾选的分组，扣满一间再下一间 ——
    比"全局按顺序猜"准确得多，因为"这笔买了哪几间"是用户明确说的。
    """

    __tablename__ = "record_rooms"

    id = Column(Integer, primary_key=True)
    record_id = Column(Integer, ForeignKey("purchase_records.id", ondelete="CASCADE"),
                       nullable=False, index=True)
    # 删分组时置空（记录本身留着），不是级联删 —— 那是钱
    room_id = Column(Integer, ForeignKey("rooms.id", ondelete="SET NULL"),
                     nullable=True, index=True)

    record = relationship("PurchaseRecord", back_populates="rooms")


class Allocation(Base):
    __tablename__ = "allocations"

    id = Column(Integer, primary_key=True)
    item_id = Column(Integer, ForeignKey("items.id", ondelete="CASCADE"), nullable=False)
    room_id = Column(Integer, ForeignKey("rooms.id", ondelete="CASCADE"), nullable=False)
    qty = Column(Float, default=0)
    price_override = Column(Float, nullable=True)  # 该房间的单独单价（不填则用物料单价）
    paid_qty = Column(Float, default=0)            # 旧字段：已由采购记录取代（展示用覆盖算法计算）
    note = Column(String(200), default="")

    item = relationship("Item", back_populates="allocations")
    room = relationship("Room", back_populates="allocations")

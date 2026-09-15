from sqlalchemy import Boolean, Column, Float, ForeignKey, Integer, String
from sqlalchemy.orm import relationship

from .db import Base


class Category(Base):
    __tablename__ = "categories"

    id = Column(Integer, primary_key=True)
    name = Column(String(50), unique=True, nullable=False)
    sort = Column(Integer, default=0)

    items = relationship("Item", back_populates="category")


class Room(Base):
    __tablename__ = "rooms"

    id = Column(Integer, primary_key=True)
    name = Column(String(50), nullable=False)
    sort = Column(Integer, default=0)

    allocations = relationship("Allocation", back_populates="room",
                               cascade="all, delete-orphan")


class Item(Base):
    __tablename__ = "items"

    id = Column(Integer, primary_key=True)
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

    category = relationship("Category", back_populates="items")
    records = relationship("PurchaseRecord", back_populates="item",
                           cascade="all, delete-orphan", order_by="PurchaseRecord.id")
    allocations = relationship("Allocation", back_populates="item",
                               cascade="all, delete-orphan",
                               order_by="Allocation.id")


class PurchaseRecord(Base):
    """采购记录：一个物料可有多笔，每笔记 实付数量 + 实付金额。"""
    __tablename__ = "purchase_records"

    id = Column(Integer, primary_key=True)
    item_id = Column(Integer, ForeignKey("items.id", ondelete="CASCADE"), nullable=False)
    qty = Column(Float, default=0)                 # 这笔实付数量
    amount = Column(Float, default=0)              # 这笔实付金额
    date = Column(String(20), default="")          # 付款日期（选填）
    note = Column(String(200), default="")         # 备注（如订单号）

    item = relationship("Item", back_populates="records")


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

from typing import Optional

from pydantic import BaseModel, ConfigDict, Field


class CategoryIn(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    sort: int = 0


class CategoryOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    name: str
    sort: int


class RoomIn(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    sort: int = 0


class RoomOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    name: str
    sort: int


class AllocIn(BaseModel):
    room_id: int
    qty: float = 0
    price_override: Optional[float] = None
    note: str = ""


class AllocOut(BaseModel):
    id: int
    item_id: int
    room_id: int
    qty: float
    price_override: Optional[float] = None
    note: str = ""


class RecordIn(BaseModel):
    qty: float = 0
    amount: float = 0
    date: str = ""
    note: str = ""


class RecordPatchIn(BaseModel):
    qty: Optional[float] = None
    amount: Optional[float] = None
    date: Optional[str] = None
    note: Optional[str] = None


class RecordOut(BaseModel):
    id: int
    item_id: int
    qty: float
    amount: float
    unit_price: Optional[float] = None
    date: str = ""
    note: str = ""


class ItemIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    category_id: Optional[int] = None
    brand: str = ""
    model: str = ""
    unit: str = "个"
    qty_total: float = 0
    price: float = 0
    discount_price: Optional[float] = None
    note: str = ""
    # 传入则整体替换布点明细 / 采购记录；不传(None)表示不动
    allocations: Optional[list[AllocIn]] = None
    records: Optional[list[RecordIn]] = None


class ItemPatchIn(BaseModel):
    """部分更新（矩阵页/清单页行内快捷改动用）。"""
    name: Optional[str] = None
    category_id: Optional[int] = None
    brand: Optional[str] = None
    model: Optional[str] = None
    unit: Optional[str] = None
    qty_total: Optional[float] = None
    price: Optional[float] = None
    discount_price: Optional[float] = None
    note: Optional[str] = None


class MatrixCellIn(BaseModel):
    item_id: int
    room_id: int
    qty: float = 0
    price_override: Optional[float] = None
    note: str = ""


class BatchDeleteIn(BaseModel):
    ids: list[int]  # 要删除的物料 ID 列表


class ItemOut(BaseModel):
    id: int
    name: str
    category_id: Optional[int] = None
    category_name: Optional[str] = None
    brand: str = ""
    model: str = ""
    unit: str
    qty_total: float
    price: float
    discount_price: Optional[float] = None
    bought: bool
    note: str
    total_qty: float
    list_total: float
    discount_total: float
    paid_qty: float = 0
    paid_price: Optional[float] = None
    paid: float = 0
    unpaid_qty: float = 0
    unpaid: float = 0
    status: str = "unbought"
    records: list[RecordOut] = []
    allocations: list[AllocOut] = []


class CategoryStat(BaseModel):
    id: Optional[int] = None
    name: str
    list_total: float
    discount_total: float
    paid_total: float


class RoomStat(BaseModel):
    id: int
    name: str
    qty: float
    list_total: float


class SummaryOut(BaseModel):
    totals: dict
    by_category: list[CategoryStat]
    by_room: list[RoomStat]
    unbought: list[dict]


class ImportReport(BaseModel):
    mode: str
    items_created: int = 0
    items_matched: int = 0
    allocations: int = 0
    records: int = 0
    rooms_created: int = 0
    categories_created: int = 0
    warnings: list[str] = []


# ---------------------------------------------------------------- 登录

class CredentialsIn(BaseModel):
    # 不含空白与竖线：token 的载荷用竖线分隔字段
    username: str = Field(min_length=1, max_length=50, pattern=r"^[^\s|]+$")
    password: str = Field(min_length=6, max_length=128)


class PasswordChangeIn(BaseModel):
    old_password: str = Field(min_length=1, max_length=128)
    new_password: str = Field(min_length=6, max_length=128)


class AuthStateOut(BaseModel):
    initialized: bool   # 是否已经创建过管理员账号
    authenticated: bool
    username: Optional[str] = None


class LoginOut(BaseModel):
    token: str
    username: str


class OkOut(BaseModel):
    ok: bool = True

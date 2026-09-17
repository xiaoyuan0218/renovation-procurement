from datetime import datetime
from typing import List, Optional

from pydantic import (BaseModel, ConfigDict, Field, field_serializer,
                      field_validator)

from .services import dates as date_utils


class Timestamped(BaseModel):
    """带创建/修改时间的输出模型（各实体 Out 都继承它）。

    统一格式化成 `YYYY-MM-DD HH:MM:SS`：与客户端 `nowStamp()` 同格式，
    等宽字符串的字典序就是时间序，同步判冲突可以直接比大小。
    """

    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

    @field_serializer("created_at", "updated_at")
    def _fmt_timestamp(self, value):
        return value.strftime("%Y-%m-%d %H:%M:%S") if value else ""


class ItemListIn(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    note: str = Field(default="", max_length=200)
    sort: int = 0
    # 手机带着自己的编号上传时用它，让两边编号对得上；不传就现发一个
    code: Optional[str] = None
    # 新建时可选：以某份现有清单为模板，只复制它的分组与分类（不带物料）。
    # 不传就是一张完全空白的清单。
    copy_from: Optional[int] = None


class ItemListOut(Timestamped):
    model_config = ConfigDict(from_attributes=True)
    id: int
    name: str
    note: str = ""
    sort: int = 0
    code: str = ""
    # 清单列表要显示"里面有多少东西"，一次查询带出来，前端不必再逐份去数
    item_count: int = 0
    room_count: int = 0
    category_count: int = 0

    @field_validator("code", mode="before")
    @classmethod
    def _blank_code(cls, value):
        # 老库升级途中的清单可能还没回填编号，接口上不能吐 null
        return value or ""


class CategoryIn(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    sort: int = 0


class CategoryOut(Timestamped):
    model_config = ConfigDict(from_attributes=True)
    id: int
    name: str
    sort: int


class RoomIn(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    sort: int = 0


class RoomOut(Timestamped):
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
    vendor: str = Field(default="", max_length=50)      # 商家（选填）
    order_no: str = Field(default="", max_length=50)    # 订单号（选填）
    # 这笔钱涉及的分组，可多选（一次采购同时买几间的东西是常事）
    room_ids: list[int] = []
    # 老客户端只会传单值；两者都给了以 room_ids 为准
    room_id: Optional[int] = None

    @field_validator("date")
    @classmethod
    def _normalize_date(cls, value: str) -> str:
        # 统一成 YYYY-MM-DD；写进来的脏格式（2026/9/14、带时间的）顺手归一化
        try:
            return date_utils.clean(value)
        except ValueError as e:
            raise ValueError(str(e)) from e


class RecordPatchIn(BaseModel):
    qty: Optional[float] = None
    amount: Optional[float] = None
    date: Optional[str] = None
    note: Optional[str] = None
    vendor: Optional[str] = Field(default=None, max_length=50)
    order_no: Optional[str] = Field(default=None, max_length=50)
    # 显式传 [] 表示"清空涉及的分组"，整个字段不传才是"不改"
    room_ids: Optional[list[int]] = None
    # 老客户端的单值写法；只有没给 room_ids 时才看它
    room_id: Optional[int] = None

    @field_validator("date")
    @classmethod
    def _normalize_date(cls, value: Optional[str]) -> Optional[str]:
        if value is None:  # None = 这次不改日期
            return None
        try:
            return date_utils.clean(value)
        except ValueError as e:
            raise ValueError(str(e)) from e


class RecordOut(Timestamped):
    id: int
    item_id: int
    qty: float
    amount: float
    unit_price: Optional[float] = None
    date: str = ""
    note: str = ""
    vendor: str = ""
    order_no: str = ""
    room_ids: list[int] = []
    room_id: Optional[int] = None   # = room_ids 的第一个，留给老客户端


class ExpenseIn(BaseModel):
    """额外费用（运费/安装费/辅料）。kind 不限死，允许自己写别的名目。"""
    kind: str = Field(default="运费", max_length=20)
    amount: float = 0
    date: str = ""
    vendor: str = Field(default="", max_length=50)
    order_no: str = Field(default="", max_length=50)
    note: str = Field(default="", max_length=200)
    item_id: Optional[int] = None   # 关联到哪条物料的货（选填）

    @field_validator("date")
    @classmethod
    def _normalize_date(cls, value: str) -> str:
        try:
            return date_utils.clean(value)
        except ValueError as e:
            raise ValueError(str(e)) from e


class ExpenseOut(Timestamped):
    model_config = ConfigDict(from_attributes=True)
    id: int
    kind: str
    amount: float
    date: str = ""
    vendor: str = ""
    order_no: str = ""
    note: str = ""
    item_id: Optional[int] = None
    item_name: str = ""   # 关联物料的名字，界面直接用

    @field_validator("date", mode="before")
    @classmethod
    def _read_date(cls, value):
        return date_utils.for_read(value)


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
    # 读取时拿到的 rev，保存时原样带回；对不上说明这条在别处被改过（409）
    base_rev: Optional[int] = None


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


class ItemOut(Timestamped):
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
    daily_unpaid: float = 0
    actual_discount: float = 0
    daily_discount: float = 0
    status: str = "unbought"
    rev: int = 1
    records: list[RecordOut] = []
    allocations: list[AllocOut] = []


class TrashItemOut(ItemOut):
    """回收站里的一条：物料的全部信息 + 什么时候被删的。"""
    deleted_at: str = ""


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

# ---------------------------------------------------------------- 清单级同步
# 手机单机版把整份清单搬来搬去时用的请求体，格式与 services/list_transfer.py 一致。

class SyncRoomIn(BaseModel):
    id: Optional[int] = None
    name: str = Field(min_length=1, max_length=50)
    sort: int = 0


class SyncCategoryIn(BaseModel):
    id: Optional[int] = None
    name: str = Field(min_length=1, max_length=50)
    sort: int = 0


class SyncAllocIn(BaseModel):
    id: Optional[int] = None
    room_id: Optional[int] = None
    qty: float = 0
    price_override: Optional[float] = None
    note: str = Field(default="", max_length=200)


class SyncRecordIn(BaseModel):
    id: Optional[int] = None
    qty: float = 0
    amount: float = 0
    date: str = ""
    note: str = Field(default="", max_length=200)
    vendor: str = Field(default="", max_length=50)
    order_no: str = Field(default="", max_length=50)
    room_ids: list[int] = []

    @field_validator("date")
    @classmethod
    def _normalize_date(cls, value: str) -> str:
        # 别的设备上可能存着 "2026-09-14 00:00:00" 这类脏值：读时归一化就好，
        # 一条日期格式不对不该让整份清单同步失败
        return date_utils.for_read(value)


class SyncItemIn(BaseModel):
    id: Optional[int] = None
    name: str = Field(min_length=1, max_length=100)
    category_id: Optional[int] = None
    unit: str = Field(default="个", max_length=20)
    brand: str = Field(default="", max_length=50)
    model: str = Field(default="", max_length=100)
    qty_total: float = 0
    price: float = 0
    discount_price: Optional[float] = None
    note: str = Field(default="", max_length=500)
    sort: int = 0
    deleted_at: Optional[str] = None
    allocations: list[SyncAllocIn] = []
    records: list[SyncRecordIn] = []


class SyncExpenseIn(BaseModel):
    id: Optional[int] = None
    kind: str = Field(default="运费", max_length=20)
    amount: float = 0
    date: str = ""
    vendor: str = Field(default="", max_length=50)
    order_no: str = Field(default="", max_length=50)
    note: str = Field(default="", max_length=200)
    item_id: Optional[int] = None

    @field_validator("date")
    @classmethod
    def _normalize_date(cls, value: str) -> str:
        return date_utils.for_read(value)


class SyncListMeta(BaseModel):
    name: str = Field(default="未命名清单", max_length=50)
    note: str = Field(default="", max_length=200)
    sort: int = 0
    # 清单编号：搬运时跟着走，两边保持一致
    code: str = ""


class SyncPayload(BaseModel):
    """一份清单的全量内容。

    注意这里用 `List[...]` 而不是 `list[...]`：字段名 `list` 会在类体内遮蔽
    内建类型，写成 `list[SyncRoomIn]` 会直接报 TypeError。
    """
    version: int = 1
    list: SyncListMeta = SyncListMeta()
    rooms: List[SyncRoomIn] = []
    categories: List[SyncCategoryIn] = []
    items: List[SyncItemIn] = []
    expenses: List[SyncExpenseIn] = []


class SyncPushIn(SyncPayload):
    """覆盖已有清单时多带两样：上次同步的指纹，以及"以我为准"的强制开关。"""
    base_fingerprint: Optional[str] = None
    force: bool = False

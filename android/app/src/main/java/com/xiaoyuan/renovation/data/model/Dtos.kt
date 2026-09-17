package com.xiaoyuan.renovation.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ---------------- 基础数据 ---------------- */

@Serializable
data class RoomDto(
    val id: Int,
    val name: String,
    val sort: Int = 0,
)

@Serializable
data class CategoryDto(
    val id: Int,
    val name: String,
    val sort: Int = 0,
)

/* ---------------- 清单 ---------------- */

/** 一份清单：有自己的物料、分组、分类，彼此隔离。 */
@Serializable
data class ItemListDto(
    val id: Int,
    val name: String,
    val note: String = "",
    val sort: Int = 0,
    /** 清单编号：改名字、上传时重名加后缀都不变，两端靠它对认 */
    val code: String = "",
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("room_count") val roomCount: Int = 0,
    @SerialName("category_count") val categoryCount: Int = 0,
)

@Serializable
data class ListInDto(
    val name: String,
    val note: String = "",
    val sort: Int = 0,
    /** 新建时可选：照抄这份清单的分组与分类（不带物料）；null 就是空白清单。 */
    @SerialName("copy_from") val copyFrom: Int? = null,
)

/* ---------------- 物料 ---------------- */

@Serializable
data class RecordDto(
    val id: Int = 0,
    @SerialName("item_id") val itemId: Int = 0,
    val qty: Double = 0.0,
    val amount: Double = 0.0,
    @SerialName("unit_price") val unitPrice: Double? = null,
    val date: String = "",
    val note: String = "",
    val vendor: String = "",
    @SerialName("order_no") val orderNo: String = "",
    /** 这笔钱涉及的分组（可多选）：勾了谁，"这间买齐了没"就只往谁身上算 */
    @SerialName("room_ids") val roomIds: List<Int> = emptyList(),
    /** 什么时候记的 / 最后改的（界面上显示，判冲突时也比它） */
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class AllocationDto(
    val id: Int = 0,
    @SerialName("item_id") val itemId: Int = 0,
    @SerialName("room_id") val roomId: Int = 0,
    val qty: Double = 0.0,
    @SerialName("price_override") val priceOverride: Double? = null,
    val note: String = "",
)

@Serializable
data class ItemDto(
    val id: Int,
    val name: String,
    @SerialName("category_id") val categoryId: Int? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val brand: String = "",
    val model: String = "",
    val unit: String = "个",
    @SerialName("qty_total") val qtyTotal: Double = 0.0,
    val price: Double = 0.0,
    @SerialName("discount_price") val discountPrice: Double? = null,
    val bought: Boolean = false,
    val note: String = "",
    @SerialName("total_qty") val totalQty: Double = 0.0,
    @SerialName("list_total") val listTotal: Double = 0.0,
    @SerialName("discount_total") val discountTotal: Double = 0.0,
    @SerialName("paid_qty") val paidQty: Double = 0.0,
    @SerialName("paid_price") val paidPrice: Double? = null,
    val paid: Double = 0.0,
    @SerialName("unpaid_qty") val unpaidQty: Double = 0.0,
    val unpaid: Double = 0.0,
    val status: String = STATUS_UNBOUGHT,
    val records: List<RecordDto> = emptyList(),
    val allocations: List<AllocationDto> = emptyList(),
)

/* ---------------- 请求体 ---------------- */

@Serializable
data class AllocInDto(
    @SerialName("room_id") val roomId: Int,
    val qty: Double = 0.0,
    @SerialName("price_override") val priceOverride: Double? = null,
    val note: String = "",
)

@Serializable
data class RecordInDto(
    val qty: Double = 0.0,
    val amount: Double = 0.0,
    val date: String = "",
    val note: String = "",
    val vendor: String = "",
    @SerialName("order_no") val orderNo: String = "",
    @SerialName("room_ids") val roomIds: List<Int> = emptyList(),
)

@Serializable
data class RecordPatchDto(
    val qty: Double? = null,
    val amount: Double? = null,
    val date: String? = null,
    val note: String? = null,
    val vendor: String? = null,
    @SerialName("order_no") val orderNo: String? = null,
    // 服务端把 null 当作"这个字段不改"，所以只传要改的即可
    @SerialName("room_ids") val roomIds: List<Int>? = null,
)

/**
 * 新增/更新物料。allocations / records 传数组表示整体替换，传 null 表示保持不动
 * （与后端 ItemIn 语义一致）。
 */
@Serializable
data class ItemInDto(
    val name: String,
    @SerialName("category_id") val categoryId: Int? = null,
    val brand: String = "",
    val model: String = "",
    val unit: String = "个",
    @SerialName("qty_total") val qtyTotal: Double = 0.0,
    val price: Double = 0.0,
    @SerialName("discount_price") val discountPrice: Double? = null,
    val note: String = "",
    val allocations: List<AllocInDto>? = null,
    val records: List<RecordInDto>? = null,
)

@Serializable
data class NameInDto(
    val name: String,
    val sort: Int = 0,
)

@Serializable
data class MatrixCellInDto(
    @SerialName("item_id") val itemId: Int,
    @SerialName("room_id") val roomId: Int,
    val qty: Double = 0.0,
    @SerialName("price_override") val priceOverride: Double? = null,
    val note: String = "",
)

@Serializable
data class BatchDeleteInDto(val ids: List<Int>)

/* ---------------- 简单响应 ---------------- */

@Serializable
data class OkDto(val ok: Boolean = true)

@Serializable
data class BatchDeleteResultDto(val deleted: Int = 0)

@Serializable
data class CellSaveResultDto(val ok: Boolean = true, val deleted: Boolean = false)

/* ---------------- 分配矩阵 ---------------- */

@Serializable
data class MatrixCellDto(
    val qty: Double = 0.0,
    @SerialName("price_override") val priceOverride: Double? = null,
    @SerialName("paid_qty") val paidQty: Double = 0.0,
    val note: String = "",
)

@Serializable
data class MatrixItemDto(
    val id: Int,
    val name: String,
    val model: String = "",
    @SerialName("category_name") val categoryName: String? = null,
    val unit: String = "个",
    val price: Double = 0.0,
    @SerialName("discount_price") val discountPrice: Double? = null,
    val bought: Boolean = false,
    val status: String = STATUS_UNBOUGHT,
    @SerialName("paid_qty") val paidQty: Double = 0.0,
    @SerialName("total_qty") val totalQty: Double = 0.0,
    @SerialName("total_qty_source") val totalQtySource: String = "item",
    @SerialName("list_total") val listTotal: Double = 0.0,
    val cells: Map<String, MatrixCellDto> = emptyMap(),
) {
    /** 该物料在指定分组的分配；cells 的 key 是 room_id 的字符串形式。 */
    fun cellOf(roomId: Int): MatrixCellDto? = cells[roomId.toString()]
}

@Serializable
data class MatrixDto(
    val rooms: List<RoomDto> = emptyList(),
    val items: List<MatrixItemDto> = emptyList(),
)

/* ---------------- 总览 ---------------- */

@Serializable
data class TotalsDto(
    @SerialName("list_total") val listTotal: Double = 0.0,
    @SerialName("discount_total") val discountTotal: Double = 0.0,
    @SerialName("paid_total") val paidTotal: Double = 0.0,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("unpaid_total") val unpaidTotal: Double = 0.0,
    // 两个口径各自的三段拆分（详见后端 compute.py 的文件头说明）：
    //   paid + actualDiscount + unpaid = listTotal
    //   paid + dailyDiscount + dailyUnpaid = discountTotal
    @SerialName("daily_unpaid_total") val dailyUnpaidTotal: Double = 0.0,
    @SerialName("actual_discount_total") val actualDiscountTotal: Double = 0.0,
    @SerialName("daily_discount_total") val dailyDiscountTotal: Double = 0.0,
    @SerialName("status_count") val statusCount: Map<String, Int> = emptyMap(),
    @SerialName("bought_count") val boughtCount: Int = 0,
    @SerialName("partial_count") val partialCount: Int = 0,
) {
    fun countOf(status: String): Int = statusCount[status] ?: 0

    /** 未买齐 = 未买 + 部分已买 */
    val pendingCount: Int get() = countOf(STATUS_UNBOUGHT) + countOf(STATUS_PARTIAL)

    /** 已付占预算（原价合计）比例，0..1 */
    val paidRatioOfList: Float
        get() = if (listTotal > 0) (paidTotal / listTotal).toFloat().coerceIn(0f, 1f) else 0f
}

@Serializable
data class CategoryStatDto(
    val id: Int? = null,
    val name: String,
    @SerialName("list_total") val listTotal: Double = 0.0,
    @SerialName("discount_total") val discountTotal: Double = 0.0,
    @SerialName("paid_total") val paidTotal: Double = 0.0,
)

@Serializable
data class RoomStatDto(
    val id: Int,
    val name: String,
    val qty: Double = 0.0,
    @SerialName("list_total") val listTotal: Double = 0.0,
)

@Serializable
data class SummaryDto(
    val totals: TotalsDto = TotalsDto(),
    @SerialName("by_category") val byCategory: List<CategoryStatDto> = emptyList(),
    @SerialName("by_room") val byRoom: List<RoomStatDto> = emptyList(),
    val unbought: List<ItemDto> = emptyList(),
    /** 近几个月的已付（货款口径，不含额外费用） */
    @SerialName("by_month") val byMonth: List<MonthPaidDto> = emptyList(),
    @SerialName("by_month_undated") val byMonthUndated: Double = 0.0,
    /** 额外费用（运费/安装费）：独立于上面两个口径，单独汇总 */
    @SerialName("expenses_total") val expensesTotal: Double = 0.0,
    @SerialName("expenses_by_kind") val expensesByKind: List<ExpenseKindDto> = emptyList(),
    @SerialName("expenses_count") val expensesCount: Int = 0,
)

/* ---------------- 导入报告 ---------------- */

@Serializable
data class ImportReportDto(
    val mode: String = "replace",
    @SerialName("items_created") val itemsCreated: Int = 0,
    @SerialName("items_matched") val itemsMatched: Int = 0,
    val allocations: Int = 0,
    val records: Int = 0,
    @SerialName("rooms_created") val roomsCreated: Int = 0,
    @SerialName("categories_created") val categoriesCreated: Int = 0,
    val warnings: List<String> = emptyList(),
)

/* ---------------- 登录 ---------------- */

/* ---------------- 额外费用 / 回收站 / 按月 ---------------- */

@Serializable
data class MonthPaidDto(val month: String = "", val paid: Double = 0.0)

@Serializable
data class ExpenseKindDto(val kind: String = "", val amount: Double = 0.0)

@Serializable
data class ExpenseDto(
    val id: Int,
    val kind: String = "运费",
    val amount: Double = 0.0,
    val date: String = "",
    val vendor: String = "",
    @SerialName("order_no") val orderNo: String = "",
    val note: String = "",
    /** 关联到哪条物料的货（选填，对账用） */
    @SerialName("item_id") val itemId: Int? = null,
    @SerialName("item_name") val itemName: String = "",
)

@Serializable
data class ExpenseInDto(
    val kind: String = "运费",
    val amount: Double = 0.0,
    val date: String = "",
    val vendor: String = "",
    @SerialName("order_no") val orderNo: String = "",
    val note: String = "",
    @SerialName("item_id") val itemId: Int? = null,
)

/** 回收站里的一条：只要列表够用的几个字段。 */
@Serializable
data class TrashItemDto(
    val id: Int,
    val name: String,
    @SerialName("deleted_at") val deletedAt: String = "",
    @SerialName("list_total") val listTotal: Double = 0.0,
    val paid: Double = 0.0,
)

@Serializable
data class PurgeResultDto(val deleted: Int = 0, val ok: Boolean = true)

@Serializable
data class AuthStateDto(
    /** 后端是否已经创建过管理员账号。false 表示这次要先「创建管理员」。 */
    val initialized: Boolean = false,
    val authenticated: Boolean = false,
    val username: String? = null,
)

@Serializable
data class CredentialsInDto(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResultDto(
    /** 安卓端把这个存起来，之后每个请求带 Authorization: Bearer。 */
    val token: String,
    val username: String,
)

@Serializable
data class HealthDto(val status: String = "")

/* ---------------- 状态常量 ---------------- */

const val STATUS_DONE = "done"
const val STATUS_PARTIAL = "partial"
const val STATUS_UNBOUGHT = "unbought"
const val STATUS_NONE = "none"

fun statusLabel(status: String): String = when (status) {
    STATUS_DONE -> "已买完"
    STATUS_PARTIAL -> "部分已买"
    STATUS_UNBOUGHT -> "未买"
    else -> "无需采购"
}

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
)

@Serializable
data class RecordPatchDto(
    val qty: Double? = null,
    val amount: Double? = null,
    val date: String? = null,
    val note: String? = null,
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

/* ---------------- 布点矩阵 ---------------- */

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
    /** 该物料在指定房间的布点；cells 的 key 是 room_id 的字符串形式。 */
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

package com.xiaoyuan.renovation.mobile.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 清单搬运用的传输模型，字段与后端 `services/list_transfer.py` 的 JSON 一一对应。
 *
 * 只带**语义字段**：派生值和历史列（rev、bought、paid_qty…）两边都不传也不比，
 * 否则"内容没变"会被误判成"变过"，来回搬还会累积偏差。
 */
@Serializable
data class SyncPayload(
    val version: Int = 1,
    val list: SyncListMeta = SyncListMeta(),
    val rooms: List<SyncRoom> = emptyList(),
    val categories: List<SyncCategory> = emptyList(),
    val items: List<SyncItem> = emptyList(),
    val expenses: List<SyncExpense> = emptyList(),
)

@Serializable
data class SyncListMeta(
    val name: String = "未命名清单",
    val note: String = "",
    val sort: Int = 0,
    /** 清单编号：搬运时跟着走，两边保持一致 */
    val code: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SyncRoom(
    val id: Int? = null,
    val name: String,
    val sort: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SyncCategory(
    val id: Int? = null,
    val name: String,
    val sort: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SyncAllocation(
    val id: Int? = null,
    @SerialName("room_id") val roomId: Int? = null,
    val qty: Double = 0.0,
    @SerialName("price_override") val priceOverride: Double? = null,
    val note: String = "",
)

@Serializable
data class SyncRecord(
    val id: Int? = null,
    val qty: Double = 0.0,
    val amount: Double = 0.0,
    val date: String = "",
    val note: String = "",
    val vendor: String = "",
    @SerialName("order_no") val orderNo: String = "",
    /** 这笔钱涉及的分组（可多选） */
    @SerialName("room_ids") val roomIds: List<Int> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SyncItem(
    val id: Int? = null,
    val name: String,
    @SerialName("category_id") val categoryId: Int? = null,
    val unit: String = "个",
    val brand: String = "",
    val model: String = "",
    @SerialName("qty_total") val qtyTotal: Double = 0.0,
    val price: Double = 0.0,
    @SerialName("discount_price") val discountPrice: Double? = null,
    val note: String = "",
    val sort: Int = 0,
    /** 非空表示这条在回收站里 —— 回收站也要跟着搬，两端才一致 */
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val allocations: List<SyncAllocation> = emptyList(),
    val records: List<SyncRecord> = emptyList(),
)

@Serializable
data class SyncExpense(
    val id: Int? = null,
    val kind: String = "运费",
    val amount: Double = 0.0,
    val date: String = "",
    val vendor: String = "",
    @SerialName("order_no") val orderNo: String = "",
    val note: String = "",
    @SerialName("item_id") val itemId: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

/** 服务器对导出/推送的应答：当前指纹 + 当下的完整数据。 */
@Serializable
data class SyncSnapshot(
    val fingerprint: String,
    val payload: SyncPayload,
)

/** 新建清单的应答：多一个服务器上的清单 id。 */
@Serializable
data class SyncCreateResult(
    @SerialName("list_id") val listId: Int,
    val fingerprint: String,
    val payload: SyncPayload,
)

/** 推送（覆盖服务器上的某份清单）的请求体。 */
@Serializable
data class SyncPush(
    @SerialName("base_fingerprint") val baseFingerprint: String? = null,
    val force: Boolean = false,
    val version: Int = 1,
    val list: SyncListMeta,
    val rooms: List<SyncRoom> = emptyList(),
    val categories: List<SyncCategory> = emptyList(),
    val items: List<SyncItem> = emptyList(),
    val expenses: List<SyncExpense> = emptyList(),
) {
    companion object {
        fun of(payload: SyncPayload, baseFingerprint: String?, force: Boolean) = SyncPush(
            baseFingerprint = baseFingerprint,
            force = force,
            version = payload.version,
            list = payload.list,
            rooms = payload.rooms,
            categories = payload.categories,
            items = payload.items,
            expenses = payload.expenses,
        )
    }
}

/**
 * 上次同步时的基线，存成 JSON 放在绑定信息里（不另建表）。
 *
 * [localMap] 是**服务器 id → 本地 id**（键形如 `item:32` / `room:1`），
 * 合并时把两端的数据都翻译到本地 id 空间再比对 —— 本地是自增主键、
 * 与服务器的 id 没有必然对应关系，靠这张映射才能认出"这是同一行"。
 */
@Serializable
data class SyncBaseline(
    /** 上次同步完成时服务器那边的内容指纹 */
    val fingerprint: String = "",
    /** 上次同步完成时服务器的完整数据（服务器 id 空间） */
    val payload: SyncPayload = SyncPayload(),
    /** 服务器 id → 本地 id */
    val localMap: Map<String, Int> = emptyMap(),
) {
    fun localOf(kind: String, remoteId: Int?): Int? =
        remoteId?.let { localMap["$kind:$it"] }
}

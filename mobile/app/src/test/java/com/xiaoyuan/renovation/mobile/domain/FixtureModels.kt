package com.xiaoyuan.renovation.mobile.domain

import com.xiaoyuan.renovation.mobile.data.db.AllocationEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.PurchaseRecordEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 口径对照数据的结构与加载。
 *
 * 数据由 `backend/scripts/gen_compute_fixture.py` 用**后端的真实计算函数**生成，
 * 手机端只负责拿同样的输入跑一遍自己的实现再比对。改了任一侧的口径，
 * 这些测试就会红 —— 先确认哪边是对的，再同步改另一边。
 */
internal val fixtureJson = Json { ignoreUnknownKeys = true }

internal fun loadFixture(): Fixture {
    val stream = Fixture::class.java.getResourceAsStream("/compute_fixture.json")
        ?: error("找不到 compute_fixture.json（先跑 backend/scripts/gen_compute_fixture.py）")
    return fixtureJson.decodeFromString(Fixture.serializer(), stream.readBytes().decodeToString())
}

@Serializable
internal data class Fixture(
    val rooms: List<String>,
    val category: String = "",
    val cases: List<Case> = emptyList(),
    val summary: SummaryFixture,
)

@Serializable
internal data class Case(
    val name: String,
    val item: ItemFixture,
    val allocations: List<AllocFixture> = emptyList(),
    val records: List<RecordFixture> = emptyList(),
    val expected: Expected,
    @SerialName("expected_cover") val expectedCover: Map<String, Double> = emptyMap(),
) {
    /**
     * 房间索引 +1 当作 room id、分配/记录的下标 +1 当作 id，与生成脚本的约定一致。
     * [id] 由调用方给：汇总测试要用「下标 +1」当物料 id 才能对上 unbought_ids。
     */
    fun toBundle(id: Int = 1): ItemBundle {
        val allocEntities = allocations.mapIndexed { idx, a ->
            AllocationEntity(
                id = idx + 1,
                itemId = id,
                roomId = a.room + 1,
                qty = a.qty,
                priceOverride = a.priceOverride,
            )
        }
        val recordEntities = records.mapIndexed { idx, r ->
            PurchaseRecordEntity(
                id = idx + 1,
                itemId = id,
                qty = r.qty,
                amount = r.amount,
                date = r.date,
                vendor = r.vendor,
            )
        }
        val recordRooms = records.mapIndexed { idx, r ->
            (idx + 1) to r.rooms.map { it + 1 }
        }.toMap()
        return ItemBundle(
            item = ItemEntity(
                id = id,
                listId = 1,
                name = name,
                categoryId = if (item.hasCategory) 1 else null,
                unit = item.unit,
                brand = item.brand,
                model = item.model,
                qtyTotal = item.qtyTotal,
                price = item.price,
                discountPrice = item.discountPrice,
                note = item.note,
            ),
            categoryName = if (item.hasCategory) "照明" else null,
            allocations = allocEntities,
            records = recordEntities,
            recordRooms = recordRooms,
        )
    }
}

@Serializable
internal data class ItemFixture(
    @SerialName("qty_total") val qtyTotal: Double = 0.0,
    val price: Double = 0.0,
    @SerialName("discount_price") val discountPrice: Double? = null,
    val unit: String = "个",
    val brand: String = "",
    val model: String = "",
    val note: String = "",
    @SerialName("has_category") val hasCategory: Boolean = false,
)

@Serializable
internal data class AllocFixture(
    val room: Int,
    val qty: Double = 0.0,
    @SerialName("price_override") val priceOverride: Double? = null,
)

@Serializable
internal data class RecordFixture(
    val qty: Double = 0.0,
    val amount: Double = 0.0,
    val date: String = "",
    val vendor: String = "",
    val rooms: List<Int> = emptyList(),
)

@Serializable
internal data class Expected(
    @SerialName("total_qty") val totalQty: Double = 0.0,
    @SerialName("list_total") val listTotal: Double = 0.0,
    @SerialName("discount_total") val discountTotal: Double = 0.0,
    @SerialName("paid_qty") val paidQty: Double = 0.0,
    @SerialName("paid_price") val paidPrice: Double? = null,
    val paid: Double = 0.0,
    @SerialName("unpaid_qty") val unpaidQty: Double = 0.0,
    val unpaid: Double = 0.0,
    @SerialName("daily_unpaid") val dailyUnpaid: Double = 0.0,
    @SerialName("actual_discount") val actualDiscount: Double = 0.0,
    @SerialName("daily_discount") val dailyDiscount: Double = 0.0,
    val status: String = "",
)

/* ---------------- 汇总部分 ---------------- */

@Serializable
internal data class SummaryFixture(
    val totals: ExpectedTotals,
    @SerialName("by_category") val byCategory: List<ExpectedCategory> = emptyList(),
    @SerialName("by_room") val byRoom: List<ExpectedRoom> = emptyList(),
    @SerialName("unbought_ids") val unboughtIds: List<Int> = emptyList(),
    @SerialName("by_month") val byMonth: List<ExpectedMonth> = emptyList(),
    @SerialName("by_month_undated") val byMonthUndated: Double = 0.0,
    @SerialName("expenses_total") val expensesTotal: Double = 0.0,
    @SerialName("expenses_by_kind") val expensesByKind: List<ExpectedKind> = emptyList(),
    @SerialName("expenses_count") val expensesCount: Int = 0,
)

@Serializable
internal data class ExpectedTotals(
    @SerialName("list_total") val listTotal: Double = 0.0,
    @SerialName("discount_total") val discountTotal: Double = 0.0,
    @SerialName("paid_total") val paidTotal: Double = 0.0,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("unpaid_total") val unpaidTotal: Double = 0.0,
    @SerialName("daily_unpaid_total") val dailyUnpaidTotal: Double = 0.0,
    @SerialName("actual_discount_total") val actualDiscountTotal: Double = 0.0,
    @SerialName("daily_discount_total") val dailyDiscountTotal: Double = 0.0,
    @SerialName("status_count") val statusCount: Map<String, Int> = emptyMap(),
    @SerialName("bought_count") val boughtCount: Int = 0,
    @SerialName("partial_count") val partialCount: Int = 0,
)

@Serializable
internal data class ExpectedCategory(
    val id: Int? = null,
    val name: String,
    @SerialName("list_total") val listTotal: Double = 0.0,
    @SerialName("discount_total") val discountTotal: Double = 0.0,
    @SerialName("paid_total") val paidTotal: Double = 0.0,
)

@Serializable
internal data class ExpectedRoom(
    val id: Int,
    val name: String,
    val qty: Double = 0.0,
    @SerialName("list_total") val listTotal: Double = 0.0,
)

@Serializable
internal data class ExpectedMonth(val month: String, val paid: Double = 0.0)

@Serializable
internal data class ExpectedKind(val kind: String, val amount: Double = 0.0)

package com.xiaoyuan.renovation.mobile.domain

import com.xiaoyuan.renovation.mobile.data.db.CategoryEntity
import com.xiaoyuan.renovation.mobile.data.db.ExtraExpenseEntity
import com.xiaoyuan.renovation.mobile.data.db.RoomEntity
import com.xiaoyuan.renovation.mobile.data.model.CategoryStatDto
import com.xiaoyuan.renovation.mobile.data.model.ExpenseKindDto
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.model.MonthPaidDto
import com.xiaoyuan.renovation.mobile.data.model.RoomStatDto
import com.xiaoyuan.renovation.mobile.data.model.STATUS_DONE
import com.xiaoyuan.renovation.mobile.data.model.STATUS_PARTIAL
import com.xiaoyuan.renovation.mobile.data.model.STATUS_UNBOUGHT
import com.xiaoyuan.renovation.mobile.data.model.SummaryDto
import com.xiaoyuan.renovation.mobile.data.model.TotalsDto

/**
 * 本地版的汇总口径，逐条对齐后端 `app/routers/summary.py`。
 *
 * 聚合之后两个三段拆分等式依然严格成立（先按物料算好再求和，不做二次近似）：
 * - 已付 + 实际优惠 + 未付 = 原价合计
 * - 已付 + 日常价优惠 + 日常价未付 = 日常价合计
 *
 * 额外费用（运费/安装费）**不参与**上面两个口径，单独汇总 —— 等式说的始终是货款。
 */
object SummaryCompute {

    /** 一条物料在汇总里用到的全部数字（一次算好，后面反复用）。 */
    private class Row(bundle: ItemBundle) {
        val bundle = bundle
        val id: Int = bundle.item.id
        val categoryId: Int? = bundle.item.categoryId
        val listTotal = LocalCompute.listTotal(bundle.item, bundle.allocations)
        val discountTotal = LocalCompute.discountTotal(bundle.item, bundle.allocations)
        val paid = LocalCompute.paid(bundle.records)
        private val qty = LocalCompute.totalQty(bundle.item, bundle.allocations)
        private val paidQty = LocalCompute.paidQty(bundle.records)
        private val unpaidQty = LocalCompute.unpaidQty(qty, paidQty)
        val unpaid = LocalCompute.unpaid(unpaidQty, bundle.item)
        val dailyUnpaid = LocalCompute.dailyUnpaid(unpaidQty, bundle.item)
        val actualDiscount = r2(listTotal - paid - unpaid)
        val dailyDiscount = r2(discountTotal - paid - dailyUnpaid)
        val status = LocalCompute.status(qty, paidQty)
    }

    fun summary(
        bundles: List<ItemBundle>,
        categories: List<CategoryEntity>,
        rooms: List<RoomEntity>,
        expenses: List<ExtraExpenseEntity>,
    ): SummaryDto {
        val rows = bundles.map { Row(it) }
        val views: List<ItemDto> = bundles.map { LocalCompute.toDto(it) }

        /* ---------- 合计 ---------- */
        val statusCount = linkedMapOf(
            STATUS_DONE to 0,
            STATUS_PARTIAL to 0,
            STATUS_UNBOUGHT to 0,
            "none" to 0,
        )
        rows.forEach { statusCount[it.status] = (statusCount[it.status] ?: 0) + 1 }

        val totals = TotalsDto(
            listTotal = r2(rows.sumOf { it.listTotal }),
            discountTotal = r2(rows.sumOf { it.discountTotal }),
            paidTotal = r2(rows.sumOf { it.paid }),
            itemCount = rows.size,
            unpaidTotal = r2(rows.sumOf { it.unpaid }),
            dailyUnpaidTotal = r2(rows.sumOf { it.dailyUnpaid }),
            actualDiscountTotal = r2(rows.sumOf { it.actualDiscount }),
            dailyDiscountTotal = r2(rows.sumOf { it.dailyDiscount }),
            statusCount = statusCount,
            boughtCount = statusCount[STATUS_DONE] ?: 0,
            partialCount = statusCount[STATUS_PARTIAL] ?: 0,
        )

        /* ---------- 分类 ---------- */
        val byCategory = categories.map { c ->
            val sub = rows.filter { it.categoryId == c.id }
            CategoryStatDto(
                id = c.id,
                name = c.name,
                listTotal = r2(sub.sumOf { it.listTotal }),
                discountTotal = r2(sub.sumOf { it.discountTotal }),
                paidTotal = r2(sub.sumOf { it.paid }),
            )
        }.toMutableList()
        val uncategorized = rows.filter { it.categoryId == null }
        if (uncategorized.isNotEmpty()) {
            byCategory += CategoryStatDto(
                id = null,
                name = "未分类",
                listTotal = r2(uncategorized.sumOf { it.listTotal }),
                discountTotal = r2(uncategorized.sumOf { it.discountTotal }),
                paidTotal = r2(uncategorized.sumOf { it.paid }),
            )
        }

        /* ---------- 分组（按各物料的分配累加） ---------- */
        val byRoom = rooms.map { room ->
            var qty = 0.0
            var total = 0.0
            bundles.forEach { b ->
                b.allocations.filter { it.roomId == room.id }.forEach { a ->
                    qty += a.qty
                    total += a.qty * (a.priceOverride ?: b.item.price)
                }
            }
            RoomStatDto(id = room.id, name = room.name, qty = r2(qty), listTotal = r2(total))
        }

        /* ---------- 未买齐（按日常价合计降序，先处理金额大的） ---------- */
        val pendingIds = rows
            .filter { it.status == STATUS_UNBOUGHT || it.status == STATUS_PARTIAL }
            .sortedByDescending { it.discountTotal }
            .map { it.id }
            .toSet()
        val unbought = views.filter { it.id in pendingIds }
            .sortedByDescending { it.discountTotal }

        /* ---------- 按月已付（日期从读出口径归一化，脏值归入"没记日期"） ---------- */
        val months = sortedMapOf<String, Double>()
        var undated = 0.0
        bundles.forEach { b ->
            b.records.forEach { r ->
                val month = LocalCompute.monthOf(r.date)
                if (month != null) months[month] = (months[month] ?: 0.0) + r.amount
                else undated += r.amount
            }
        }
        val byMonth = months.map { (month, paid) -> MonthPaidDto(month, r2(paid)) }

        /* ---------- 额外费用：独立于两个口径 ---------- */
        val kindSum = linkedMapOf<String, Double>()
        expenses.forEach { e ->
            val kind = e.kind.trim().ifEmpty { "其他" }
            kindSum[kind] = (kindSum[kind] ?: 0.0) + e.amount
        }
        val byKind = kindSum.entries
            .map { ExpenseKindDto(it.key, r2(it.value)) }
            .sortedByDescending { it.amount }

        return SummaryDto(
            totals = totals,
            byCategory = byCategory,
            byRoom = byRoom,
            unbought = unbought,
            byMonth = byMonth,
            byMonthUndated = r2(undated),
            expensesTotal = r2(kindSum.values.sum()),
            expensesByKind = byKind,
            expensesCount = expenses.size,
        )
    }
}

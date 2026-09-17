package com.xiaoyuan.renovation.domain

import com.xiaoyuan.renovation.data.model.AllocInDto
import com.xiaoyuan.renovation.data.model.RecordInDto
import com.xiaoyuan.renovation.data.model.STATUS_DONE
import com.xiaoyuan.renovation.data.model.STATUS_NONE
import com.xiaoyuan.renovation.data.model.STATUS_PARTIAL
import com.xiaoyuan.renovation.data.model.STATUS_UNBOUGHT
import kotlin.math.max
import kotlin.math.round

/**
 * 与后端 `app/services/compute.py` 完全同口径的本地计算。
 *
 * 只用于编辑表单的实时预览 —— 保存后的权威结果始终以后端返回为准。
 * 口径：
 * - 总数量：有分配 = Σ分配数量，否则用物料自身总量
 * - 原价小计：Σ(分配数量 × (分组覆盖单价 ?? 物料单价))，无分配 = 总量 × 单价
 * - 日常价小计：总数量 × (日常单价 ?? 单价)
 * - 实付数量/金额 = Σ采购记录；未付 = (总量 − 实付量) × 单价
 * - 状态：总量≤0 → none；实付≥总量 → done；实付>0 → partial；否则 unbought
 */
object Compute {

    private fun r2(x: Double): Double = round(x * 100) / 100

    fun totalQty(allocations: List<AllocInDto>, qtyTotal: Double): Double =
        if (allocations.isEmpty()) r2(qtyTotal) else r2(allocations.sumOf { it.qty })

    fun listTotal(allocations: List<AllocInDto>, price: Double, qtyTotal: Double): Double =
        if (allocations.isEmpty()) {
            r2(qtyTotal * price)
        } else {
            r2(allocations.sumOf { (it.qty) * (it.priceOverride ?: price) })
        }

    fun discountTotal(totalQty: Double, price: Double, discountPrice: Double?): Double =
        r2(totalQty * (discountPrice ?: price))

    fun paidQty(records: List<RecordInDto>): Double = r2(records.sumOf { it.qty })

    fun paidAmount(records: List<RecordInDto>): Double = r2(records.sumOf { it.amount })

    fun unpaidQty(totalQty: Double, paidQty: Double): Double = r2(max(0.0, totalQty - paidQty))

    fun unpaidAmount(totalQty: Double, paidQty: Double, price: Double): Double =
        r2(unpaidQty(totalQty, paidQty) * price)

    fun paidUnitPrice(paidQty: Double, paidAmount: Double): Double? =
        if (paidQty > 0 && paidAmount > 0) r2(paidAmount / paidQty) else null

    fun status(totalQty: Double, paidQty: Double): String = when {
        totalQty <= 0 -> STATUS_NONE
        paidQty >= totalQty - 1e-9 -> STATUS_DONE
        paidQty > 0 -> STATUS_PARTIAL
        else -> STATUS_UNBOUGHT
    }
}

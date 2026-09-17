package com.xiaoyuan.renovation.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 口径一致性：同样的输入，手机算出来的必须和后端一模一样。
 *
 * 对照数据由 `backend/scripts/gen_compute_fixture.py` 用**后端的真实计算函数**
 * 生成（含 9 个覆盖各分支的场景），这里只负责拿同样的输入跑本地实现再逐字段比对。
 * 改了任一侧的口径，这个测试就会红 —— 先确认哪边是对的，再同步改另一边。
 */
class LocalComputeTest {

    private val fixture: Fixture = loadFixture()

    @Test
    fun `每个场景的金额与状态都与后端一致`() {
        assertTrue("对照场景不该为空", fixture.cases.isNotEmpty())
        fixture.cases.forEach { case ->
            val bundle = case.toBundle()
            val dto = LocalCompute.toDto(bundle)
            val expected = case.expected
            val where = case.name

            assertClose(where, "total_qty", expected.totalQty, dto.totalQty)
            assertClose(where, "list_total", expected.listTotal, dto.listTotal)
            assertClose(where, "discount_total", expected.discountTotal, dto.discountTotal)
            assertClose(where, "paid_qty", expected.paidQty, dto.paidQty)
            assertClose(where, "paid", expected.paid, dto.paid)
            assertClose(where, "unpaid_qty", expected.unpaidQty, dto.unpaidQty)
            assertClose(where, "unpaid", expected.unpaid, dto.unpaid)
            assertEquals("$where status", expected.status, dto.status)
            assertClose(where, "paid_price", expected.paidPrice, dto.paidPrice)

            // 两个口径各自的三段拆分必须严格闭合
            assertClose(
                where, "原价三段闭合",
                dto.listTotal, dto.paid + expected.actualDiscount + dto.unpaid,
            )
            assertClose(
                where, "日常价三段闭合",
                dto.discountTotal,
                dto.paid + expected.dailyDiscount + expected.dailyUnpaid,
            )
        }
    }

    @Test
    fun `各分组的已付覆盖与后端一致`() {
        fixture.cases.forEach { case ->
            val bundle = case.toBundle()
            val cover = LocalCompute.paidCover(bundle)
            val expected = case.expectedCover

            assertEquals("${case.name} 覆盖的分组数", expected.size, cover.size)
            expected.forEach { (allocId, qty) ->
                assertClose(case.name, "分配 $allocId 的覆盖", qty, cover[allocId.toInt()] ?: 0.0)
            }
        }
    }

    private fun assertClose(where: String, what: String, expected: Double, actual: Double) {
        assertTrue(
            "$where 的 $what：期望 $expected，实际 $actual",
            kotlin.math.abs(expected - actual) < 1e-9,
        )
    }

    private fun assertClose(where: String, what: String, expected: Double?, actual: Double?) {
        if (expected == null || actual == null) {
            assertEquals("$where 的 $what", expected, actual)
            return
        }
        assertClose(where, what, expected, actual)
    }

}

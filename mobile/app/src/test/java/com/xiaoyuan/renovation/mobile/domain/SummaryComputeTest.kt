package com.xiaoyuan.renovation.mobile.domain

import com.xiaoyuan.renovation.mobile.data.db.CategoryEntity
import com.xiaoyuan.renovation.mobile.data.db.ExtraExpenseEntity
import com.xiaoyuan.renovation.mobile.data.db.RoomEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 汇总口径一致性：手机算出来的总览数字必须和电脑端一模一样。
 *
 * 对照数据由 `backend/scripts/gen_compute_fixture.py` 生成 —— 它直接调后端的
 * `get_summary()`，拿到的就是服务端会发给客户端的那份结构。这里用同样的输入
 * 跑 [SummaryCompute]，逐字段比对，包括顺序敏感的"未买齐清单"和"按月"。
 */
class SummaryComputeTest {

    private val fixture: Fixture = loadFixture()

    /** 物料 id 按 cases 顺序排（下标 +1），与生成脚本里 unbought_ids 的约定一致。 */
    private fun bundles(): List<ItemBundle> =
        fixture.cases.mapIndexed { idx, case -> case.toBundle(id = idx + 1) }

    private fun categories(): List<CategoryEntity> =
        listOf(CategoryEntity(id = 1, listId = 1, name = fixture.category, sort = 0))

    private fun rooms(): List<RoomEntity> =
        fixture.rooms.mapIndexed { idx, name ->
            RoomEntity(id = idx + 1, listId = 1, name = name, sort = idx)
        }

    /** 与生成脚本里那四笔费用一致（含一笔空类型、一笔没日期）。 */
    private fun expenses(): List<ExtraExpenseEntity> = listOf(
        ExtraExpenseEntity(id = 1, listId = 1, kind = "运费", amount = 80.0, date = "2026-09-01", vendor = "德邦"),
        ExtraExpenseEntity(id = 2, listId = 1, kind = "安装费", amount = 120.0, date = "2026-09-10"),
        ExtraExpenseEntity(id = 3, listId = 1, kind = "运费", amount = 35.0),
        ExtraExpenseEntity(id = 4, listId = 1, kind = "", amount = 20.0),
    )

    private fun summary() = SummaryCompute.summary(bundles(), categories(), rooms(), expenses())

    @Test
    fun `合计与状态统计与后端一致`() {
        val expected = fixture.summary.totals
        val totals = summary().totals

        assertClose("原价合计", expected.listTotal, totals.listTotal)
        assertClose("日常价合计", expected.discountTotal, totals.discountTotal)
        assertClose("已付合计", expected.paidTotal, totals.paidTotal)
        assertClose("未付合计", expected.unpaidTotal, totals.unpaidTotal)
        assertClose("日常价未付", expected.dailyUnpaidTotal, totals.dailyUnpaidTotal)
        assertClose("实际优惠", expected.actualDiscountTotal, totals.actualDiscountTotal)
        assertClose("日常价优惠", expected.dailyDiscountTotal, totals.dailyDiscountTotal)
        assertEquals("物料数", expected.itemCount, totals.itemCount)
        assertEquals("状态分布", expected.statusCount, totals.statusCount)
        assertEquals("已买完数", expected.boughtCount, totals.boughtCount)
        assertEquals("部分已买数", expected.partialCount, totals.partialCount)
    }

    @Test
    fun `聚合之后两个三段拆分依然闭合`() {
        val totals = summary().totals

        assertClose(
            "原价：已付 + 实际优惠 + 未付",
            totals.listTotal,
            totals.paidTotal + totals.actualDiscountTotal + totals.unpaidTotal,
        )
        assertClose(
            "日常价：已付 + 日常价优惠 + 日常价未付",
            totals.discountTotal,
            totals.paidTotal + totals.dailyDiscountTotal + totals.dailyUnpaidTotal,
        )
    }

    @Test
    fun `分类与分组汇总与后端一致`() {
        val actual = summary()
        val expected = fixture.summary

        assertEquals("分类条数", expected.byCategory.size, actual.byCategory.size)
        expected.byCategory.forEachIndexed { idx, want ->
            val got = actual.byCategory[idx]
            assertEquals("第 $idx 个分类的 id", want.id, got.id)
            assertEquals("第 $idx 个分类的名字", want.name, got.name)
            assertClose("$want.name 原价", want.listTotal, got.listTotal)
            assertClose("$want.name 日常价", want.discountTotal, got.discountTotal)
            assertClose("$want.name 已付", want.paidTotal, got.paidTotal)
        }
        // 有一件物料没分类 —— "未分类"这一条必须出现
        assertTrue("应含未分类", actual.byCategory.any { it.id == null && it.name == "未分类" })

        assertEquals("分组条数", expected.byRoom.size, actual.byRoom.size)
        expected.byRoom.forEachIndexed { idx, want ->
            val got = actual.byRoom[idx]
            assertEquals("第 $idx 个分组名", want.name, got.name)
            assertClose("${want.name} 数量", want.qty, got.qty)
            assertClose("${want.name} 原价", want.listTotal, got.listTotal)
        }
    }

    @Test
    fun `未买齐清单的成员与顺序与后端一致`() {
        val actual = summary().unbought.map { it.id }
        assertEquals("未买齐的物料与顺序", fixture.summary.unboughtIds, actual)
    }

    @Test
    fun `按月已付与未记日期金额与后端一致`() {
        val actual = summary()
        val expected = fixture.summary

        assertEquals("月份条数", expected.byMonth.size, actual.byMonth.size)
        expected.byMonth.forEachIndexed { idx, want ->
            val got = actual.byMonth[idx]
            assertEquals("第 $idx 个月的月份", want.month, got.month)
            assertClose("${want.month} 已付", want.paid, got.paid)
        }
        assertClose("没记日期的金额", expected.byMonthUndated, actual.byMonthUndated)
    }

    @Test
    fun `额外费用单独汇总且不进入两个口径`() {
        val actual = summary()
        val expected = fixture.summary

        assertClose("费用合计", expected.expensesTotal, actual.expensesTotal)
        assertEquals("费用笔数", expected.expensesCount, actual.expensesCount)
        assertEquals("费用类型条数", expected.expensesByKind.size, actual.expensesByKind.size)
        expected.expensesByKind.forEachIndexed { idx, want ->
            val got = actual.expensesByKind[idx]
            assertEquals("第 $idx 个类型的名字（按金额降序）", want.kind, got.kind)
            assertClose("${want.kind} 金额", want.amount, got.amount)
        }

        // 费用不进三段拆分：把上面的期望值搬进来验证一次，防止将来有人"顺手"加进去
        val totals = actual.totals
        assertClose(
            "原价三段不含量费",
            expected.totals.listTotal,
            totals.paidTotal + totals.actualDiscountTotal + totals.unpaidTotal,
        )
    }

    private fun assertClose(what: String, expected: Double, actual: Double) {
        assertTrue("$what：期望 $expected，实际 $actual", abs(expected - actual) < 1e-9)
    }
}

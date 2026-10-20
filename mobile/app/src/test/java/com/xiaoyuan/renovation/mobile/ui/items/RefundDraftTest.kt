package com.xiaoyuan.renovation.mobile.ui.items

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.model.RecordDto
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import com.xiaoyuan.renovation.mobile.data.repo.CurrentListHolder
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手机端退款：编辑页生成负数记录、已付按净额算、退定金把未付里的抵扣一起退回。
 *
 * 最容易坏的一处是「读回」：以前把 amount <= 0 一律当成空，退款记录一打开
 * 编辑页就变 0，再保存退款就没了。这里直接盯住这条往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RefundDraftTest {

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var vm: ItemEditViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo = LocalRepository(db, CurrentListHolder(AppPrefs(context), db, scope))
        vm = ItemEditViewModel(repo)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun record(
        key: Long = 1,
        qty: String = "1",
        amount: String = "6999",
        deposit: Boolean = false,
    ) = DraftRecord(
        key = key, qty = qty, amount = amount, isDeposit = deposit,
        date = "2026-10-01", note = "首付", vendor = "京东", orderNo = "A1",
        roomIds = listOf(3),
    )

    @Test
    fun `读回负数记录不能丢——那就是退款`() {
        val row = draftRecordOf(
            RecordDto(
                id = 9, itemId = 1, qty = 0.0, amount = -999.0,
                date = "2026-10-09", note = "价保退款", vendor = "京东",
                orderNo = "A1", roomIds = listOf(3),
            ),
            key = 5,
        )

        assertEquals("-999", row.amount)
        assertTrue(row.isRefundRow())
    }

    @Test
    fun `部分退款生成负数记录，已付按净额算`() {
        vm.edit { it.copy(qtyTotal = "1", price = "6999", records = listOf(record())) }

        vm.addRefundRow(1, "999", "价保退款", "2026-10-20")

        val records = vm.form.value.records
        assertEquals(2, records.size)
        val refund = records.last()
        assertEquals("-999", refund.amount)
        assertEquals("", refund.qty)
        assertEquals("价保退款", refund.note)
        assertEquals("京东", refund.vendor)
        assertEquals("A1", refund.orderNo)
        assertEquals(listOf(3), refund.roomIds)

        val preview = vm.preview(vm.form.value)
        assertEquals(6000.0, preview.paid, 1e-9)
        assertEquals(1.0, preview.paidQty, 1e-9)
        assertEquals("done", preview.status)
    }

    @Test
    fun `退款不能超过这笔的实付金额`() {
        vm.edit { it.copy(records = listOf(record())) }

        vm.addRefundRow(1, "7000", "价保退款", "2026-10-20")

        assertEquals(1, vm.form.value.records.size)
        assertNotNull(vm.message.value)
    }

    @Test
    fun `退定金沿用定金标记，未付里的抵扣一起退回`() {
        vm.edit {
            it.copy(
                qtyTotal = "1", price = "6999",
                records = listOf(record(qty = "", amount = "1000", deposit = true)),
            )
        }
        val before = vm.preview(vm.form.value)
        assertEquals(1000.0, before.paid, 1e-9)
        assertEquals(5999.0, before.unpaid, 1e-9)

        vm.addRefundRow(1, "1000", "退定", "2026-10-20")

        val after = vm.preview(vm.form.value)
        assertEquals(0.0, after.paid, 1e-9)
        assertEquals(6999.0, after.unpaid, 1e-9)
        assertTrue(vm.form.value.records.last().isDeposit)
    }
}

package com.xiaoyuan.renovation.mobile.data.xlsx

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemListEntity
import com.xiaoyuan.renovation.mobile.data.db.PurchaseRecordEntity
import com.xiaoyuan.renovation.mobile.domain.LocalCompute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 定金标记在「读回界面」与「表格导出导入」两条路上都不能丢。
 *
 * 这两条都是用户报过"勾了没用"的地方：读回丢字段时，编辑页重开永远显示未勾选
 * （再保存一次还把库里真值抹掉）；导出少一列表头时，整个采购记录页的列全错位、
 * 定金也读不回来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DepositRoundTripTest {

    private lateinit var db: AppDatabase
    private var listId = 0
    private var itemId = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listId = db.lists().insert(
            ItemListEntity(name = "定金清单", note = "", sort = 0, code = "DST001"),
        ).toInt()
        itemId = db.items().insert(
            ItemEntity(listId = listId, name = "壁挂炉", unit = "台", qtyTotal = 1.0,
                price = 6999.0),
        ).toInt()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `读回记录时定金跟着回来（不然编辑页重开就不勾了）`() {
        val entity = PurchaseRecordEntity(
            id = 7, itemId = itemId, qty = 0.0, amount = 1000.0, isDeposit = true,
            createdAt = "2026-10-08 05:00:00.000000",
            updatedAt = "2026-10-08 05:00:00.000000",
        )

        val dto = LocalCompute.toRecordDto(entity, emptyList())

        assertTrue("定金字要在 DTO 里带回来", dto.isDeposit)
    }

    @Test
    fun `采购记录页的表头与数据列数一致，定金在后端同一个位置`() = runBlocking {
        db.records().insert(
            PurchaseRecordEntity(itemId = itemId, qty = 0.0, amount = 1000.0,
                isDeposit = true, createdAt = "2026-10-08 05:00:00.000000",
                updatedAt = "2026-10-08 05:00:00.000000"),
        )

        val rows = SheetMapping.toSheets(db, listId).toMap()["采购记录"]!!
        val header = rows[0].map { it.toString() }
        val data = rows[1]

        assertEquals("表头与数据列数要一致（少了定金列整列会错位）", header.size, data.size)
        val idx = header.indexOf("定金")
        assertTrue("表头要有「定金」列：$header", idx >= 0)
        assertEquals("定金列写在实付金额之后（与后端一致）",
            header.indexOf("实付金额") + 1, idx)
        assertEquals("是", data[idx].toString())
    }

    @Test
    fun `手机导出再导回，定金不丢也不串位`() = runBlocking {
        db.records().insert(
            PurchaseRecordEntity(itemId = itemId, qty = 0.0, amount = 1000.0,
                isDeposit = true, date = "2026-10-01", note = "定金备注",
                vendor = "某商家", orderNo = "NO-1",
                createdAt = "2026-10-08 05:00:00.000000",
                updatedAt = "2026-10-08 05:00:00.000000"),
        )
        db.records().insert(
            PurchaseRecordEntity(itemId = itemId, qty = 1.0, amount = 5999.0,
                isDeposit = false, date = "2026-10-09", note = "尾款",
                createdAt = "2026-10-09 05:00:00.000000",
                updatedAt = "2026-10-09 05:00:00.000000"),
        )

        // 导出的行是 Any?（数字/字符串混排），导回时按表格里的写法（文本）读
        val sheets = SheetMapping.toSheets(db, listId)
            .associate { (name, rows) ->
                name to rows.map { row -> row.map { it?.toString() ?: "" } }
            }
        // 清空记录后整份导回（模拟"导出一份、再导进来"）
        db.records().deleteOfItem(itemId)
        SheetMapping.fromSheets(db, listId, sheets, mode = "merge")

        val records = db.records().ofItem(itemId).sortedBy { it.amount }
        assertEquals(2, records.size)
        assertTrue("定金那笔仍是定金", records[0].isDeposit)
        assertEquals("金额没串位", 1000.0, records[0].amount, 1e-9)
        assertEquals("商家没串位", "某商家", records[0].vendor)
        assertEquals("日期没串位", "2026-10-01", records[0].date)
        assertEquals("普通那笔不该被认成定金", false, records[1].isDeposit)
    }
}

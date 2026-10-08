package com.xiaoyuan.renovation.mobile.data.xlsx

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ItemListEntity
import com.xiaoyuan.renovation.mobile.data.db.STAMP
import com.xiaoyuan.renovation.mobile.data.db.normalizeStamp
import com.xiaoyuan.renovation.mobile.data.db.parseStamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * 导入导出带着「添加时间 / 修改时间」走。
 *
 * 为什么必须带上：手机导出、电脑再导回来（或者反过来）是搬家与备份的常规操作，
 * 时间戳要是丢了或被刷成"刚刚"，同步就会把所有行都当成刚改过，较旧的改动反而
 * 被当成新的，把另一台设备上的更新盖掉。所以这一组测试盯的是"照原样搬过去、
 * 搬回来"，以及"老文件没这两列时不要瞎写"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimestampRoundTripTest {

    private lateinit var db: AppDatabase
    private var listId = 0

    private val created = "2026-03-04 05:06:07.000000"
    private val updated = "2026-04-05 06:07:08.000000"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listId = db.lists().insert(
            ItemListEntity(name = "时间戳清单", note = "", sort = 0, code = "TST002"),
        ).toInt()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 带时间列的一张表：一条物料 + 一笔采购记录 + 一笔额外费用。 */
    private fun sheetsWithTime() = mapOf(
        "物料汇总" to listOf(
            listOf("物料ID", "类目", "物料名称", "单位", "数量", "单价", "添加时间", "修改时间"),
            listOf("1", "", "筒灯", "个", "6", "30.0", created, updated),
        ),
        "采购记录" to listOf(
            listOf("物料ID", "物料名称", "实付数量", "实付金额", "付款日期", "添加时间", "修改时间"),
            listOf("1", "筒灯", "6", "180.5", "2026-03-10", created, updated),
        ),
        "额外费用" to listOf(
            listOf("类型", "金额", "日期", "添加时间", "修改时间"),
            listOf("运费", "120", "2026-03-10", created, updated),
        ),
    )

    @Test
    fun `表里带的时间照原样落库`() = runBlocking {
        SheetMapping.fromSheets(db, listId, sheetsWithTime(), mode = "merge")

        val item = db.items().all(listId).single()
        assertEquals("物料的添加时间要按表里的写", created, item.createdAt)
        assertEquals("物料的修改时间要按表里的写", updated, item.updatedAt)

        val record = db.records().ofItem(item.id).single()
        assertEquals(created, record.createdAt)
        assertEquals(updated, record.updatedAt)

        val expense = db.expenses().byList(listId).single()
        assertEquals(created, expense.createdAt)
        assertEquals(updated, expense.updatedAt)
    }

    @Test
    fun `导出的表里带着时间，读回来还是同一时刻`() = runBlocking {
        SheetMapping.fromSheets(db, listId, sheetsWithTime(), mode = "merge")

        // 导出写的是**本地时间带偏移量**（用户在表格里看到的是自己的钟），
        // 但按偏移量换回来必须是同一时刻 —— 否则来回一趟时间就漂了
        val rows = SheetMapping.toSheets(db, listId)
            .toMap()["物料汇总"]!!
        val header = rows[0].map { it.toString() }
        val row = rows[1].map { it.toString() }
        val exportedCreated = row[header.indexOf("添加时间")]
        val exportedUpdated = row[header.indexOf("修改时间")]
        assertTrue("导出的时间要带时区偏移量：$exportedCreated",
            Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(exportedCreated))
        assertEquals(created, normalizeStamp(exportedCreated))
        assertEquals(updated, normalizeStamp(exportedUpdated))
    }

    @Test
    fun `带偏移量的时间按偏移量换回 UTC，不是当成本地时间`() = runBlocking {
        val table = mapOf(
            "物料汇总" to listOf(
                listOf("物料ID", "物料名称", "单位", "数量", "单价", "添加时间", "修改时间"),
                listOf("1", "筒灯", "个", "6", "30.0",
                    "2026-03-04 13:06:07+08:00", "2026-03-04 05:06:07+00:00"),
            ),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "merge")

        val item = db.items().all(listId).single()
        // +08:00 的 13:06:07 就是 UTC 的 05:06:07，两个写法落到同一时刻
        assertEquals(LocalDateTime.of(2026, 3, 4, 5, 6, 7), parseStamp(item.createdAt))
        assertEquals(parseStamp(item.createdAt), parseStamp(item.updatedAt))
    }

    @Test
    fun `电脑导出的时间格式（不带小数秒）也认得`() = runBlocking {
        val table = mapOf(
            "物料汇总" to listOf(
                listOf("物料ID", "物料名称", "单位", "数量", "单价", "添加时间", "修改时间"),
                listOf("1", "筒灯", "个", "6", "30.0",
                    "2026-03-04 05:06:07", "2026-04-05 06:07:08"),
            ),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "merge")

        val item = db.items().all(listId).single()
        // 归一成本机格式后与带微秒的写法等价：同一时刻
        val expectCreated = LocalDateTime.of(2026, 3, 4, 5, 6, 7)
        assertEquals(expectCreated, parseStamp(item.createdAt))
        assertEquals(expectCreated.format(STAMP), item.createdAt)
    }

    @Test
    fun `老文件没这两列时不动已有时间，新行补一个现在`() = runBlocking {
        // 先按带时间的表建好一条
        SheetMapping.fromSheets(db, listId, sheetsWithTime(), mode = "merge")
        val before = db.items().all(listId).single()

        // 再来一份老格式的表（没有时间列）：这条物料的时间不能被刷成"现在"
        val oldStyle = mapOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "单位", "数量", "单价"),
                listOf("", "筒灯", "个", "6", "30.0"),
            ),
        )
        SheetMapping.fromSheets(db, listId, oldStyle, mode = "merge")
        val after = db.items().all(listId).single()
        assertEquals("老文件不该改动已有时间", before.createdAt, after.createdAt)
        assertEquals(before.updatedAt, after.updatedAt)

        // 同一次导入里新建的另外一条：没有来源时间，补"现在"，不能是空串
        val withNew = mapOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "单位", "数量", "单价"),
                listOf("", "筒灯", "个", "6", "30.0"),
                listOf("", "新买的开关", "个", "2", "12.0"),
            ),
        )
        SheetMapping.fromSheets(db, listId, withNew, mode = "merge")
        val fresh = db.items().all(listId).first { it.name == "新买的开关" }
        assertNotNull("新行的添加时间不能是空串", parseStamp(fresh.createdAt))
        assertNotEquals("新行要有自己的时间", "", fresh.createdAt)
    }
}

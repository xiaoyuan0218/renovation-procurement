package com.xiaoyuan.renovation.mobile.data.xlsx

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ItemListEntity
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
 * 表格导入的落库行为（真开内存库跑）。
 *
 * 重点是 `merge` 模式：按名称合并时，表格里的「布点明细」是这份物料分到哪些
 * 分组的**完整快照**，所以重建分配前必须先清掉旧分配。否则同一格会被插成
 * 两条（旧的一条 + 表格里的一条），数量凭空翻倍 —— 这正是 2026-09-19 走查
 * 实测到的问题：KETING 8 变成两条 8。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportMergeTest {

    private lateinit var db: AppDatabase
    private var listId = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listId = db.lists().insert(
            ItemListEntity(name = "测试清单", note = "", sort = 0, code = "TST001"),
        ).toInt()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 一张最简的导出表：一条物料 + 它的一条布点。 */
    private fun sheets(itemName: String, roomName: String, qty: Double) = mapOf(
        "物料汇总" to listOf(
            listOf("类目", "物料名称", "品牌", "型号", "单位", "数量", "单价", "优惠单价", "日常价", "物料ID"),
            listOf("", itemName, "", "", "个", qty.toString(), "10.0", "", "", "5"),
        ),
        "布点明细" to listOf(
            listOf("物料名称", "房间", "数量", "单价", "备注", "物料ID"),
            listOf(itemName, roomName, qty.toString(), "", "", "5"),
        ),
    )

    @Test
    fun `按名称合并时同名物料的旧分配要先清掉，不能重复插`() = runBlocking {
        val table = sheets("筒灯", "客厅", 8.0)
        SheetMapping.fromSheets(db, listId, table, mode = "merge")

        val item = db.items().all(listId).single()
        val after1 = db.allocations().ofItem(item.id)
        assertEquals("第一次导入：一条分配", 1, after1.size)
        assertEquals(8.0, after1.first().qty, 0.0001)

        // 同一张表再导一次：语义没变，结果也不该变
        SheetMapping.fromSheets(db, listId, table, mode = "merge")

        val after2 = db.allocations().ofItem(item.id)
        assertEquals("重复导入后仍是一条分配（旧的要被清掉）", 1, after2.size)
        assertEquals(8.0, after2.first().qty, 0.0001)
        assertEquals("物料也只有一条", 1, db.items().all(listId).size)
    }

    @Test
    fun `按名称合并时表格里改过的分配要覆盖旧的，不是叠加`() = runBlocking {
        SheetMapping.fromSheets(db, listId, sheets("筒灯", "客厅", 8.0), mode = "merge")
        val item = db.items().all(listId).single()

        // 电脑上把这 8 个改成 5 个，导回来该是 5，不是 13
        SheetMapping.fromSheets(db, listId, sheets("筒灯", "客厅", 5.0), mode = "merge")

        val allocs = db.allocations().ofItem(item.id)
        assertEquals(1, allocs.size)
        assertEquals("数量按表格走", 5.0, allocs.first().qty, 0.0001)
    }

    @Test
    fun `一条物料分到多个分组时每个分组各留一条`() = runBlocking {
        val table = mapOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "品牌", "型号", "单位", "数量", "单价", "优惠单价", "日常价", "物料ID"),
                listOf("", "筒灯", "", "", "个", "12.0", "10.0", "", "", "5"),
            ),
            "布点明细" to listOf(
                listOf("物料名称", "房间", "数量", "单价", "备注", "物料ID"),
                listOf("筒灯", "客厅", "8.0", "", "", "5"),
                listOf("筒灯", "卧室", "4.0", "", "", "5"),
            ),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "merge")
        val item = db.items().all(listId).single()
        assertEquals(2, db.allocations().ofItem(item.id).size)

        // 再导一次：仍是两条，各是各的数量
        SheetMapping.fromSheets(db, listId, table, mode = "merge")
        val allocs = db.allocations().ofItem(item.id)
        assertEquals("重复导入后仍是两条", 2, allocs.size)
        assertEquals(listOf(4.0, 8.0), allocs.map { it.qty }.sorted())
    }

    @Test
    fun `覆盖模式重建后分配不重复`() = runBlocking {
        val table = sheets("筒灯", "客厅", 8.0)
        SheetMapping.fromSheets(db, listId, table, mode = "replace")
        SheetMapping.fromSheets(db, listId, table, mode = "replace")

        val item = db.items().all(listId).single()
        assertEquals("覆盖两次后仍是一条分配", 1, db.allocations().ofItem(item.id).size)
        assertTrue(db.items().all(listId).size == 1)
    }
}

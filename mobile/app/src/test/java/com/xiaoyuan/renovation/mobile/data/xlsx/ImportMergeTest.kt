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

    /* ---------------- PC 与手机互传：这几条盯的是「换了设备也不出错」 ---------------- */

    @Test
    fun `分组页里的空分组与空分类也要建出来`() = runBlocking {
        // 只存在于「分组」「分类」两页、没有任何物料的分组，从前会在搬运中消失
        val table = mapOf(
            "物料汇总" to listOf(listOf("类目", "物料名称", "单位", "数量", "单价")),
            "分组" to listOf(listOf("分组名称"), listOf("客厅"), listOf("还没用过")),
            "分类" to listOf(listOf("分类名称"), listOf("照明"), listOf("备用")),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "replace")

        assertEquals(
            setOf("客厅", "还没用过"),
            db.rooms().byList(listId).map { it.name }.toSet(),
        )
        assertEquals(
            setOf("照明", "备用"),
            db.categories().byList(listId).map { it.name }.toSet(),
        )
    }

    @Test
    fun `分组名里带斜杠或顿号不会被拆成两个分组`() = runBlocking {
        // 「客厅/餐厅」是一个分组名。采购记录的「分组」列多个分组用顿号连接，
        // 无脑拆会把它拆坏 —— 先整体匹配已有分组名就不会。
        val table = mapOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "单位", "数量", "单价", "物料ID"),
                listOf("", "筒灯", "个", "2", "10", "1"),
            ),
            "采购记录" to listOf(
                listOf("物料名称", "实付数量", "实付金额", "付款日期", "分组", "物料ID"),
                listOf("筒灯", "2", "20", "2026-09-01", "客厅/餐厅", "1"),
            ),
            "分组" to listOf(listOf("分组名称"), listOf("客厅/餐厅")),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "replace")

        assertEquals(listOf("客厅/餐厅"), db.rooms().byList(listId).map { it.name })
    }

    @Test
    fun `同名物料各归各的，不会并成一条`() = runBlocking {
        val table = mapOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "单位", "数量", "单价", "物料ID"),
                listOf("", "灯带控制器", "个", "1", "99", "1"),
                listOf("", "灯带控制器", "个", "2", "99", "2"),
            ),
            "布点明细" to listOf(
                listOf("物料名称", "房间", "数量", "单价", "备注", "物料ID"),
                listOf("灯带控制器", "客厅", "1", "", "", "1"),
                listOf("灯带控制器", "卧室", "2", "", "", "2"),
            ),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "merge")

        val items = db.items().all(listId)
        assertEquals("两条同名物料要各留一条", 2, items.size)
        val perItem = items.map { db.allocations().ofItem(it.id).sumOf { a -> a.qty } }
        assertEquals("布点要按「物料ID」各归各的", setOf(1.0, 2.0), perItem.toSet())
    }

    @Test
    fun `合并模式下采购记录不会翻倍`() = runBlocking {
        val table = mapOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "单位", "数量", "单价", "物料ID"),
                listOf("", "筒灯", "个", "2", "10", "1"),
            ),
            "采购记录" to listOf(
                listOf("物料名称", "实付数量", "实付金额", "付款日期", "分组", "物料ID"),
                listOf("筒灯", "1", "10", "2026-09-01", "客厅", "1"),
            ),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "merge")
        SheetMapping.fromSheets(db, listId, table, mode = "merge")

        val item = db.items().all(listId).single()
        assertEquals("同一份文件连导两次，记录仍只有一笔", 1, db.records().ofItem(item.id).size)
    }

    @Test
    fun `一笔采购涉及的分组要真的关联上`() = runBlocking {
        // 从前按位置传参（RecordRoomEntity(id, it)），字段错位、roomId 永远是 null
        // —— 钱花在哪几个分组整个丢掉
        val table = mapOf(
            "物料汇总" to listOf(
                listOf("类目", "物料名称", "单位", "数量", "单价", "物料ID"),
                listOf("", "筒灯", "个", "6", "10", "1"),
            ),
            "采购记录" to listOf(
                listOf("物料名称", "实付数量", "实付金额", "付款日期", "分组", "物料ID"),
                listOf("筒灯", "6", "60", "2026-09-01", "客厅、卧室", "1"),
            ),
        )
        SheetMapping.fromSheets(db, listId, table, mode = "replace")

        val record = db.records().byList(listId).single()
        val rooms = db.recordRooms().ofRecord(record.id)
            .mapNotNull { rr -> rr.roomId?.let { id -> db.rooms().byId(id)?.name } }
            .toSet()
        assertEquals("一笔钱涉及的两个分组都要关联上", setOf("客厅", "卧室"), rooms)
    }

    @Test
    fun `合并模式下额外费用按表格整体替换，不叠加`() = runBlocking {
        fun withExpense(kind: String, amount: String) = mapOf(
            "物料汇总" to listOf(listOf("类目", "物料名称", "单位", "数量", "单价")),
            "额外费用" to listOf(
                listOf("类型", "金额", "日期", "商家", "订单号", "备注"),
                listOf(kind, amount, "2026-09-01", "", "", ""),
            ),
        )
        SheetMapping.fromSheets(db, listId, withExpense("运费", "120"), mode = "merge")
        assertEquals(1, db.expenses().byList(listId).size)

        SheetMapping.fromSheets(db, listId, withExpense("安装费", "80"), mode = "merge")
        val expenses = db.expenses().byList(listId)
        assertEquals("费用是流水账，按表格整体替换", 1, expenses.size)
        assertEquals("安装费", expenses.single().kind)
    }

    @Test
    fun `老文件没有费用页时不动现有费用`() = runBlocking {
        SheetMapping.fromSheets(
            db, listId,
            mapOf(
                "物料汇总" to listOf(listOf("类目", "物料名称", "单位", "数量", "单价")),
                "额外费用" to listOf(
                    listOf("类型", "金额", "日期", "商家", "订单号", "备注"),
                    listOf("运费", "120", "", "", "", ""),
                ),
            ),
            mode = "replace",
        )
        assertEquals(1, db.expenses().byList(listId).size)

        // 不带费用页的表格（老版本导出的）导一次：现有费用留着，不能被清掉
        SheetMapping.fromSheets(
            db, listId,
            mapOf("物料汇总" to listOf(listOf("类目", "物料名称", "单位", "数量", "单价"))),
            mode = "replace",
        )
        assertEquals("老文件不该清掉现有费用", 1, db.expenses().byList(listId).size)
    }
}

package com.xiaoyuan.renovation.mobile.data.xlsx

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemListEntity
import com.xiaoyuan.renovation.mobile.domain.ItemBundle
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
 * 商品链接（1.2.5 起）在「界面读回」与「表格导出导入」两条路上都不能丢，而且
 * 只认 http/https —— 这个值最终会被渲染成可点的链接，伪协议等于脚本执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ItemLinkRoundTripTest {

    private lateinit var db: AppDatabase
    private var listId = 0
    private var itemId = 0

    private val link = "https://item.jd.com/100012043978.html"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        listId = db.lists().insert(
            ItemListEntity(name = "链接清单", note = "", sort = 0, code = "LNK001"),
        ).toInt()
        itemId = db.items().insert(
            ItemEntity(listId = listId, name = "壁挂炉", unit = "台", qtyTotal = 1.0,
                price = 6999.0, link = link),
        ).toInt()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `读回物料时链接跟着回来（不然编辑页重开就是空的）`() = runBlocking {
        val entity = db.items().byId(itemId)!!

        val dto = LocalCompute.toDto(ItemBundle(item = entity))

        assertEquals("链接要在 DTO 里带回来", link, dto.link)
    }

    @Test
    fun `伪协议一律清空，只认 http 和 https`() {
        assertEquals(link, LocalCompute.cleanLink(link))
        assertEquals("http://item.jd.com/1.html",
            LocalCompute.cleanLink("http://item.jd.com/1.html"))
        assertEquals("前后空格去掉", link, LocalCompute.cleanLink("  $link  "))
        assertEquals("javascript 伪协议不能留",
            "", LocalCompute.cleanLink("javascript:alert(1)"))
        assertEquals("data 伪协议不能留",
            "", LocalCompute.cleanLink("data:text/html,<script>1</script>"))
        assertEquals("没有协议头的裸网址不当链接",
            "", LocalCompute.cleanLink("item.jd.com/1.html"))
        assertEquals("", LocalCompute.cleanLink(null))
    }

    @Test
    fun `物料汇总的表头有商品链接列，位置与后端一致`() = runBlocking {
        val rows = SheetMapping.toSheets(db, listId).toMap()["物料汇总"]!!
        val header = rows[0].map { it.toString() }
        val data = rows[1]

        assertEquals("表头与数据列数要一致（多了少了都会整列错位）", header.size, data.size)
        val idx = header.indexOf("商品链接")
        assertTrue("表头要有「商品链接」列：$header", idx >= 0)
        assertEquals("商品链接列写在备注之后（与后端 excel_io 一致）",
            header.indexOf("备注") + 1, idx)
        assertEquals(link, data[idx].toString())
    }

    @Test
    fun `手机导出再导回，链接不丢`() = runBlocking {
        val sheets = SheetMapping.toSheets(db, listId)
            .associate { (name, rows) ->
                name to rows.map { row -> row.map { it?.toString() ?: "" } }
            }
        // 先把链接清掉（模拟"换了台设备/这份数据还没有链接"），导回时应当从表里读回来
        db.items().update(db.items().byId(itemId)!!.copy(link = ""))

        SheetMapping.fromSheets(db, listId, sheets, mode = "merge")

        assertEquals("导出再导入要一分不差", link,
            db.items().all(listId).single().link)
    }

    @Test
    fun `老表格没有商品链接列时，不清空已填的链接`() = runBlocking {
        val sheets = SheetMapping.toSheets(db, listId)
        val rows = sheets.toMap()["物料汇总"]!!
        val header = rows[0].map { it.toString() }
        val linkIdx = header.indexOf("商品链接")

        // 模拟 1.2.5 之前导出的文件：整列删掉（表头与数据行都少一格）
        val oldSheets = sheets.map { (name, table) ->
            name to table.map { row -> row.filterIndexed { i, _ -> i != linkIdx } }
                .map { row -> row.map { it?.toString() ?: "" } }
        }.toMap()

        SheetMapping.fromSheets(db, listId, oldSheets, mode = "merge")

        assertEquals("老文件没带这个信息 ≠ 要清空", link,
            db.items().all(listId).single().link)
    }
}

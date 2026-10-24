package com.xiaoyuan.renovation.mobile.data.xlsx

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemListEntity
import com.xiaoyuan.renovation.mobile.data.model.ItemInDto
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import com.xiaoyuan.renovation.mobile.data.repo.CurrentListHolder
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import com.xiaoyuan.renovation.mobile.data.repo.okData
import com.xiaoyuan.renovation.mobile.domain.ItemBundle
import com.xiaoyuan.renovation.mobile.domain.LocalCompute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 *
 * 存库口径是「统一链接提取规范」：从输入里提取第一个 http(s) 链接（整段分享
 * 文案也认），没有就清空；对已清洗过的纯 URL 幂等，读回路径不受影响。
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

    /*
     * 下面是「统一链接提取规范」的用例。四条样例文案按方案给定的链接域
     * （3.cn / xhslink.com / e.tb.cn / mobile.yangkeduo.com）于 2026-10-25
     * 依 testCases 的描述构造，不是真实抓取的原文 —— 方案只固定了域名与
     * 关键参数形态（@ 完整、tk= 保留），样例改版时以此为对照基线。
     */

    @Test
    fun `从整段分享文案里提取第一个 http 链接`() {
        assertEquals("https://3.cn/38K-9pPI?jkl=@YC8fI3EmHbBY@",
            LocalCompute.extractLink(
                "【京东】好物推荐 https://3.cn/38K-9pPI?jkl=@YC8fI3EmHbBY@ 点击下单"))
        assertEquals("https://xhslink.com/m/2x3IQ9yoNdZ49IKkEhFMOG",
            LocalCompute.extractLink(
                "【小红书】看看这个 https://xhslink.com/m/2x3IQ9yoNdZ49IKkEhFMOG"))
        assertEquals("淘宝的 tk= 参数要原样保留",
            "https://e.tb.cn/h.TnDmJ5?tk=abCdEf12",
            LocalCompute.extractLink(
                "【淘宝】复制整段打开淘宝 https://e.tb.cn/h.TnDmJ5?tk=abCdEf12 抢好物"))
        assertEquals("纯 URL 输出原值", "https://mobile.yangkeduo.com/goods.html?goods_id=88888",
            LocalCompute.extractLink("https://mobile.yangkeduo.com/goods.html?goods_id=88888"))

        assertEquals("两个链接取第一个（确定性规则，同步两端才不会互相覆盖）",
            "https://a.com/1", LocalCompute.extractLink("先看 https://a.com/1 再看 https://b.com/2"))
        assertEquals("口令码里没有 http 链接", "",
            LocalCompute.extractLink("口令：￥a1b2c3￥"))
    }

    @Test
    fun `尾部标点剥除，收括号看配对`() {
        // 候选 "https://x.com/a_(page))"：( 出现 1 次、) 出现 2 次，只剥外面
        // 多出来的那一个；URL 自己配对的 "(page)" 不能被剥坏
        assertEquals("https://x.com/a_(page)",
            LocalCompute.extractLink("see https://x.com/a_(page)) thanks"))
        assertEquals("URL 里没有开括号时，尾部的右括号是包裹符，剥净", "https://x.com/a",
            LocalCompute.extractLink("(see https://x.com/a))"))
        assertEquals("https://x.com/a_(page)", LocalCompute.extractLink("https://x.com/a_(page)"))
    }

    @Test
    fun `空 host 与超长 URL 清空，scheme 大小写保留`() {
        assertEquals("", LocalCompute.extractLink("https://..."))
        assertEquals("", LocalCompute.extractLink("https://)"))
        assertEquals("", LocalCompute.extractLink("https://./x"))
        assertEquals("无路径的短链要放行", "https://a", LocalCompute.extractLink("https://a"))
        assertEquals("https://1.2.3.4/x", LocalCompute.extractLink("https://1.2.3.4/x"))
        assertEquals("https://localhost:8000/x",
            LocalCompute.extractLink("https://localhost:8000/x"))
        assertEquals("600 字符的 URL 截断就坏，整体放弃", "",
            LocalCompute.extractLink("https://example.com/" + "a".repeat(600)))
        assertEquals("scheme 大小写保留原样（正则里的 $ 若被字符串模板吃掉，这条会挂）",
            "HTTP://X.COM/A", LocalCompute.extractLink("HTTP://X.COM/A"))
        assertEquals("大小写混写的 scheme 与域名照常提取", "Https://E.TB.CN/a?tk=1",
            LocalCompute.extractLink("看这个 Https://E.TB.CN/a?tk=1 好"))
    }

    @Test
    fun `cleanLink 幂等，提取两次结果一致`() {
        val text = "【京东】 https://3.cn/38K-9pPI?jkl=@YC8fI3EmHbBY@ 下单"
        val once = LocalCompute.cleanLink(text)
        assertEquals(once, LocalCompute.cleanLink(once))
    }

    @Test
    fun `存库入口把整段文案收口成提取结果`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            // LocalRepository 是本地写库的唯一出口；它经 cleanLink 走提取，
            // 超长 URL 在这里就清空，不会出现「本地存了、同步时被清」的落差
            val repo = LocalRepository(db, CurrentListHolder(AppPrefs(context), db, scope))
            val extracted = repo.createItem(
                ItemInDto(
                    name = "整段文案",
                    link = "【京东】好物推荐 https://3.cn/38K-9pPI?jkl=@YC8fI3EmHbBY@ 点击下单",
                ),
            ).okData!!
            assertEquals("https://3.cn/38K-9pPI?jkl=@YC8fI3EmHbBY@", extracted.link)

            val tooLong = repo.createItem(
                ItemInDto(name = "超长链接", link = "https://example.com/" + "a".repeat(600)),
            ).okData!!
            assertEquals("600 字符 URL 落库即为空", "", tooLong.link)
        } finally {
            scope.cancel()
        }
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

package com.xiaoyuan.renovation.mobile.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ItemListEntity
import com.xiaoyuan.renovation.mobile.data.db.SyncBindingEntity
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.CurrentListHolder
import com.xiaoyuan.renovation.mobile.data.repo.okData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同步的对外行为：真开一个内存库、真起一个假服务器，走完整的请求与落库。
 *
 * 这里管的是 [MergerTest] 管不到的那一层 —— 合并规则本身已经在那边逐条测过，
 * 这边测的是"请求失败时该怎么办"。其中**最要紧的是反面**：只有服务器明确说
 * 404（这份清单在电脑上被删了）才允许解除绑定；断网、超时、500 一律不许动它，
 * 否则用户一进电梯就丢掉全部绑定关系。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var prefs: AppPrefs
    private lateinit var scope: CoroutineScope
    private lateinit var session: ServerSession
    private lateinit var currentList: CurrentListHolder
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = AppPrefs(context)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val url = server.url("/").toString()
        prefs.saveServer(url, "u", "t")
        session = ServerSession(prefs, scope)
        currentList = CurrentListHolder(prefs, db, scope)
        engine = SyncEngine(db, prefs, currentList, session)

        // 地址和 token 是 DataStore 异步读回来的，等它们就位再开测
        awaitUntil("服务器地址与凭证就位") {
            session.url.value == url && session.token.value == "t"
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.shutdown()
    }

    /* ---------------- 服务器上那份没了 ---------------- */

    @Test
    fun `服务器上那份被删了——自动解绑、本地数据留着、并给出提示`() = runBlocking {
        val listId = newList("装修采购", code = "ABCDEFGH")
        db.sync().upsert(binding(listId))
        server.enqueue(response(404, """{"detail":"清单不存在"}"""))

        val outcome = engine.sync(listId).okData ?: error("404 不该算同步失败，而是解绑")

        assertTrue("要告诉调用方发生了什么", outcome.remoteMissing)
        assertNull("绑定应当已经解除", db.sync().byList(listId))
        assertNotNull("本地清单必须原样留着", db.lists().byId(listId))
        val notice = engine.events.value
        assertNotNull("得弹个提示，否则用户不知道绑定没了", notice)
        assertTrue("提示里要点出是哪份清单：$notice", notice!!.contains("装修采购"))
    }

    @Test
    fun `服务器报错时不能误解除绑定`() = runBlocking {
        val listId = newList("装修采购", code = "ABCDEFGH")
        db.sync().upsert(binding(listId))
        server.enqueue(response(500, """{"detail":"内部错误"}"""))

        val result = engine.sync(listId)

        assertTrue("非 404 就是普通失败：$result", result is ApiResult.Err)
        assertNotNull("绑定必须留着 —— 服务器出错不等于这份清单没了", db.sync().byList(listId))
        assertNull("不该弹「已解除绑定」的提示", engine.events.value)
    }

    @Test
    fun `后台自动同步遇到服务器删除时让界面刷新`() = runBlocking {
        val listId = newList("装修采购", code = "ABCDEFGH")
        db.sync().upsert(binding(listId))
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        server.enqueue(response(404, """{"detail":"没了"}"""))

        val changed = engine.autoSyncCurrent()

        assertTrue("解绑是状态变化，界面得跟着刷新", changed)
        assertNull(db.sync().byList(listId))
    }

    /* ---------------- 编号是身份：上传与拉取都按它认 ---------------- */

    @Test
    fun `从没同步过就撞上同一份——先问用户怎么对齐，不擅自写服务器`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        server.enqueue(response(200, listJson(id = 5, code = code)))
        server.enqueue(response(200, snapshotJson("fp-server", code)))

        val outcome = engine.upload(listId, "装修采购").okData ?: error("上传应当成功")

        val decision = outcome.needsUploadDecision
            ?: error("从没同步过、又撞上同一份，应当交给用户决定而不是硬合并")
        assertEquals(5, decision.remoteListId)
        assertNull("还没决定，不该建立绑定", db.sync().byList(listId))

        // 只查了清单列表和服务器那份的内容，没有往服务器写任何东西
        val first = server.next()
        val second = server.next()
        assertEquals("/api/lists", first.path)
        assertEquals("/api/sync/lists/5", second.path)
        assertEquals("GET", second.method)
        assertEquals("只有两次请求，不能有推送", 0, server.requestCount - 2)
    }

    @Test
    fun `绑过、有基线时仍然直接走三方合并，不打扰用户`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        // 有基线 = 之前同步过，常规路径
        db.sync().upsert(
            binding(listId, remoteListId = 5).copy(
                baseline = """{"fingerprint":"fp-old","payload":{"version":1,"list":{""" +
                    """"name":"装修采购","code":"$code"}},"rooms":[],"categories":[],""" +
                    """"items":[],"expenses":[]},"localMap":{}}""",
            ),
        )
        server.enqueue(response(200, listJson(id = 5, code = code)))
        server.enqueue(response(200, snapshotJson("fp-server", code)))
        server.enqueue(response(200, snapshotJson("fp-after", code)))

        val outcome = engine.upload(listId, "装修采购").okData ?: error("上传应当成功")

        assertNull("有基线就该直接合并", outcome.needsUploadDecision)
        val find = server.next()
        val exported = server.next()
        val pushed = server.next()
        assertEquals("/api/lists", find.path)
        assertEquals("/api/sync/lists/5", exported.path)
        assertEquals("PUT", pushed.method)
    }

    @Test
    fun `选两边合并——推上去的内容不含重复的同名分组分类`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        // 本地有一个分组和分类，服务器那份也有同名的 —— 没有基线，靠名字配对
        db.rooms().insert(
            com.xiaoyuan.renovation.mobile.data.db.RoomEntity(listId = listId, name = "示例分组"),
        )
        db.categories().insert(
            com.xiaoyuan.renovation.mobile.data.db.CategoryEntity(listId = listId, name = "示例分类"),
        )
        server.enqueue(response(200, snapshotWithNames("fp-server", code)))
        server.enqueue(response(200, snapshotJson("fp-merged", code)))

        val result = engine.resolveUpload(listId, 5, UploadChoice.MergeBoth)

        assertTrue("合并应当成功：$result", result is ApiResult.Ok)
        val exported = server.next()
        assertEquals("先取服务器那份的内容", "/api/sync/lists/5", exported.path)
        assertEquals("GET", exported.method)
        val push = server.next()
        assertEquals("PUT", push.method)
        val body = push.body.readUtf8()
        assertEquals("同名分组不该出现两次", 1, Regex("\"示例分组\"").findAll(body).count())
        assertEquals("同名分类不该出现两次", 1, Regex("\"示例分类\"").findAll(body).count())
        assertNotNull("合并完要建立绑定", db.sync().byList(listId))
    }

    @Test
    fun `选以电脑为准——本地换成服务器的内容，不往服务器写`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        server.enqueue(response(200, snapshotJson("fp-server", code)))

        val result = engine.resolveUpload(listId, 5, UploadChoice.KeepRemote)

        assertTrue("应当成功：$result", result is ApiResult.Ok)
        val only = server.next()
        assertEquals("/api/sync/lists/5", only.path)
        assertEquals("只读不写", "GET", only.method)
        assertEquals(5, db.sync().byList(listId)?.remoteListId)
    }

    @Test
    fun `选用手机上的覆盖——force 推送、不带指纹、服务器换成手机的内容`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        // 本地有一条手机上的物料
        db.items().insert(
            com.xiaoyuan.renovation.mobile.data.db.ItemEntity(
                listId = listId, name = "手机上的筒灯", price = 33.0,
            ),
        )
        // 服务器上那份是另一套内容（没有这条物料）
        server.enqueue(response(200, snapshotWithItem("fp-server", code, price = 66.0)))
        server.enqueue(response(200, snapshotJson("fp-after", code)))

        val result = engine.resolveUpload(listId, 5, UploadChoice.OverwriteRemote)

        assertTrue("覆盖应当成功：$result", result is ApiResult.Ok)
        val exported = server.next()
        assertEquals("先取服务器内容", "/api/sync/lists/5", exported.path)
        val push = server.next()
        assertEquals("覆盖走 PUT", "PUT", push.method)
        val body = push.body.readUtf8()
        assertFalse(
            "不带 base_fingerprint：意思是'我知道服务器什么样，但我就是要覆盖'",
            body.contains("base_fingerprint"),
        )
        assertTrue("force 必须带上（服务器好留整库备份）", body.contains("\"force\":true"))
        assertTrue("推的是手机的内容", body.contains("手机上的筒灯"))
        assertFalse("不该有服务器那份的物料", body.contains("筒灯\"") && !body.contains("手机上的筒灯") && body.contains("\"price\":66.0"))
        assertEquals("覆盖完要建立绑定", 5, db.sync().byList(listId)?.remoteListId)
    }

    @Test
    fun `服务器上没有同编号时才新建一份`() = runBlocking {
        val listId = newList("装修采购", code = "ABCDEFGH")
        server.enqueue(response(200, "[]"))
        server.enqueue(response(200, createJson(listId = 7, code = "ABCDEFGH")))

        val outcome = engine.upload(listId, "装修采购").okData ?: error("上传应当成功")

        assertEquals(7, outcome.createdListId)
        val first = server.next()
        val second = server.next()
        assertEquals("/api/lists", first.path)
        assertEquals("POST", second.method)
        assertEquals("/api/sync/lists", second.path)
    }

    @Test
    fun `拉取时本地已有同编号——直接绑定，不重复建一份`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        server.enqueue(response(200, snapshotJson("fp-server", code)))
        server.enqueue(response(200, snapshotJson("fp-server", code)))

        val outcome = engine.pullAsNewList(5, "装修采购").okData ?: error("拉取应当成功")

        assertEquals("要报出绑的是本地哪一份", listId, outcome.createdListId)
        assertEquals("不该多出一份清单", 1, db.lists().all().size)
        assertNotNull("应当已经建立绑定", db.sync().byList(listId))
        assertEquals(5, db.sync().byList(listId)?.remoteListId)

        val first = server.next()
        val second = server.next()
        assertEquals("/api/sync/lists/5", first?.path)
        assertEquals("/api/sync/lists/5", second?.path)
        // 本地与服务器内容一致（这份清单是空的），两边都没动 ——
        // 从前这里会无条件 PUT 把服务器重写一遍，推完 id 全变，
        // 自动同步就被自己一轮轮转起来；现在不推了
        val third = server.takeRequest(2, TimeUnit.SECONDS)
        assertNull("内容一致时不该有第三次请求（更不该重写服务器）", third)
    }

    /* ---------------- 各种"不该动手"的前置条件 ---------------- */

    @Test
    fun `未绑定的清单同步要明确报错`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")

        val result = engine.sync(listId)

        assertTrue("未绑定应当返回失败：$result", result is ApiResult.Err)
        assertEquals("这份清单还没绑定服务器", (result as ApiResult.Err).message)
        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    @Test
    fun `清单不存在时同步报错`() = runBlocking {
        val sync = engine.sync(999999)
        assertTrue(sync is ApiResult.Err)
        // sync 先看有没有绑定；清单根本不存在时自然也没有绑定，
        // 报"还没绑定"比报"清单不存在"更贴近用户能做的事
        assertEquals("这份清单还没绑定服务器", (sync as ApiResult.Err).message)
        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    @Test
    fun `清单不存在时上传报错`() = runBlocking {
        val upload = engine.upload(999999, "不存在")
        assertTrue(upload is ApiResult.Err)
        assertEquals("清单不存在", (upload as ApiResult.Err).message)
        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    /* ---------------- 后台自动同步：哪些情况不该动 ---------------- */

    @Test
    fun `自动同步在未绑定时不跑`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }

        val changed = engine.autoSyncCurrent()

        assertFalse("没绑定就不该同步", changed)
        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    @Test
    fun `自动同步在关掉开关时不跑`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")
        db.sync().upsert(binding(listId, autoSync = false))
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }

        val changed = engine.autoSyncCurrent()

        assertFalse("关了自动同步就不该跑", changed)
        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    @Test
    fun `自动同步在没登录时不跑`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")
        db.sync().upsert(binding(listId))
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        engine.logout()
        awaitUntil("凭证已清空") { session.token.value.isBlank() }

        val changed = engine.autoSyncCurrent()

        assertFalse("没登录就不该跑", changed)
        assertEquals("不该发出任何请求", 0, server.requestCount)
    }

    @Test
    fun `自动同步在服务器报错时静默返回`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")
        db.sync().upsert(binding(listId))
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        server.enqueue(response(500, """{"detail":"内部错误"}"""))

        val changed = engine.autoSyncCurrent()

        assertFalse("失败要静默，不能打扰用户", changed)
        assertNotNull("绑定必须留着", db.sync().byList(listId))
        assertNull("不该弹提示", engine.events.value)
    }

    @Test
    fun `自动同步在有冲突时静默返回、不弹窗`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        db.items().insert(
            com.xiaoyuan.renovation.mobile.data.db.ItemEntity(
                listId = listId, name = "筒灯", price = 12.0, updatedAt = "2026-09-18 10:00:00",
            ),
        )
        // 基线里这条是 10.0 —— 本地改过
        db.sync().upsert(
            binding(listId, remoteListId = 5).copy(
                fingerprint = "fp-old",
                baseline = baselineJson(code, itemPrice = 10.0, itemUpdated = "2026-09-18 09:00:00"),
            ),
        )
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        // 服务器上也改成另一个值、时间更新 —— 两边都改、服务器赢
        server.enqueue(response(200, snapshotWithItem("fp-server", code, price = 20.0)))

        val changed = engine.autoSyncCurrent()

        assertFalse("有冲突要静默，等用户手动同步时再问", changed)
    }

    @Test
    fun `自动同步在内容真变了时让界面刷新`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        // 基线是空的：服务器上后来多了一条物料 —— 属于"服务器新增"，会拉下来
        db.sync().upsert(
            binding(listId, remoteListId = 5).copy(
                fingerprint = "fp-old",
                baseline = emptyBaselineJson(code),
            ),
        )
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        server.enqueue(response(200, snapshotWithItem("fp-server", code, price = 20.0)))
        server.enqueue(response(200, snapshotWithItem("fp-after", code, price = 20.0)))

        val changed = engine.autoSyncCurrent()

        assertTrue("拉下来内容变了，界面得刷新", changed)
        assertEquals("服务器新增的物料要落到本地", 1, db.items().all(listId).size)
    }

    @Test
    fun `用户裁决后重同步不再把同一冲突弹回来`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        // 本地改了价格、时间戳 13:29:43
        db.items().insert(
            com.xiaoyuan.renovation.mobile.data.db.ItemEntity(
                listId = listId, name = "筒灯", price = 111.0, updatedAt = "2026-09-19 13:29:43",
            ),
        )
        // 基线里这条还是 10.0（09:00）—— 两边都改了
        db.sync().upsert(
            binding(listId, remoteListId = 5).copy(
                fingerprint = "fp-old",
                baseline = baselineJson(code, itemPrice = 10.0),
            ),
        )
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        // 服务器也改成 222、同一秒 —— 判不出来，第一轮返回冲突、不推送
        val serverSnap = snapshotWithItemAt("fp-server", code, price = 222.0, updated = "2026-09-19 13:29:43")
        server.enqueue(response(200, serverSnap))

        val first = engine.sync(listId).okData ?: error("第一轮应当成功")
        assertTrue("同一秒判不出来，要弹冲突框", first.hasConflicts)

        // 用户选了"以手机为准"：force 推送成功后这批冲突就算解决了，
        // 不该原样返回让界面再弹一遍 —— 从前用户得对着同一个弹窗选两次
        server.enqueue(response(200, serverSnap))       // 第二轮 GET
        server.enqueue(response(200, snapshotWithItemAt("fp-after", code, price = 111.0, updated = "2026-09-19 13:29:43")))
        val second = engine.sync(listId, preferLocal = true).okData ?: error("第二轮应当成功")
        assertFalse("用户已裁决，不该再弹", second.hasConflicts)
        val firstGet = server.next()
        val secondGet = server.next()
        val push = server.next()
        assertEquals("/api/sync/lists/5", firstGet.path)
        assertEquals("/api/sync/lists/5", secondGet.path)
        assertEquals("裁决后要走 force 推送", "PUT", push.method)
        assertTrue("推的是手机的价格 111", push.body.readUtf8().contains("\"price\":111.0"))
    }

    @Test
    fun `自动同步在内容没变时不刷新界面`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        db.sync().upsert(
            binding(listId, remoteListId = 5).copy(fingerprint = "fp-server"),
        )
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        // 服务器指纹与绑定一致 = 服务器没动过；本地也没内容
        server.enqueue(response(200, snapshotJson("fp-server", code)))
        server.enqueue(response(200, snapshotJson("fp-server", code)))

        val changed = engine.autoSyncCurrent()

        assertFalse("内容没变就别刷界面（刷了会再触发一轮同步）", changed)
    }

    @Test
    fun `两边都没动时不该推送——否则自动同步会自己触发自己`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        // 服务器没动过（指纹与绑定一致），本地内容与基线也一致
        db.sync().upsert(
            binding(listId, remoteListId = 5).copy(
                fingerprint = "fp-server",
                baseline = emptyBaselineJson(code),
            ),
        )
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        server.enqueue(response(200, snapshotJson("fp-server", code)))

        val changed = engine.autoSyncCurrent()

        assertFalse(changed)
        // 只该有一次 GET（取服务器状态），不该有 PUT ——
        // 推上去会让服务器重建内容、本地 id 全变，于是"内容变了"再次成立，
        // 自动同步就被自己一轮轮转起来（实测每几秒一次 PUT）
        assertEquals("不该推送", 1, server.requestCount)
        val only = server.next()
        assertEquals("GET", only.method)
    }

    @Test
    fun `本地内容与基线一致、但服务器动过时仍然要合并`() = runBlocking {
        val code = "ABCDEFGH"
        val listId = newList("装修采购", code)
        db.sync().upsert(
            binding(listId, remoteListId = 5).copy(
                fingerprint = "fp-old",
                baseline = emptyBaselineJson(code),
            ),
        )
        currentList.set(listId)
        awaitUntil("当前清单就位") { currentList.flow.value == listId }
        // 服务器多了一条物料：这是真变化，必须拉下来
        server.enqueue(response(200, snapshotWithItem("fp-server", code, price = 20.0)))
        server.enqueue(response(200, snapshotWithItem("fp-after", code, price = 20.0)))

        val changed = engine.autoSyncCurrent()

        assertTrue("服务器真变了，得同步", changed)
        assertEquals("物料要落到本地", 1, db.items().all(listId).size)
    }

    /* ---------------- 拉取：本地没有同一份时真的新建 ---------------- */

    @Test
    fun `拉取时本地没有同编号——新建一份并绑定`() = runBlocking {
        val code = "ABCDEFGH"
        server.enqueue(response(200, snapshotJson("fp-server", code)))
        server.enqueue(response(200, snapshotJson("fp-server", code)))

        val outcome = engine.pullAsNewList(5, "装修采购").okData ?: error("拉取应当成功")

        val created = outcome.createdListId ?: error("应当报出新建的清单 id")
        assertEquals("本地应当多出一份清单", 1, db.lists().all().size)
        assertEquals("新清单要带上服务器的编号", code, db.lists().byId(created)?.code)
        assertEquals("要建立绑定", 5, db.sync().byList(created)?.remoteListId)
    }

    @Test
    fun `拉取失败时如实报错、不动本地`() = runBlocking {
        server.enqueue(response(404, """{"detail":"清单不存在"}"""))

        val result = engine.pullAsNewList(5, "装修采购")

        assertTrue("应当失败：$result", result is ApiResult.Err)
        assertEquals("本地不该多出清单", 0, db.lists().all().size)
        assertNull("不该建立绑定", db.sync().byList(1))
    }

    /* ---------------- 解除绑定 ---------------- */

    @Test
    fun `解除绑定只断开关联，本地清单与内容都留着`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")
        db.items().insert(
            com.xiaoyuan.renovation.mobile.data.db.ItemEntity(listId = listId, name = "筒灯"),
        )
        db.sync().upsert(binding(listId))

        engine.unbind(listId)

        assertNull("绑定要没了", db.sync().byList(listId))
        assertNotNull("本地清单必须留着", db.lists().byId(listId))
        assertEquals("本地内容也要留着", 1, db.items().all(listId).size)
        assertEquals("不该往服务器发请求（服务器那份原样留着）", 0, server.requestCount)
    }

    @Test
    fun `删除已绑定的清单时绑定跟着清掉，不留悬空行`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")
        db.items().insert(
            com.xiaoyuan.renovation.mobile.data.db.ItemEntity(listId = listId, name = "筒灯"),
        )
        db.sync().upsert(binding(listId))

        db.lists().deleteCascade(listId)

        assertNull("清单没了，绑定必须跟着消失", db.sync().byList(listId))
        assertNull("清单本身要删掉", db.lists().byId(listId))
        assertEquals("内容也要清空", 0, db.items().all(listId).size)
    }

    /* ---------------- 上传失败的如实报错 ---------------- */

    @Test
    fun `上传时服务器报错要如实返回`() = runBlocking {
        val listId = newList("装修采购", "ABCDEFGH")
        server.enqueue(response(500, """{"detail":"内部错误"}"""))

        val result = engine.upload(listId, "装修采购")

        assertTrue("应当失败：$result", result is ApiResult.Err)
        assertNull("失败就不该建立绑定", db.sync().byList(listId))
    }

    /* ---------------- 小工具 ---------------- */

    private suspend fun newList(name: String, code: String): Int =
        db.lists().insert(ItemListEntity(name = name, code = code)).toInt()

    /** 一份带一条物料的服务器基线（时间戳可控，用来造"两边都改过"）。 */
    private fun baselineJson(
        code: String,
        itemPrice: Double = 10.0,
        itemUpdated: String = "2026-09-18 09:00:00",
    ) = """
        {"fingerprint":"fp-old",
         "payload":{"version":1,
           "list":{"name":"装修采购","note":"","sort":0,"code":"$code"},
           "rooms":[],"categories":[],
           "items":[{"id":101,"name":"筒灯","price":$itemPrice,"qty_total":1.0,
                     "updated_at":"$itemUpdated"}],
           "expenses":[]},
         "localMap":{"item:101":1}}
    """.trimIndent()

    /** 空基线（服务器上还没有任何物料）—— 用来造"服务器新增"的场景。 */
    private fun emptyBaselineJson(code: String) = """
        {"fingerprint":"fp-old",
         "payload":{"version":1,
           "list":{"name":"装修采购","note":"","sort":0,"code":"$code"},
           "rooms":[],"categories":[],"items":[],"expenses":[]},
         "localMap":{}}
    """.trimIndent()

    private fun binding(
        listId: Int,
        remoteListId: Int = 5,
        autoSync: Boolean = true,
    ) = SyncBindingEntity(
        listId = listId,
        serverUrl = server.url("/").toString(),
        remoteListId = remoteListId,
        remoteName = "装修采购",
        fingerprint = "fp-server",
        baseline = "",
        lastSyncedAt = "",
        autoSync = autoSync,
    )

    /** 取下一个请求；没有就当场报错 —— 绝不让测试挂死在无限等待上。 */
    private fun MockWebServer.next() =
        takeRequest(5, TimeUnit.SECONDS) ?: error("没有收到预期的请求")

    private fun response(code: Int, body: String) =
        MockResponse().setResponseCode(code).setBody(body)

    private fun listJson(id: Int, code: String) = """
        [{"id":$id,"name":"装修采购","note":"","sort":0,"code":"$code",
          "item_count":0,"room_count":0,"category_count":0}]
    """.trimIndent()

    private fun snapshotJson(fingerprint: String, code: String) = """
        {"fingerprint":"$fingerprint",
         "payload":{"version":1,
           "list":{"name":"装修采购","note":"","sort":0,"code":"$code",
                   "created_at":"2026-09-01 10:00:00","updated_at":"2026-09-01 10:00:00"},
           "rooms":[],"categories":[],"items":[],"expenses":[]}}
    """.trimIndent()

    /** 服务器那份带着同名的分组与分类 —— 用来验合并时不会留成两份。 */
    private fun snapshotWithNames(fingerprint: String, code: String) = """
        {"fingerprint":"$fingerprint",
         "payload":{"version":1,
           "list":{"name":"装修采购","note":"","sort":0,"code":"$code",
                   "created_at":"2026-09-01 10:00:00","updated_at":"2026-09-01 10:00:00"},
           "rooms":[{"id":11,"name":"示例分组","sort":0,
                     "created_at":"2026-09-01 10:00:00","updated_at":"2026-09-01 10:00:00"}],
           "categories":[{"id":21,"name":"示例分类","sort":0,
                          "created_at":"2026-09-01 10:00:00","updated_at":"2026-09-01 10:00:00"}],
           "items":[],"expenses":[]}}
    """.trimIndent()

    /** 服务器那份带一条物料（id 固定 101，便于与基线里的同一条对齐）。 */
    private fun snapshotWithItem(fingerprint: String, code: String, price: Double) =
        snapshotWithItemAt(fingerprint, code, price, updated = "2026-09-18 11:00:00")

    /** 同上，但物料的 updated_at 可指定 —— 造"同一秒、两边都改"用。 */
    private fun snapshotWithItemAt(
        fingerprint: String, code: String, price: Double, updated: String,
    ) = """
        {"fingerprint":"$fingerprint",
         "payload":{"version":1,
           "list":{"name":"装修采购","note":"","sort":0,"code":"$code",
                   "created_at":"2026-09-01 10:00:00","updated_at":"2026-09-01 10:00:00"},
           "rooms":[],"categories":[],
           "items":[{"id":101,"name":"筒灯","price":$price,"qty_total":1.0,
                     "created_at":"2026-09-01 10:00:00","updated_at":"$updated",
                     "allocations":[],"records":[]}],
           "expenses":[]}}
    """.trimIndent()

    private fun createJson(listId: Int, code: String) = """
        {"list_id":$listId,"fingerprint":"fp-new",
         "payload":{"version":1,
           "list":{"name":"装修采购","note":"","sort":0,"code":"$code",
                   "created_at":"2026-09-01 10:00:00","updated_at":"2026-09-01 10:00:00"},
           "rooms":[],"categories":[],"items":[],"expenses":[]}}
    """.trimIndent()

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(20)
        }
        fail("等待超时：$what")
    }
}

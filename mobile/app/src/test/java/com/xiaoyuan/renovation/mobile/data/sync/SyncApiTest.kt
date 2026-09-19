package com.xiaoyuan.renovation.mobile.data.sync

import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 服务器返回内容的翻译：把各种失败翻译成用户能看懂的人话。
 *
 * 重点钉"旧版后端"这条：旧版没有同步接口，兜底路由会回一页 HTML。有的旧后端
 * 回 200，有的回 404/501 —— 无论状态码是什么，只要响应是网页就该提示"升级"，
 * 而不是"请求失败（501）"这种让人摸不着头脑的话。
 */
class SyncApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SyncApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = SyncApi({ server.url("/").toString() }, { "token" })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun html(code: Int): MockResponse =
        MockResponse().setResponseCode(code).setBody("<html><body>旧版首页</body></html>")

    @Test
    fun `旧版后端返回200的网页——提示升级而不是解析错误`() = runBlocking {
        server.enqueue(html(200))

        val result = api.lists()

        assertTrue(result is ApiResult.Err)
        assertEquals(426, (result as ApiResult.Err).code)
        assertEquals("服务器上还是旧版，没有同步功能 —— 先把服务器升级到最新版再试", result.message)
    }

    @Test
    fun `旧版后端返回404的网页——同样提示升级`() = runBlocking {
        server.enqueue(html(404))

        val result = api.lists()

        assertEquals("这条从前会显示「服务器上找不到这份清单」，误导用户去核对清单", 426, (result as ApiResult.Err).code)
    }

    @Test
    fun `旧版后端返回501的网页——同样提示升级`() = runBlocking {
        // python http.server 这类服务对不认识的方法回 501 加 HTML ——
        // 从前显示"请求失败（501）"，用户根本想不到是版本问题
        server.enqueue(html(501))

        val result = api.lists()

        assertEquals(426, (result as ApiResult.Err).code)
        assertEquals("服务器上还是旧版，没有同步功能 —— 先把服务器升级到最新版再试", result.message)
    }

    @Test
    fun `401后端给了detail就用detail的`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"账号或密码不正确"}"""),
        )

        val result = api.lists()

        assertTrue(result is ApiResult.Err)
        assertEquals(401, (result as ApiResult.Err).code)
        assertEquals("账号或密码不正确", result.message)
    }

    @Test
    fun `401后端没给文案时用本地兜底`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val result = api.lists()

        assertEquals("账号或密码不对", (result as ApiResult.Err).message)
    }

    @Test
    fun `404后端给了detail就用detail的`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"detail":"清单不存在"}"""),
        )

        val result = api.lists()

        assertEquals("清单不存在", (result as ApiResult.Err).message)
    }

    @Test
    fun `后端的detail文案优先于本地兜底`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"detail":{"message":"服务器上这份清单在你上次同步之后也改过"}}""",
            ),
        )

        val result = api.lists()

        assertEquals("服务器上这份清单在你上次同步之后也改过", (result as ApiResult.Err).message)
    }
}

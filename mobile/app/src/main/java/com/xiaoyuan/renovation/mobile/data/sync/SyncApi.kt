package com.xiaoyuan.renovation.mobile.data.sync

import com.xiaoyuan.renovation.mobile.data.model.CredentialsInDto
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import com.xiaoyuan.renovation.mobile.data.model.LoginResultDto
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 连服务器用的几个请求。
 *
 * 直接用 OkHttp 而不是 Retrofit：服务器地址是**运行时**才填的（局域网 IP 或域名），
 * Retrofit 那套固定在编译期的 baseUrl 反而要额外绕一圈。整份清单也就几个请求。
 */
class SyncApi(
    private val serverUrl: () -> String,
    private val token: () -> String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /* ---------------- 登录 ---------------- */

    suspend fun login(username: String, password: String): ApiResult<LoginResultDto> = call {
        val body = json.encodeToString(
            CredentialsInDto.serializer(),
            CredentialsInDto(username, password),
        )
        decode(post("/api/auth/login", body))
    }

    /** 服务器上有哪些清单（上传/拉取时让用户挑目标）。 */
    suspend fun lists(): ApiResult<List<ItemListDto>> = call {
        decodeList(get("/api/lists"))
    }

    /* ---------------- 搬运 ---------------- */

    /** 导出服务器上某份清单的全量内容。 */
    suspend fun exportList(remoteListId: Int): ApiResult<SyncSnapshot> = call {
        decode(get("/api/sync/lists/$remoteListId"))
    }

    /** 整份覆盖服务器上的某份清单。 */
    suspend fun pushList(remoteListId: Int, body: SyncPush): ApiResult<SyncSnapshot> = call {
        val text = json.encodeToString(SyncPush.serializer(), body)
        decode(put("/api/sync/lists/$remoteListId", text))
    }

    /** 把本地清单搬成服务器上的一份新清单。 */
    suspend fun createList(body: SyncPayload): ApiResult<SyncCreateResult> = call {
        val text = json.encodeToString(SyncPayload.serializer(), body)
        decode(post("/api/sync/lists", text))
    }

    /* ---------------- 请求封装 ---------------- */

    private fun get(path: String): String = send(
        Request.Builder().url(url(path)).get(),
    )

    private fun put(path: String, body: String): String = send(
        Request.Builder().url(url(path)).put(body.toRequestBody(jsonType)),
    )

    private fun post(path: String, body: String): String = send(
        Request.Builder().url(url(path)).post(body.toRequestBody(jsonType)),
    )

    private fun send(builder: Request.Builder): String {
        val request = builder
            .header("Authorization", "Bearer ${token()}")
            .header("X-List-Id", "")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SyncHttpException(response.code, describe(response.code, text))
            }
            // 返回网页而不是数据：旧版后端没有这个接口，被前端兜底路由接住、发回了首页。
            // 直接抛 JSON 解析错的话，用户看到的是一串 "Unexpected JSON token..."，根本看不出该干嘛
            if (text.trimStart().startsWith("<")) {
                throw SyncHttpException(
                    code = 426,
                    message = "服务器上还是旧版，没有同步功能 —— 先把服务器升级到最新版再试",
                )
            }
            return text
        }
    }

    private fun url(path: String): String = serverUrl().trimEnd('/') + path

    /** 后端错误统一是 `{"detail": ...}`；422 时 detail 是数组。 */
    private fun describe(code: Int, body: String): String {
        val detail = runCatching {
            val element = json.parseToJsonElement(body)
            (element as? JsonObject)?.get("detail")
        }.getOrNull()
        val text = when (detail) {
            is JsonObject -> detail["message"]?.jsonPrimitive?.content
            else -> detail?.jsonPrimitive?.content
        }
        return text?.ifBlank { null } ?: when (code) {
            401 -> "账号或密码不对"
            404 -> "服务器上找不到这份清单，可能已被删除"
            409 -> "服务器上这份清单在你上次同步之后也改过"
            422 -> "提交的数据格式不正确"
            else -> "请求失败（$code）"
        }
    }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    private inline fun <reified T> decodeList(text: String): List<T> = json.decodeFromString(text)
}

/** 服务器返回了非 2xx。 */
class SyncHttpException(val code: Int, message: String) : Exception(message)

/** 需要登录的调用失败时统一成一句人话（与本地调用同一套结果类型）。 */
private suspend fun <T> call(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
    try {
        ApiResult.Ok(block())
    } catch (e: SyncHttpException) {
        ApiResult.Err(
            message = e.message ?: "请求被拒绝",
            code = e.code,
            hint = if (e.code == 401) "到「设置 → 服务器」里重新登录" else null,
        )
    } catch (e: IOException) {
        ApiResult.Err(
            message = "无法连接服务器",
            hint = "确认手机与服务器在同一网络，以及地址、端口是否正确",
        )
    } catch (e: Exception) {
        ApiResult.Err(e.message ?: "未知错误")
    }
}

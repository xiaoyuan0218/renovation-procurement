package com.xiaoyuan.renovation.data.repo

import com.xiaoyuan.renovation.data.remote.ApiClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.IOException

/** 统一的调用结果：把网络异常、业务错误、解析失败都收敛成一句人话。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>

    /** [hint] 是给用户看的下一步建议，没有则为空。 */
    data class Err(val message: String, val code: Int? = null, val hint: String? = null) : ApiResult<Nothing>
}

/** 拿成功值；失败返回 null（配合上面 already-known 的错误分支使用）。 */
val <T> ApiResult<T>.okData: T? get() = (this as? ApiResult.Ok<T>)?.data

inline fun <T> ApiResult<T>.onOk(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) block(data)
    return this
}

inline fun <T> ApiResult<T>.onErr(block: (ApiResult.Err) -> Unit): ApiResult<T> {
    if (this is ApiResult.Err) block(this)
    return this
}

/** 把一次 API 调用包起来，任何异常都不会冒泡到 UI。 */
suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
    try {
        ApiResult.Ok(block())
    } catch (e: HttpException) {
        ApiResult.Err(parseHttpError(e), e.code())
    } catch (e: IOException) {
        ApiResult.Err(
            message = "无法连接服务器",
            hint = "确认手机与服务器在同一网络，以及地址、端口是否正确",
        )
    } catch (e: kotlinx.serialization.SerializationException) {
        ApiResult.Err(
            message = "服务器返回的数据无法识别",
            hint = "这个地址可能不是采购清单后端（比如返回的是网页）",
        )
    } catch (e: Exception) {
        ApiResult.Err(e.message ?: "未知错误")
    }
}

/** 后端错误统一是 `{"detail": ...}`；422 时 detail 是数组。 */
private fun parseHttpError(e: HttpException): String {
    val raw = try {
        e.response()?.errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    val detail = raw?.let { body ->
        try {
            val element = ApiClientFactory.json.parseToJsonElement(body)
            (element as? JsonObject)?.get("detail")
        } catch (_: Exception) {
            null
        }
    }
    return when (detail) {
        is JsonPrimitive -> detail.content.ifBlank { fallback(e.code()) }
        is JsonArray -> detail.mapNotNull { item ->
            (item as? JsonObject)?.get("msg")?.jsonPrimitive?.content
        }.joinToString("；").ifBlank { fallback(e.code()) }
        else -> fallback(e.code())
    }
}

private fun fallback(code: Int): String = when (code) {
    400 -> "请求被拒绝（400）"
    404 -> "接口不存在（404），服务器版本可能不匹配"
    422 -> "提交的数据格式不正确（422）"
    500 -> "服务器内部错误（500）"
    else -> "请求失败（$code）"
}

/** 导出/模板下载：把响应体读成字节，同时取出服务端给的文件名。 */
data class DownloadedFile(val fileName: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadedFile) return false
        return fileName == other.fileName && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}

/**
 * 从 RFC 5987 的 `Content-Disposition` 里取文件名：
 * `attachment; filename*=UTF-8''%E8%A3%85%E4%BF%AE.xlsx`
 */
fun parseFileName(disposition: String?, fallbackName: String): String {
    if (disposition.isNullOrBlank()) return fallbackName
    val utf8 = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)
    if (utf8 != null) {
        return try {
            java.net.URLDecoder.decode(utf8.groupValues[1].trim(), "UTF-8")
        } catch (_: Exception) {
            fallbackName
        }
    }
    val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)
    return plain?.groupValues?.getOrNull(1)?.trim() ?: fallbackName
}

fun ResponseBody.contentTypeIsJson(): Boolean =
    contentType()?.subtype?.contains("json", ignoreCase = true) == true

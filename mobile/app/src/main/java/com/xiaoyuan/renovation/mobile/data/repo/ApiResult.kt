package com.xiaoyuan.renovation.mobile.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的调用结果。
 *
 * 与网络版客户端保持同名同形，界面层才能在两版之间照搬；本地读写没有 HTTP，
 * 所以 [Err] 里只有一句人话（[code] 留给四期连服务器时用）。
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>

    /** [hint] 是给用户看的下一步建议，没有则为空。 */
    data class Err(val message: String, val code: Int? = null, val hint: String? = null) :
        ApiResult<Nothing>
}

/** 拿成功值；失败返回 null。 */
val <T> ApiResult<T>.okData: T? get() = (this as? ApiResult.Ok<T>)?.data

inline fun <T> ApiResult<T>.onOk(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) block(data)
    return this
}

inline fun <T> ApiResult<T>.onErr(block: (ApiResult.Err) -> Unit): ApiResult<T> {
    if (this is ApiResult.Err) block(this)
    return this
}

/** 把一次本地库读写包起来，任何异常都不会冒泡到界面。 */
suspend fun <T> localCall(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
    try {
        ApiResult.Ok(block())
    } catch (e: IllegalArgumentException) {
        ApiResult.Err(e.message ?: "输入不合法")
    } catch (e: Exception) {
        ApiResult.Err(e.message ?: "操作失败")
    }
}

package com.xiaoyuan.renovation.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 给每个请求带上登录凭证。
 *
 * token 由 [com.xiaoyuan.renovation.data.prefs.SettingsStore] 以 StateFlow 暴露，
 * 这里同步读取 —— 拦截器跑在 OkHttp 的线程上，不能挂起。
 * 还没登录时 provider 返回 null，请求照常发出（登录接口本身也不需要 token）。
 */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build(),
        )
    }
}

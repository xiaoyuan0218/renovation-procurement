package com.xiaoyuan.renovation.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把请求的 scheme/host/port 换成当前配置的服务器地址。
 *
 * Retrofit 需要一个编译期 baseUrl，而本项目后端地址由用户在运行时决定，
 * 所以固定用一个占位地址，真正发请求前再逐条重写 ——
 * 这样改地址不需要重建 Retrofit，也不用重启 App。
 */
class DynamicHostInterceptor(private val baseUrlProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val configured = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)

        val base = configured.toHttpUrlOrNull() ?: return chain.proceed(request)

        val prefix = base.encodedPath.trimEnd('/')
        val target = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .apply {
                val path = request.url.encodedPath
                if (prefix.isNotEmpty() && !path.startsWith("$prefix/")) {
                    encodedPath("$prefix$path")
                }
            }
            .build()

        return chain.proceed(request.newBuilder().url(target).build())
    }
}

package com.xiaoyuan.renovation.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 给每个业务请求带上当前清单。
 *
 * 清单之间是隔离的，后端靠这个头决定这次要动哪一份；不带就落到第一份清单。
 * 登录、探活这类与清单无关的接口带上也无妨（后端不看）。
 *
 * 和 [AuthInterceptor] 一样同步读取 —— 拦截器跑在 OkHttp 的线程上，不能挂起。
 */
class ListInterceptor(private val listIdProvider: () -> Int?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val id = listIdProvider() ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder()
                .header("X-List-Id", id.toString())
                .build(),
        )
    }
}

package com.xiaoyuan.renovation.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ApiClientFactory {

    /** 占位地址，真实地址由 [DynamicHostInterceptor] 在发请求前替换。 */
    private const val PLACEHOLDER_BASE_URL = "http://placeholder.invalid/"

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = true
        encodeDefaults = true
    }

    /**
     * [tokenProvider] 不传就是匿名客户端 —— 「测试连接」在登录前就要用它探测地址，
     * 那时还没有 token。
     */
    fun create(
        baseUrlProvider: () -> String?,
        tokenProvider: () -> String? = { null },
        listIdProvider: () -> Int? = { null },
    ): ApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor(DynamicHostInterceptor(baseUrlProvider))
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(ListInterceptor(listIdProvider))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    /** 导入导出体积较大，用更宽松的超时。 */
    fun createForTransfer(
        baseUrlProvider: () -> String?,
        tokenProvider: () -> String? = { null },
        listIdProvider: () -> Int? = { null },
    ): ApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor(DynamicHostInterceptor(baseUrlProvider))
            // 导出的 xlsx 与导入模板也要带凭证，否则会 401
            .addInterceptor(AuthInterceptor(tokenProvider))
            // 导出/导入同样只作用于当前清单
            .addInterceptor(ListInterceptor(listIdProvider))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}

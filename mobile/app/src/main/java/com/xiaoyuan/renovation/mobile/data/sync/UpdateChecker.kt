package com.xiaoyuan.renovation.mobile.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「检查更新」：问 GitHub 上最新那批构建是什么版本，跟这台手机上装的比。
 *
 * 比版本号而不是比时间：滚动 release 的 tag 永远是 latest，拿不到版本，
 * 所以版本号写在发布说明里（见 .github/workflows/apk.yml 的 notes），这里抠出来。
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/xiaoyuan0218/renovation-procurement/releases/tags/latest"

    data class Result(val latest: String, val current: String) {
        val hasNew: Boolean get() = compareVersions(latest, current) > 0
    }

    /** 装在手机上的版本；debug 包会带 -debug 后缀，比之前先剥掉 */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("").substringBefore("-")

    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub 返回 ${response.code}")
            val body = response.body?.string().orEmpty()
            val found = VERSION_RE.find(body)
                ?: error("读不到远端的版本号，多半是发布说明还没更新，过一会儿再试")
            val current = currentVersion(context)
            if (current.isEmpty()) error("读不到本机版本号")
            Result(latest = found.groupValues[1], current = current)
        }
    }

    private val VERSION_RE = Regex("""\*\*版本\s*([0-9][0-9.]*)\*\*""")

    /** 逐段比大小：1.10.0 比 1.9.0 新，字符串直接比会得出反的结论 */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}

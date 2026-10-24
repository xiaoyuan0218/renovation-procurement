package com.xiaoyuan.renovation.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 商品链接的外跳入口：交给系统浏览器打开。 */
object UrlOpener {

    // 与后端 extract_link、mobile extractLink 是同一份提取规范，三端正则与尾剥表逐字同一。
    // $ 写成 ${'$'}：字符串模板会把 $& 当成模板引用处理，$ 后跟非标识符时直接编译不过
    private val URL_CANDIDATE = Regex(
        "https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!${'$'}&'()*+,;=%]+",
        RegexOption.IGNORE_CASE,
    )

    // host 段非空：'https://'、'https://...' 这类空 host 靠 scheme 前缀拦不住，
    // 而展示端按 http/https 前缀就能点开，必须在提取时就一起拦掉。
    // 只做前缀匹配，匹配语义由使用处的 containsMatchIn 决定
    private val HAS_HOST = Regex("^https?://[^/?#]*[A-Za-z0-9]", RegexOption.IGNORE_CASE)

    /** URL 不能截断（截了就坏），超过后端入库上限就整个放弃。 */
    private const val MAX_LINK_LENGTH = 500

    /** 认得出的 http/https 才算商品链接（伪协议交给系统是执行而不是浏览）。 */
    fun isHttp(url: String?): Boolean {
        val text = url?.trim().orEmpty()
        return text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
    }

    /**
     * 从整段分享文案里提取第一个 http(s) 链接，没有就返回空串。
     * 对已提取的纯 URL 幂等，scheme 大小写保留原样。
     */
    fun extractHttp(value: String?): String {
        // String.trim 连首尾全角空格（U+3000）一起去掉；文案中间的全角空格不在
        // 正则字符集里，会自然截断
        val text = value.orEmpty().trim()
        val candidate = URL_CANDIDATE.find(text)?.value ?: return ""

        // 尾部标点逐个剥：普通标点直接剥；收括号只在开括号出现得更少（说明它是
        // 文案的包裹符而非 URL 的一部分）时才剥，否则 'a_(page))' 会被剥坏成 'a_(page'。
        // '}' 实际进不了候选串（正则字符集不含它），留着只为三端尾剥表逐字同一
        var url = candidate
        while (url.isNotEmpty()) {
            val drop = when (url.last()) {
                '.', ',', ';', ':', '!', '?', '\'', '"' -> true
                ')' -> url.count { it == '(' } < url.count { it == ')' }
                ']' -> url.count { it == '[' } < url.count { it == ']' }
                '}' -> url.count { it == '{' } < url.count { it == '}' }
                else -> false
            }
            if (!drop) break
            url = url.dropLast(1)
        }

        if (!isHttp(url)) return ""
        // 前缀匹配而非整串匹配：host 校验只要求 scheme:// 到第一个 /?# 之间
        // 有字母数字，后面的路径由正则里 ^ 锚定保证不越界
        if (!HAS_HOST.containsMatchIn(url)) return ""
        return if (url.length > MAX_LINK_LENGTH) "" else url
    }

    /**
     * 打开一个 http/https 网址。返回 false 交给调用方提示，
     * 别让界面以为已经跳走了。
     */
    fun open(context: Context, url: String?): Boolean {
        val text = url?.trim().orEmpty()
        if (!isHttp(text)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(text))
            // 从非 Activity 上下文启动 Activity 必须带 NEW_TASK（与 FileUtils.share 同理）
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

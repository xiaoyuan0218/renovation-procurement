package com.xiaoyuan.renovation.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 商品链接的外跳入口：交给系统浏览器打开。 */
object UrlOpener {

    /** 认得出的 http/https 才算商品链接（伪协议交给系统是执行而不是浏览）。 */
    fun isHttp(url: String?): Boolean {
        val text = url?.trim().orEmpty()
        return text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
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

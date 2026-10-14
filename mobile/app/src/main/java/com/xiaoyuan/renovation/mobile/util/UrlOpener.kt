package com.xiaoyuan.renovation.mobile.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 商品链接的外跳入口：交给系统浏览器打开。 */
object UrlOpener {

    /**
     * 打开一个 http/https 网址。
     *
     * 别的前缀（javascript:、intent: 之类）一律拒绝：它们交给系统是执行而不是浏览，
     * 链接来自用户填的表格/同步数据，不能原样往外递。返回 false 交给调用方提示，
     * 别让界面以为已经跳走了。
     */
    fun open(context: Context, url: String?): Boolean {
        val text = url?.trim().orEmpty()
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
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

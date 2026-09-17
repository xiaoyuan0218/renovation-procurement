package com.xiaoyuan.renovation.mobile.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

object FileUtils {

    /** 从 SAF 返回的 Uri 里取用户看到的文件名。 */
    fun displayName(context: Context, uri: Uri, fallback: String = "导入文件.xlsx"): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: fallback
    }

    fun readBytes(context: Context, uri: Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    /** 把下载下来的文件写进缓存目录，再用 FileProvider 交给系统分享/保存。 */
    fun writeToExports(context: Context, fileName: String, bytes: ByteArray): Pair<File, Uri>? {
        return runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            file to uri
        }.getOrNull()
    }

    /**
     * 唤起系统分享面板。
     *
     * 注意这里拿到的是 Application context（ViewModel 里没有 Activity），
     * 从非 Activity 上下文启动 Activity **必须**带 FLAG_ACTIVITY_NEW_TASK，
     * 否则抛 AndroidRuntimeException —— 表现就是点"导出数据"直接闪退。
     *
     * 返回 false 表示没有应用能接收，交给调用方提示，不要让整个 App 挂掉。
     */
    fun share(context: Context, uri: Uri, mime: String, title: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // createChooser 只迁移 URI 授权相关的 flag，不会带上 NEW_TASK，
        // 而 ACTION_SEND 走的是 EXTRA_STREAM、没有 ClipData，所以授权 flag 也要补一次
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

package com.xiaoyuan.renovation.util

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

    fun share(context: Context, uri: Uri, mime: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}

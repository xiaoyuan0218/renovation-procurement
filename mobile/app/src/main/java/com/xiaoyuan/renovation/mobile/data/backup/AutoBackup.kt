package com.xiaoyuan.renovation.mobile.data.backup

import android.content.Context
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.xlsx.SheetMapping
import com.xiaoyuan.renovation.mobile.data.xlsx.Xlsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * 单机版每日自动本地备份。
 *
 * 单机版的账只存在本机 Room 库里，手动备份靠用户记得每天导 xlsx —— 手机坏了、
 * 库坏了、误导入覆盖了，没导过的日子就全没了。这里每天进程第一次启动时把
 * **所有清单**各导一份 xlsx 到应用私有目录（filesDir/auto-backups/），每份清单
 * 每天最多一份（当天已有就复用），各自保留最近 [KEEP] 份。
 *
 * 导 xlsx 而不是拷库文件：minSdk 26 的 SQLite（3.18）没有 VACUUM INTO，直接
 * 拷正在运行的库文件可能拿到写了一半的中间状态；xlsx 走的是「数据备份」页
 * 手动导出的同一条代码路径（Xlsx + SheetMapping），导回来一分不差，且格式与
 * 网页版互通。注意私有目录卸载即清空、手机丢了也拿不到 —— 它兜的是坏库与误
 * 操作，重要节点用户仍应从备份页把文件分享出去。
 */
object AutoBackup {

    /** 每份清单保留最近几份：一天一份，一周的量足够翻旧账。 */
    const val KEEP = 7

    private const val PREFIX = "自动备份_"

    fun dir(context: Context): File = File(context.filesDir, "auto-backups")

    /** 备份页展示用：文件名尾部日期等宽，倒序即新→旧。 */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension == "xlsx" }
            ?.sortedByDescending { it.name } ?: emptyList()

    /**
     * 确保今天每份清单都有一份备份。幂等：当天的文件已存在就直接复用。
     * 单个清单失败不挡其他清单；全部做完后有任何失败就整体报错，调用方记日志。
     */
    suspend fun runOnce(context: Context, db: AppDatabase): Result<Unit> =
        withContext(Dispatchers.IO) {
            val outDir = dir(context).apply { mkdirs() }
            val today = LocalDate.now()
            val errors = mutableListOf<String>()
            for (list in db.lists().all()) {
                try {
                    val target = File(outDir, "$PREFIX${list.name}_$today.xlsx")
                    if (target.isFile && target.length() > 0) continue
                    val bytes = Xlsx.write(SheetMapping.toSheets(db, list.id))
                    // 先写临时名再改名：写到一半进程被杀，也不会留下一个看着
                    // 像备份、其实不完整的文件被当成今天的备份复用
                    val tmp = File(outDir, "${target.name}.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        error("无法保存 ${target.name}")
                    }
                } catch (e: Exception) {
                    errors.add("${list.name}：${e.message ?: "导出失败"}")
                }
            }
            rotate(outDir)
            if (errors.isEmpty()) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(errors.joinToString("；")))
            }
        }

    /** 按清单前缀分组轮转：每组保留最近 [KEEP] 份，更旧的删掉（删不掉就算了）。 */
    private fun rotate(outDir: File) {
        outDir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.extension == "xlsx" }
            ?.groupBy { it.name.substringBeforeLast('_') }
            ?.forEach { (_, files) ->
                files.sortedBy { it.name }.dropLast(KEEP).forEach { f ->
                    runCatching { f.delete() }
                }
            }
    }
}

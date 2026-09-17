package com.xiaoyuan.renovation.mobile.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xiaoyuan.renovation.mobile.data.xlsx.ImportReport
import com.xiaoyuan.renovation.mobile.data.xlsx.SheetMapping
import com.xiaoyuan.renovation.mobile.data.xlsx.Xlsx
import com.xiaoyuan.renovation.mobile.di.AppContainer
import com.xiaoyuan.renovation.mobile.ui.design.ChoiceChips
import com.xiaoyuan.renovation.mobile.ui.design.GhostButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassCard
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.KeyValueRow
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

private val MODES = listOf(
    "replace" to "覆盖现有物料",
    "merge" to "按名称合并",
)

/**
 * 数据备份：把当前清单导成 xlsx，或者从 xlsx 导回来。
 *
 * 格式与网页版、网络版**完全一致**，所以手机上导出的表电脑上能直接导进网页版，
 * 反过来电脑导出的表手机也认（`XlsxTest` 里拿后端真跑出来的文件对过）。
 * 比网页版多认「采购记录」和「额外费用」两页 —— 手机导出再导入应该一分不差，
 * 不然拿它当备份就会丢账。
 */
@Composable
fun BackupPage(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var mode by remember { mutableStateOf("replace") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("读不到这个文件")
                }
                val sheets = withContext(Dispatchers.IO) { Xlsx.read(bytes) }
                val listId = container.currentList.require()
                val result = SheetMapping.fromSheets(container.db, listId, sheets, mode)
                container.bumpDataVersion()
                report = result
                message = "导入完成"
            } catch (e: Exception) {
                message = e.message ?: "导入失败"
            }
            busy = false
        }
    }

    SettingsPage(
        title = "数据备份",
        caption = "导出的表格文件，在电脑上也读得进来",
        onBack = onBack,
    ) {
        item {
            GlassCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = "导出当前清单",
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val listId = container.currentList.require()
                                    val name = container.db.lists().byId(listId)?.name.orEmpty()
                                    val bytes = withContext(Dispatchers.IO) {
                                        Xlsx.write(SheetMapping.toSheets(container.db, listId))
                                    }
                                    val file = withContext(Dispatchers.IO) {
                                        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val stamp = LocalDateTime.now().format(STAMP)
                                        File(dir, "采知道_${name}_$stamp.xlsx")
                                            .apply { writeBytes(bytes) }
                                    }
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "导出采知道"))
                                    message = "已导出，选个地方保存就行"
                                } catch (e: Exception) {
                                    message = e.message ?: "导出失败"
                                }
                                busy = false
                            }
                        },
                        icon = Icons.Filled.CloudDownload,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                HintText("导出后会弹出分享面板，可以存到文件、发到微信或云盘。这一份表在电脑上也能导进网页版。")
            }
        }

        item {
            GlassCard {
                Text(
                    text = "导入方式",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                ChoiceChips(
                    options = MODES,
                    selected = mode,
                    onSelect = { mode = it },
                    accent = Ink.Indigo,
                )
                Spacer(Modifier.height(6.dp))
                HintText(
                    if (mode == "replace") {
                        "覆盖：先清空本清单的物料、采购记录与费用，再按表格重建。"
                    } else {
                        "合并：按物料名称匹配，已有的更新，新的追加。"
                    },
                )
                Spacer(Modifier.height(14.dp))
                NeonButton(
                    text = "选择表格文件并导入",
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    icon = Icons.Filled.UploadFile,
                    enabled = !busy,
                    loading = busy,
                    gradient = Ink.IndigoGradient,
                    fillWidth = true,
                )
            }
        }

        message?.let { text -> item { HintText(text) } }

        report?.let { r ->
            item {
                GlassCard {
                    Text(
                        text = "导入报告",
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink.TextPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("方式", if (r.mode == "merge") "按名称合并" else "覆盖")
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("新建物料", "${r.itemsCreated} 项")
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("匹配到已有物料", "${r.itemsMatched} 项")
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("布点明细", "${r.allocations} 条")
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("采购记录", "${r.records} 条")
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("额外费用", "${r.expenses} 笔")
                    if (r.roomsCreated + r.categoriesCreated > 0) {
                        Spacer(Modifier.height(6.dp))
                        KeyValueRow(
                            "补建的分组/分类",
                            "${r.roomsCreated} 个 / ${r.categoriesCreated} 个",
                        )
                    }
                    if (r.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        r.warnings.take(5).forEach { HintText("· $it") }
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton(
                        text = "知道了",
                        onClick = { report = null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            HintText(
                "表格里「物料汇总」和「布点明细」是必填的两页；" +
                    "采购记录与额外费用是这台手机多认的，从网页版导出的表里没有也不影响。",
            )
        }
    }
}

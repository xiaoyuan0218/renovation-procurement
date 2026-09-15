package com.xiaoyuan.renovation.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.di.AppContainer
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.ui.common.containerViewModel
import com.xiaoyuan.renovation.ui.design.AppTextField
import com.xiaoyuan.renovation.ui.design.BrandHeader
import com.xiaoyuan.renovation.ui.design.ChoiceChips
import com.xiaoyuan.renovation.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.ui.design.ErrorState
import com.xiaoyuan.renovation.ui.design.GhostButton
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.design.GlassDivider
import com.xiaoyuan.renovation.ui.design.GlassIconButton
import com.xiaoyuan.renovation.ui.design.HintText
import com.xiaoyuan.renovation.ui.design.InfoDialog
import com.xiaoyuan.renovation.ui.design.InlineBanner
import com.xiaoyuan.renovation.ui.design.KeyValueRow
import com.xiaoyuan.renovation.ui.design.LoadingState
import com.xiaoyuan.renovation.ui.design.NeonButton
import com.xiaoyuan.renovation.ui.design.SectionTitle
import com.xiaoyuan.renovation.ui.design.TagPill
import com.xiaoyuan.renovation.ui.design.TextPromptDialog
import com.xiaoyuan.renovation.ui.theme.Ink
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    container: AppContainer,
    refreshKey: Int,
    onEditServerAddress: () -> Unit,
) {
    val vm = containerViewModel(container) { c ->
        SettingsViewModel(c.repo, c.settings, c.appContextRef)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val baseUrl by vm.baseUrl.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importMode by vm.importMode.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val connectionOk by vm.connectionOk.collectAsStateWithLifecycle()

    var newRoom by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }
    var renamingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var renamingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    LaunchedEffect(refreshKey) { vm.load() }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2800)
            vm.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        BrandHeader(
            title = "设置",
            subtitle = "服务器 · 房间 · 类目 · 数据备份",
            trailing = {
                GlassIconButton(icon = Icons.Filled.Refresh, onClick = vm::load, contentDescription = "刷新")
            },
        )

        if (message != null) {
            InlineBanner(
                text = message!!,
                accent = if (connectionOk == false) Ink.DangerSoft else Ink.Mint,
            )
        }

        /* ---------- 服务器 ---------- */
        Column {
            SectionTitle("服务器", caption = "地址随时可改，改完立即生效")
            Spacer(Modifier.height(10.dp))
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionOk) {
                                    true -> Ink.Mint
                                    false -> Ink.DangerSoft
                                    null -> Ink.TextMuted
                                },
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = baseUrl ?: "未配置",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink.Blue,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = "修改地址",
                        onClick = onEditServerAddress,
                        icon = Icons.Filled.Dns,
                    )
                    GhostButton(
                        text = if (busy) "测试中…" else "测试连接",
                        onClick = vm::testConnection,
                        icon = Icons.Filled.MonitorHeart,
                        enabled = !busy,
                    )
                }
            }
        }

        when (val current = state) {
            is LoadState.Loading -> LoadingState(text = "正在读取房间与类目…")

            is LoadState.Failed -> ErrorState(
                message = current.message,
                serverUrl = baseUrl,
                hint = current.hint,
                onRetry = vm::load,
                onOpenSettings = onEditServerAddress,
            )

            is LoadState.Ready -> {
                val data = current.data

                /* ---------- 房间 ---------- */
                Column {
                    SectionTitle("房间", caption = "共 ${data.rooms.size} 个 · 删除会同时清掉该房间的布点")
                    Spacer(Modifier.height(10.dp))
                    GlassCard {
                        Row(verticalAlignment = Alignment.Bottom) {
                            AppTextField(
                                value = newRoom,
                                onValueChange = { newRoom = it },
                                label = "新增房间",
                                placeholder = "例如：书房",
                                imeAction = ImeAction.Done,
                                modifier = Modifier.weight(1f),
                                onDone = {
                                    vm.addRoom(newRoom)
                                    newRoom = ""
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            NeonButton(
                                text = "添加",
                                onClick = {
                                    vm.addRoom(newRoom)
                                    newRoom = ""
                                },
                                icon = Icons.Filled.Add,
                                enabled = newRoom.isNotBlank() && !busy,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GlassCard(padding = 0.dp) {
                        data.rooms.forEachIndexed { index, room ->
                            if (index > 0) GlassDivider(Modifier.padding(horizontal = 14.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = room.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Ink.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                TagPill(
                                    text = "改名",
                                    color = Ink.Indigo,
                                    onClick = { renamingRoom = room.id to room.name },
                                )
                                Spacer(Modifier.width(8.dp))
                                TagPill(
                                    text = "删除",
                                    color = Ink.DangerSoft,
                                    onClick = { deletingRoom = room.id to room.name },
                                )
                            }
                        }
                    }
                }

                /* ---------- 类目 ---------- */
                Column {
                    SectionTitle("类目", caption = "共 ${data.categories.size} 个 · 类目下还有物料时不能删除")
                    Spacer(Modifier.height(10.dp))
                    GlassCard {
                        Row(verticalAlignment = Alignment.Bottom) {
                            AppTextField(
                                value = newCategory,
                                onValueChange = { newCategory = it },
                                label = "新增类目",
                                placeholder = "例如：五金",
                                imeAction = ImeAction.Done,
                                accent = Ink.Indigo,
                                modifier = Modifier.weight(1f),
                                onDone = {
                                    vm.addCategory(newCategory)
                                    newCategory = ""
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            NeonButton(
                                text = "添加",
                                onClick = {
                                    vm.addCategory(newCategory)
                                    newCategory = ""
                                },
                                icon = Icons.Filled.Add,
                                enabled = newCategory.isNotBlank() && !busy,
                                gradient = Ink.IndigoGradient,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    GlassCard(padding = 0.dp) {
                        data.categories.forEachIndexed { index, category ->
                            if (index > 0) GlassDivider(Modifier.padding(horizontal = 14.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Ink.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                TagPill(
                                    text = "改名",
                                    color = Ink.Indigo,
                                    onClick = { renamingCategory = category.id to category.name },
                                )
                                Spacer(Modifier.width(8.dp))
                                TagPill(
                                    text = "删除",
                                    color = Ink.DangerSoft,
                                    onClick = { deletingCategory = category.id to category.name },
                                )
                            }
                        }
                    }
                }
            }
        }

        /* ---------- 数据备份 ---------- */
        Column {
            SectionTitle("数据备份", caption = "xlsx 与网页版共用同一套格式")
            Spacer(Modifier.height(10.dp))
            GlassCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = "导出数据",
                        onClick = vm::exportToShare,
                        icon = Icons.Filled.CloudDownload,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        text = "下载模板",
                        onClick = vm::downloadTemplateToShare,
                        icon = Icons.Filled.CloudUpload,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                HintText("导出后会弹出系统分享面板，可以存到文件、发到微信或云盘。")

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "导入方式",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                ChoiceChips(
                    options = ImportMode.entries.map { it to it.label },
                    selected = importMode,
                    onSelect = vm::setImportMode,
                    accent = Ink.Indigo,
                )
                Spacer(Modifier.height(6.dp))
                HintText(
                    when (importMode) {
                        ImportMode.Replace -> "覆盖：先清空现有物料，再按表格重建。"
                        ImportMode.Merge -> "合并：按物料名称匹配，已存在的更新，新的追加。"
                    },
                )
                Spacer(Modifier.height(14.dp))
                NeonButton(
                    text = "选择 xlsx 并导入",
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    icon = Icons.Filled.UploadFile,
                    enabled = !busy,
                    loading = busy,
                    gradient = Ink.IndigoGradient,
                    fillWidth = true,
                )
            }
        }

        /* ---------- 关于 ---------- */
        Column {
            SectionTitle("关于")
            Spacer(Modifier.height(10.dp))
            GlassCard {
                KeyValueRow("应用", "装修采购 Android 1.0.0")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("数据存放", "服务器的 SQLite 文件")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("登录", "无需登录")
                Spacer(Modifier.height(10.dp))
                HintText("这个 App 只是服务器上那份数据的另一个入口，手机上不存业务数据。")
            }
        }
    }

    /* ---------- 弹窗 ---------- */
    renamingRoom?.let { (id, name) ->
        TextPromptDialog(
            title = "房间改名",
            initialValue = name,
            onConfirm = { newName ->
                vm.renameRoom(id, newName)
                renamingRoom = null
            },
            onDismiss = { renamingRoom = null },
        )
    }

    renamingCategory?.let { (id, name) ->
        TextPromptDialog(
            title = "类目改名",
            initialValue = name,
            onConfirm = { newName ->
                vm.renameCategory(id, newName)
                renamingCategory = null
            },
            onDismiss = { renamingCategory = null },
        )
    }

    deletingRoom?.let { (id, name) ->
        ConfirmDialog(
            title = "删除房间",
            message = "「$name」的布点会一并删除，物料本身不受影响。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                deletingRoom = null
                vm.deleteRoom(id)
            },
            onDismiss = { deletingRoom = null },
        )
    }

    deletingCategory?.let { (id, name) ->
        ConfirmDialog(
            title = "删除类目",
            message = "如果「$name」下还有物料，服务器会拒绝删除。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                deletingCategory = null
                vm.deleteCategory(id)
            },
            onDismiss = { deletingCategory = null },
        )
    }

    report?.let { r ->
        InfoDialog(title = "导入完成", onDismiss = vm::consumeReport) {
            KeyValueRow("模式", if (r.mode == "merge") "按名称合并" else "覆盖")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("新建物料", "${r.itemsCreated} 项")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("匹配物料", "${r.itemsMatched} 项")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("布点", "${r.allocations} 条")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("采购记录", "${r.records} 条")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("新增房间", "${r.roomsCreated} 个")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("新增类目", "${r.categoriesCreated} 个")
            if (r.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "提示",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Amber,
                )
                Spacer(Modifier.height(4.dp))
                r.warnings.take(8).forEach { warning ->
                    Text(
                        text = "· $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextSecondary,
                    )
                    Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
}

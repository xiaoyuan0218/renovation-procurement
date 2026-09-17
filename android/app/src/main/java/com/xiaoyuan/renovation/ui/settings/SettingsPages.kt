package com.xiaoyuan.renovation.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.data.model.ExpenseDto
import com.xiaoyuan.renovation.data.model.TrashItemDto
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.ui.common.dataOrNull
import com.xiaoyuan.renovation.ui.design.ChoiceChips
import com.xiaoyuan.renovation.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.ui.design.EmptyState
import com.xiaoyuan.renovation.ui.design.ErrorState
import com.xiaoyuan.renovation.ui.design.GhostButton
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.design.GlassIconButton
import com.xiaoyuan.renovation.ui.design.GlassPanel
import com.xiaoyuan.renovation.ui.design.HintText
import com.xiaoyuan.renovation.ui.design.KeyValueRow
import com.xiaoyuan.renovation.ui.design.LoadingState
import com.xiaoyuan.renovation.ui.design.NeonButton
import com.xiaoyuan.renovation.ui.design.SectionTitle
import com.xiaoyuan.renovation.ui.design.TagPill
import com.xiaoyuan.renovation.ui.design.TextPromptDialog
import com.xiaoyuan.renovation.ui.lists.ListsViewModel
import com.xiaoyuan.renovation.ui.lists.NewListDialog
import com.xiaoyuan.renovation.ui.theme.Ink
import com.xiaoyuan.renovation.util.Fmt

/* ============================================================
   清单：切换、新建、改名、删除
   ============================================================ */

@Composable
fun ListsPage(listsVm: ListsViewModel, onBack: () -> Unit) {
    val lists by listsVm.lists.collectAsStateWithLifecycle()
    val currentId by listsVm.currentId.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { listsVm.load() }

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deleting by remember { mutableStateOf<Pair<Int, String>?>(null) }

    SettingsPage(
        title = "清单",
        caption = "每份清单的物料、分组、分类互相独立",
        onBack = onBack,
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HintText("点一下切换，右边两个按钮用来改名和删除", Modifier.weight(1f))
                GlassIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "新建清单",
                    tint = Ink.Cyan,
                    onClick = { creating = true },
                )
            }
        }

        if (lists.isEmpty()) {
            item { EmptyState(title = "还没有清单", hint = "点上面的加号建一份") }
        }

        items(lists, key = { it.id }) { list ->
            val isCurrent = list.id == currentId
            GlassCard(
                accent = if (isCurrent) Ink.Blue else null,
                onClick = { listsVm.select(list.id) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = list.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Ink.TextPrimary,
                            )
                            if (isCurrent) {
                                Spacer(Modifier.padding(horizontal = 3.dp))
                                TagPill("当前", color = Ink.Blue)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = list.code.ifBlank { "" }
                                .let { if (it.isEmpty()) "" else "$it · " } +
                                "${list.itemCount} 条物料 · ${list.roomCount} 个分组",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    GlassIconButton(
                        icon = Icons.Filled.DriveFileRenameOutline,
                        contentDescription = "改名",
                        onClick = { renaming = list.id to list.name },
                    )
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    GlassIconButton(
                        icon = Icons.Filled.DeleteOutline,
                        contentDescription = "删除",
                        tint = Ink.DangerSoft,
                        enabled = lists.size > 1,
                        onClick = { deleting = list.id to list.name },
                    )
                }
            }
        }

        item {
            HintText("删除清单会连它里面的物料、分组、分类一起删掉；最后一份不允许删。")
        }
    }

    if (creating) {
        NewListDialog(
            lists = lists,
            defaultSourceId = currentId,
            onConfirm = { name, copyFrom ->
                listsVm.create(name, copyFrom)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { (id, name) ->
        TextPromptDialog(
            title = "清单改名",
            initialValue = name,
            label = "清单名",
            onConfirm = { listsVm.rename(id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { (id, name) ->
        ConfirmDialog(
            title = "删除清单",
            message = "「$name」里的物料、分组、分类会一起删掉，不能恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = { listsVm.delete(id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/* ============================================================
   分组与分类
   ============================================================ */

@Composable
fun RoomsPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.dataOrNull

    LaunchedEffect(Unit) { vm.load() }

    var creatingRoom by remember { mutableStateOf(false) }
    var renamingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var renamingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }

    val rooms = data?.rooms.orEmpty()
    val categories = data?.categories.orEmpty()

    SettingsPage(
        title = "分组与分类",
        caption = "分组用于分配数量，分类用于归类物料",
        onBack = onBack,
    ) {
        when {
            state is LoadState.Loading -> item { LoadingState(text = "正在读取…") }

            state is LoadState.Failed -> item {
                ErrorState(
                    message = (state as LoadState.Failed).message,
                    hint = (state as LoadState.Failed).hint,
                    onRetry = vm::load,
                )
            }

            else -> {
                item {
                    SectionTitle(
                        "分组",
                        caption = "共 ${rooms.size} 个 · 删除会同时清掉该分组的分配",
                        trailing = {
                            GlassIconButton(
                                icon = Icons.Filled.Add,
                                contentDescription = "新增分组",
                                tint = Ink.Cyan,
                                onClick = { creatingRoom = true },
                            )
                        },
                    )
                }
                items(rooms, key = { "room-${it.id}" }) { room ->
                    SimpleRow(
                        name = room.name,
                        onRename = { renamingRoom = room.id to room.name },
                        onDelete = { deletingRoom = room.id to room.name },
                    )
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    SectionTitle(
                        "分类",
                        caption = "共 ${categories.size} 个 · 分类下还有物料时不能删除",
                        trailing = {
                            GlassIconButton(
                                icon = Icons.Filled.Add,
                                contentDescription = "新增分类",
                                tint = Ink.Cyan,
                                onClick = { creatingCategory = true },
                            )
                        },
                    )
                }
                items(categories, key = { "cat-${it.id}" }) { category ->
                    SimpleRow(
                        name = category.name,
                        onRename = { renamingCategory = category.id to category.name },
                        onDelete = { deletingCategory = category.id to category.name },
                    )
                }
            }
        }
    }

    if (creatingRoom) {
        TextPromptDialog(
            title = "新增分组",
            initialValue = "",
            label = "分组名",
            confirmText = "新增",
            onConfirm = { vm.addRoom(it); creatingRoom = false },
            onDismiss = { creatingRoom = false },
        )
    }
    renamingRoom?.let { (id, name) ->
        TextPromptDialog(
            title = "分组改名",
            initialValue = name,
            label = "分组名",
            onConfirm = { vm.renameRoom(id, it); renamingRoom = null },
            onDismiss = { renamingRoom = null },
        )
    }
    deletingRoom?.let { (id, name) ->
        ConfirmDialog(
            title = "删除分组",
            message = "「$name」下的分配数量会一起删掉；已经付过的钱会保留，只是不再算到某个分组头上。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteRoom(id); deletingRoom = null },
            onDismiss = { deletingRoom = null },
        )
    }

    if (creatingCategory) {
        TextPromptDialog(
            title = "新增分类",
            initialValue = "",
            label = "分类名",
            confirmText = "新增",
            onConfirm = { vm.addCategory(it); creatingCategory = false },
            onDismiss = { creatingCategory = false },
        )
    }
    renamingCategory?.let { (id, name) ->
        TextPromptDialog(
            title = "分类改名",
            initialValue = name,
            label = "分类名",
            onConfirm = { vm.renameCategory(id, it); renamingCategory = null },
            onDismiss = { renamingCategory = null },
        )
    }
    deletingCategory?.let { (id, _) ->
        ConfirmDialog(
            title = "删除分类",
            message = "下面还挂着物料时会删不掉，需要先把那些物料改成别的分类。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteCategory(id); deletingCategory = null },
            onDismiss = { deletingCategory = null },
        )
    }
}

@Composable
private fun SimpleRow(name: String, onRename: () -> Unit, onDelete: () -> Unit) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(
                icon = Icons.Filled.DriveFileRenameOutline,
                contentDescription = "改名",
                onClick = onRename,
            )
            Spacer(Modifier.padding(horizontal = 2.dp))
            GlassIconButton(
                icon = Icons.Filled.DeleteOutline,
                contentDescription = "删除",
                tint = Ink.DangerSoft,
                onClick = onDelete,
            )
        }
    }
}

/* ============================================================
   额外费用
   ============================================================ */

@Composable
fun ExpensesPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val data = state.dataOrNull
    val expenses = data?.expenses.orEmpty()

    LaunchedEffect(Unit) { vm.load() }

    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ExpenseDto?>(null) }

    SettingsPage(
        title = "额外费用",
        caption = "运费、安装费这类不进物料单价的支出，总览里单独汇总",
        onBack = onBack,
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HintText(
                    if (expenses.isEmpty()) {
                        "还没有额外费用"
                    } else {
                        "共 ${expenses.size} 笔 · 合计 ${Fmt.money(expenses.sumOf { it.amount })}"
                    },
                    Modifier.weight(1f),
                )
                GlassIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "记一笔费用",
                    tint = Ink.Cyan,
                    onClick = { creating = true },
                )
            }
        }

        if (expenses.isEmpty()) {
            item {
                EmptyState(
                    title = "还没有额外费用",
                    hint = "运费、安装费这类钱记在这里，不会混进物料的单价",
                )
            }
        }

        items(expenses, key = { it.id }) { expense ->
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = expense.kind,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink.TextPrimary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = expenseSubtitle(expense),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = Fmt.money(expense.amount),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink.TextPrimary,
                    )
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    GlassIconButton(
                        icon = Icons.Filled.DeleteOutline,
                        contentDescription = "删除",
                        tint = Ink.DangerSoft,
                        onClick = { deleting = expense },
                    )
                }
            }
        }

        item {
            HintText("这笔钱不参与原价与日常价的两个口径，是单独一笔账。")
        }
    }

    if (creating) {
        ExpenseSheet(
            items = data?.items.orEmpty(),
            usedKinds = expenses.map { it.kind }.distinct(),
            editing = null,
            busy = busy,
            onSave = { body ->
                vm.addExpense(body)
                creating = false
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }

    deleting?.let { expense ->
        ConfirmDialog(
            title = "删除这笔费用",
            message = "「${expense.kind} ${Fmt.money(expense.amount)}」删掉后不能恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteExpense(expense.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/* ============================================================
   回收站
   ============================================================ */

@Composable
fun TrashPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val trash = state.dataOrNull?.trash.orEmpty()

    LaunchedEffect(Unit) { vm.load() }

    var purging by remember { mutableStateOf<TrashItemDto?>(null) }
    var clearing by remember { mutableStateOf(false) }

    SettingsPage(
        title = "回收站",
        caption = "删掉的物料先放这里，捞得回来",
        onBack = onBack,
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HintText(if (trash.isEmpty()) "空的" else "共 ${trash.size} 条", Modifier.weight(1f))
                if (trash.isNotEmpty()) {
                    GlassIconButton(
                        icon = Icons.Filled.DeleteSweep,
                        contentDescription = "清空回收站",
                        tint = Ink.DangerSoft,
                        onClick = { clearing = true },
                    )
                }
            }
        }

        if (trash.isEmpty()) {
            item {
                EmptyState(
                    title = "回收站是空的",
                    hint = "平时删物料只是移到这儿，随时能恢复；在这里彻底删掉才是真的没了",
                )
            }
        }

        items(trash, key = { it.id }) { item ->
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink.TextPrimary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "删除于 ${item.deletedAt.take(16)} · 原价 ${Fmt.money(item.listTotal)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    GlassIconButton(
                        icon = Icons.Filled.Restore,
                        contentDescription = "恢复",
                        tint = Ink.Mint,
                        onClick = { vm.restoreItem(item.id) },
                    )
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    GlassIconButton(
                        icon = Icons.Filled.DeleteForever,
                        contentDescription = "彻底删除",
                        tint = Ink.DangerSoft,
                        onClick = { purging = item },
                    )
                }
            }
        }
    }

    purging?.let { item ->
        ConfirmDialog(
            title = "彻底删除",
            message = "「${item.name}」的采购记录与分配会一起删掉，不能恢复。",
            confirmText = "彻底删除",
            danger = true,
            onConfirm = { vm.purgeItem(item.id); purging = null },
            onDismiss = { purging = null },
        )
    }
    if (clearing) {
        ConfirmDialog(
            title = "清空回收站",
            message = "里面 ${trash.size} 条物料的采购记录与分配会一起删掉，不能恢复。",
            confirmText = "清空",
            danger = true,
            onConfirm = { vm.purgeTrash(); clearing = false },
            onDismiss = { clearing = false },
        )
    }
}

/* ============================================================
   数据备份：xlsx 导入导出（与网页版共用同一套格式）
   ============================================================ */

@Composable
fun BackupPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importMode by vm.importMode.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importFrom(uri) }

    SettingsPage(
        title = "数据备份",
        caption = "导出的表格文件，在电脑上也读得进来",
        onBack = onBack,
    ) {
        item {
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

        message?.let { text ->
            item { GlassCard(corner = 16.dp, padding = 14.dp) { HintText(text) } }
        }

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
                    if (r.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        r.warnings.forEach { HintText("· $it") }
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton(text = "知道了", onClick = vm::consumeReport, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/* ============================================================
   账号与服务器
   ============================================================ */

@Composable
fun AccountPage(
    vm: SettingsViewModel,
    onEditServerAddress: () -> Unit,
    onBack: () -> Unit,
) {
    val baseUrl by vm.baseUrl.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connectionOk by vm.connectionOk.collectAsStateWithLifecycle()
    val username by vm.username.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var confirmLogout by remember { mutableStateOf(false) }

    SettingsPage(title = "账号与服务器", caption = "换服务器地址、改密码都在网页版做", onBack = onBack) {
        item {
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
                Spacer(Modifier.height(10.dp))
                HintText("地址随时可改，改完立即生效。")
            }
        }

        message?.let { item { HintText(it) } }

        item {
            GlassCard {
                KeyValueRow("登录账号", username ?: "—")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("数据存放", "服务器上")
                Spacer(Modifier.height(10.dp))
                HintText("这个 App 只是服务器上那份数据的另一个入口，手机上不存业务数据。")
            }
        }

        item {
            GhostButton(
                text = "退出登录",
                onClick = { confirmLogout = true },
                icon = Icons.AutoMirrored.Filled.Logout,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录",
            message = "退出后需要重新输入密码才能查看数据。服务器地址会保留。",
            confirmText = "退出",
            danger = true,
            onConfirm = {
                confirmLogout = false
                vm.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

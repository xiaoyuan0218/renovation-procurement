package com.xiaoyuan.renovation.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.mobile.data.model.ExpenseDto
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import com.xiaoyuan.renovation.mobile.data.model.TrashItemDto
import com.xiaoyuan.renovation.mobile.ui.common.LoadState
import com.xiaoyuan.renovation.mobile.ui.common.dataOrNull
import com.xiaoyuan.renovation.mobile.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.mobile.ui.design.EmptyState
import com.xiaoyuan.renovation.mobile.ui.design.ErrorState
import com.xiaoyuan.renovation.mobile.ui.design.GlassCard
import com.xiaoyuan.renovation.mobile.ui.design.GlassIconButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassPanel
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.LoadingState
import com.xiaoyuan.renovation.mobile.ui.design.SectionTitle
import com.xiaoyuan.renovation.mobile.ui.design.TagPill
import com.xiaoyuan.renovation.mobile.ui.design.TextPromptDialog
import com.xiaoyuan.renovation.mobile.ui.lists.ListsViewModel
import com.xiaoyuan.renovation.mobile.ui.lists.NewListDialog
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import com.xiaoyuan.renovation.mobile.util.Fmt

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
                HintText("点一下切换，右下角两个按钮用来改名和删除", Modifier.weight(1f))
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

    LaunchedEffect(Unit) { vm.load() }

    var creatingRoom by remember { mutableStateOf(false) }
    var renamingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var renamingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }

    val data = state.dataOrNull
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
                        caption = "共 ${rooms.size} 个",
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
                        caption = "共 ${categories.size} 个",
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

                item {
                    HintText("删分组时它的分配会跟着删，但已经付过的钱会保留；分类下面还挂着物料时删不掉。")
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
            onConfirm = { vm.createRoom(it); creatingRoom = false },
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
            onConfirm = { vm.createCategory(it); creatingCategory = false },
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

    LaunchedEffect(Unit) { vm.load() }

    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ExpenseDto?>(null) }

    val expenses = data?.expenses.orEmpty()

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
                        "共 ${expenses.size} 笔 · 合计 ${Fmt.money(data?.expensesTotal ?: 0.0)}"
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
            GlassPanel(onClick = { editing = expense }) {
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
                        color = expenseAmountColor(expense.amount),
                    )
                }
            }
        }

        item {
            HintText("点一笔可以改金额或删掉。这笔钱不参与原价与日常价的两个口径，是单独一笔账。")
        }
    }

    if (creating || editing != null) {
        ExpenseSheet(
            items = data?.items.orEmpty(),
            usedKinds = expenses.map { it.kind }.distinct(),
            editing = editing,
            busy = busy,
            onSave = { body ->
                val target = editing
                if (target == null) vm.createExpense(body) else vm.updateExpense(target.id, body)
                creating = false
                editing = null
            },
            onDelete = editing?.let { target ->
                { vm.deleteExpense(target.id); editing = null }
            },
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }
}

/* ============================================================
   回收站
   ============================================================ */

@Composable
fun TrashPage(vm: SettingsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.dataOrNull
    val trash = data?.trash.orEmpty()

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
                HintText(
                    if (trash.isEmpty()) "空的" else "共 ${trash.size} 条",
                    Modifier.weight(1f),
                )
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
            onConfirm = { vm.purgeTrash(trash.size); clearing = false },
            onDismiss = { clearing = false },
        )
    }
}

/* ============================================================
   关于
   ============================================================ */

@Composable
fun AboutPage(onBack: () -> Unit) {
    SettingsPage(title = "关于", caption = "数据放在哪、怎么备份", onBack = onBack) {
        item {
            GlassPanel {
                Text(
                    text = "数据默认只在这台手机上",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                HintText(
                    "它存在这台手机上、只属于这个应用。卸载应用会一起删掉，" +
                        "所以重要的数据记得在「服务器」里传一份到电脑，或者用服务器的备份功能留个底。",
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TagPill("纯本地", color = Ink.Mint)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    TagPill("无需网络", color = Ink.Cyan)
                }
            }
        }

        item {
            GlassPanel {
                Text(
                    text = "连服务器有什么用",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                HintText(
                    "不连也能单独用。连上之后，这份清单可以传到服务器上（服务器会新建一份，" +
                        "不会动你原有的清单），电脑上打开网页就能看到、也能改；" +
                        "两边都改过时它会把各自的改动合起来，只有改到同一处才会问你以哪边为准。",
                )
            }
        }

        item {
            GlassPanel {
                Text(
                    text = "金额口径",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                HintText(
                    "原价、日常价两套口径各自拆成「已付 + 优惠 + 未付」三段，和电脑上算的分文不差 ——" +
                        "手机上算出来的每一个数，和电脑上显示的完全一样。",
                )
            }
        }
    }
}

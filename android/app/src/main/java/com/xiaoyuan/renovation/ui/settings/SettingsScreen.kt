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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.xiaoyuan.renovation.ui.design.AppDateField
import com.xiaoyuan.renovation.ui.design.AppNumberField
import com.xiaoyuan.renovation.ui.design.AppSelect
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
import com.xiaoyuan.renovation.ui.design.KeyValueRow
import com.xiaoyuan.renovation.ui.design.LoadingState
import com.xiaoyuan.renovation.data.model.ExpenseInDto
import com.xiaoyuan.renovation.data.model.ItemListDto
import com.xiaoyuan.renovation.ui.design.NeonButton
import com.xiaoyuan.renovation.ui.design.SectionTitle
import com.xiaoyuan.renovation.ui.design.TagPill
import com.xiaoyuan.renovation.ui.design.TextPromptDialog
import com.xiaoyuan.renovation.ui.lists.ListsViewModel
import com.xiaoyuan.renovation.ui.lists.NewListDialog
import com.xiaoyuan.renovation.ui.theme.Ink
import com.xiaoyuan.renovation.util.Fmt

@Composable
fun SettingsScreen(
    container: AppContainer,
    listsVm: ListsViewModel,
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
    val username by vm.username.collectAsStateWithLifecycle()

    var newRoom by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }
    var renamingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var renamingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingRoom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var deletingCategory by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    LaunchedEffect(refreshKey) { vm.load(); listsVm.load() }

    // 清单管理用的是主壳那一份 ListsViewModel（同一导航条目里是同一个实例），
    // 所以在这里切换/改名/删除，顶部那条切换栏和各页面会一起更新。
    val listItems by listsVm.lists.collectAsStateWithLifecycle()
    val currentListId by listsVm.currentId.collectAsStateWithLifecycle()
    var creatingList by remember { mutableStateOf(false) }
    // 额外费用的录入草稿
    var expKind by remember { mutableStateOf("运费") }
    var expAmount by remember { mutableStateOf("") }
    var expDate by remember { mutableStateOf(Fmt.today()) }
    var expVendor by remember { mutableStateOf("") }
    var expOrderNo by remember { mutableStateOf("") }
    var expItemId by remember { mutableStateOf<Int?>(null) }
    var renamingList by remember { mutableStateOf<ItemListDto?>(null) }
    var deletingList by remember { mutableStateOf<ItemListDto?>(null) }

    // 导出/导入的按钮在页面最底部，把结果做成底部浮动提示，
    // 否则提示条在顶部、用户在底部，点了什么反馈都看不到
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbarHostState.showSnackbar(
                message = text,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            vm.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                subtitle = "服务器 · 分组 · 分类 · 数据备份",
                trailing = {
                    GlassIconButton(icon = Icons.Filled.Refresh, onClick = vm::load, contentDescription = "刷新")
                },
            )

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
                is LoadState.Loading -> LoadingState(text = "正在读取分组与分类…")

                is LoadState.Failed -> ErrorState(
                    message = current.message,
                    serverUrl = baseUrl,
                    hint = current.hint,
                    onRetry = vm::load,
                    onOpenSettings = onEditServerAddress,
                )

                is LoadState.Ready -> {
                    val data = current.data

                    /* ---------- 清单 ---------- */
                    Column {
                        SectionTitle(
                            "清单",
                            caption = "共 ${listItems.size} 份 · 每份的物料、分组、分类互相独立",
                        )
                        Spacer(Modifier.height(10.dp))
                        GlassCard(padding = 0.dp) {
                            listItems.forEachIndexed { index, list ->
                                if (index > 0) GlassDivider(Modifier.padding(horizontal = 14.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = if (list.id == currentListId) {
                                                "${list.name}（当前）"
                                            } else {
                                                list.name
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (list.id == currentListId) {
                                                Ink.Blue
                                            } else {
                                                Ink.TextPrimary
                                            },
                                        )
                                        Text(
                                            text = "${list.itemCount} 条物料 · ${list.roomCount} 个分组",
                                            color = Ink.TextMuted,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    if (list.id != currentListId) {
                                        TagPill(
                                            text = "切换",
                                            color = Ink.Blue,
                                            onClick = { listsVm.select(list.id) },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    TagPill(
                                        text = "改名",
                                        color = Ink.Indigo,
                                        onClick = { renamingList = list },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    // 最后一份不允许删：删了界面就没东西可看了
                                    TagPill(
                                        text = "删除",
                                        color = if (listItems.size > 1) Ink.Danger else Ink.TextMuted,
                                        onClick = {
                                            if (listItems.size > 1) deletingList = list
                                        },
                                    )
                                }
                            }
                            GlassDivider(Modifier.padding(horizontal = 14.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "新建时可以选空白清单，也可以照抄某份现有清单的分组与分类",
                                    color = Ink.TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                NeonButton(
                                    text = "新建清单",
                                    onClick = { creatingList = true },
                                    icon = Icons.Filled.Add,
                                )
                            }
                        }
                    }

                    /* ---------- 分组 ---------- */
                    Column {
                        SectionTitle("分组", caption = "共 ${data.rooms.size} 个 · 删除会同时清掉该分组的分配")
                        Spacer(Modifier.height(10.dp))
                        GlassCard {
                            Row(verticalAlignment = Alignment.Bottom) {
                                AppTextField(
                                    value = newRoom,
                                    onValueChange = { newRoom = it },
                                    label = "新增分组",
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

                    /* ---------- 分类 ---------- */
                    Column {
                        /* ---------- 回收站 ---------- */
                        Column {
                            SectionTitle(
                                "回收站",
                                caption = if (data.trash.isEmpty()) {
                                    "空的 · 删掉的物料会先放这里，可以捞回来"
                                } else {
                                    "${data.trash.size} 条 · 恢复它，分配和采购记录一起回来"
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            GlassCard(padding = 0.dp) {
                                if (data.trash.isEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 14.dp),
                                    ) {
                                        Text("回收站是空的", color = Ink.TextMuted, fontSize = 12.sp)
                                    }
                                } else {
                                    data.trash.forEachIndexed { index, row ->
                                        if (index > 0) {
                                            GlassDivider(Modifier.padding(horizontal = 14.dp))
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    row.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = Ink.TextPrimary,
                                                )
                                                Text(
                                                    "${Fmt.money(row.listTotal)} · 移入于 ${row.deletedAt}",
                                                    color = Ink.TextMuted,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                            TagPill(
                                                text = "恢复",
                                                color = Ink.Blue,
                                                onClick = { vm.restoreItem(row.id) },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            TagPill(
                                                text = "彻底删除",
                                                color = Ink.Danger,
                                                onClick = { vm.purgeItem(row.id) },
                                            )
                                        }
                                    }
                                    GlassDivider(Modifier.padding(horizontal = 14.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "彻底删除会连分配与采购记录一起清掉，无法恢复",
                                            color = Ink.TextMuted,
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        GhostButton(text = "清空", onClick = vm::purgeTrash)
                                    }
                                }
                            }
                        }

                        /* ---------- 额外费用 ---------- */
                        Column {
                            SectionTitle(
                                "额外费用",
                                caption = "运费、安装费这类不进物料单价的支出 · 合计 " +
                                    Fmt.money(data.expenses.sumOf { it.amount }),
                            )
                            Spacer(Modifier.height(10.dp))
                            GlassCard {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AppTextField(
                                        value = expKind,
                                        onValueChange = { expKind = it },
                                        label = "类型",
                                        placeholder = "运费 / 安装费 …",
                                        modifier = Modifier.weight(1f),
                                    )
                                    AppNumberField(
                                        value = expAmount,
                                        onValueChange = { expAmount = it },
                                        label = "金额",
                                        accent = Ink.Mint,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                AppDateField(
                                    value = expDate,
                                    onValueChange = { expDate = it },
                                    showQuickChips = false,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AppTextField(
                                        value = expVendor,
                                        onValueChange = { expVendor = it },
                                        label = "商家",
                                        placeholder = "选填",
                                        modifier = Modifier.weight(1f),
                                    )
                                    AppTextField(
                                        value = expOrderNo,
                                        onValueChange = { expOrderNo = it },
                                        label = "订单号",
                                        placeholder = "选填",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (data.items.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    AppSelect(
                                        label = "关联物料（选填）",
                                        items = data.items,
                                        selected = data.items.firstOrNull { it.id == expItemId },
                                        itemLabel = {
                                            if (it.model.isNotBlank()) "${it.name} · ${it.model}"
                                            else it.name
                                        },
                                        onSelect = { expItemId = it?.id },
                                        allowClear = true,
                                        clearLabel = "不关联",
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                NeonButton(
                                    text = "记一笔费用",
                                    onClick = {
                                        vm.addExpense(
                                            ExpenseInDto(
                                                kind = expKind.ifBlank { "运费" },
                                                amount = Fmt.parseNumberOrZero(expAmount),
                                                date = expDate,
                                                vendor = expVendor,
                                                orderNo = expOrderNo,
                                                itemId = expItemId,
                                            ),
                                        )
                                        expAmount = ""
                                        expVendor = ""
                                        expOrderNo = ""
                                        expItemId = null
                                    },
                                    icon = Icons.Filled.Add,
                                    fillWidth = true,
                                    enabled = Fmt.parseNumberOrZero(expAmount) > 0,
                                )
                            }
                            if (data.expenses.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                GlassCard(padding = 0.dp) {
                                    data.expenses.forEachIndexed { index, row ->
                                        if (index > 0) {
                                            GlassDivider(Modifier.padding(horizontal = 14.dp))
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                val linked = row.itemName.takeIf { it.isNotBlank() }
                                                Text(
                                                    "${row.kind}  ${Fmt.money(row.amount)}" +
                                                        (linked?.let { " · $it" } ?: ""),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = Ink.TextPrimary,
                                                )
                                                val meta = listOfNotNull(
                                                    row.date.takeIf { it.isNotBlank() },
                                                    row.vendor.takeIf { it.isNotBlank() },
                                                    row.orderNo.takeIf { it.isNotBlank() },
                                                ).joinToString(" · ")
                                                if (meta.isNotBlank()) {
                                                    Text(
                                                        meta,
                                                        color = Ink.TextMuted,
                                                        fontSize = 11.sp,
                                                    )
                                                }
                                            }
                                            TagPill(
                                                text = "删除",
                                                color = Ink.Danger,
                                                onClick = { vm.deleteExpense(row.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SectionTitle("分类", caption = "共 ${data.categories.size} 个 · 分类下还有物料时不能删除")
                        Spacer(Modifier.height(10.dp))
                        GlassCard {
                            Row(verticalAlignment = Alignment.Bottom) {
                                AppTextField(
                                    value = newCategory,
                                    onValueChange = { newCategory = it },
                                    label = "新增分类",
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
                    KeyValueRow("应用", "采购清单 Android 1.1.0")
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("数据存放", "服务器的 SQLite 文件")
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("登录账号", username ?: "—")
                    Spacer(Modifier.height(12.dp))
                    GhostButton(
                        text = "退出登录",
                        onClick = { confirmLogout = true },
                        icon = Icons.AutoMirrored.Filled.Logout,
                    )
                    Spacer(Modifier.height(10.dp))
                    HintText("这个 App 只是服务器上那份数据的另一个入口，手机上不存业务数据。")
                }
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { data ->
            GlassCard(corner = 16.dp, padding = 14.dp) {
                Text(
                    text = data.visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextPrimary,
                )
            }
        }
    }

    /* ---------- 弹窗 ---------- */
    if (creatingList) {
        NewListDialog(
            lists = listItems,
            defaultSourceId = currentListId,
            onConfirm = { name, copyFrom ->
                creatingList = false
                listsVm.create(name, copyFrom)
            },
            onDismiss = { creatingList = false },
        )
    }

    renamingList?.let { list ->
        TextPromptDialog(
            title = "清单改名",
            initialValue = list.name,
            onConfirm = { newName ->
                listsVm.rename(list.id, newName)
                renamingList = null
            },
            onDismiss = { renamingList = null },
        )
    }

    deletingList?.let { list ->
        ConfirmDialog(
            title = "删除清单",
            message = "「${list.name}」里的 ${list.itemCount} 条物料、${list.roomCount} 个分组" +
                "会一起删掉，无法撤销。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                deletingList = null
                listsVm.delete(list.id)
            },
            onDismiss = { deletingList = null },
        )
    }

    renamingRoom?.let { (id, name) ->
        TextPromptDialog(
            title = "分组改名",
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
            title = "分类改名",
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
            title = "删除分组",
            message = "「$name」的分配会一并删除，物料本身不受影响。",
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
            title = "删除分类",
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
            KeyValueRow("分配", "${r.allocations} 条")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("采购记录", "${r.records} 条")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("新增分组", "${r.roomsCreated} 个")
            Spacer(Modifier.height(6.dp))
            KeyValueRow("新增分类", "${r.categoriesCreated} 个")
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

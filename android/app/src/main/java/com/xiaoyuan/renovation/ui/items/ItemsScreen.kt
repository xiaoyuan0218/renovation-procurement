package com.xiaoyuan.renovation.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.data.model.CategoryDto
import com.xiaoyuan.renovation.data.model.ItemDto
import com.xiaoyuan.renovation.data.model.STATUS_DONE
import com.xiaoyuan.renovation.data.model.STATUS_PARTIAL
import com.xiaoyuan.renovation.data.model.statusLabel
import com.xiaoyuan.renovation.ui.charts.ThinProgressBar
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.ui.design.AppSelect
import com.xiaoyuan.renovation.ui.design.AppTextField
import com.xiaoyuan.renovation.ui.design.BrandHeader
import com.xiaoyuan.renovation.ui.design.ChoiceChips
import com.xiaoyuan.renovation.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.ui.design.EmptyState
import com.xiaoyuan.renovation.ui.design.ErrorState
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.design.GlassIconButton
import com.xiaoyuan.renovation.ui.design.InlineBanner
import com.xiaoyuan.renovation.ui.design.LoadingState
import com.xiaoyuan.renovation.ui.design.NeonButton
import com.xiaoyuan.renovation.ui.design.TagPill
import com.xiaoyuan.renovation.ui.theme.Ink
import com.xiaoyuan.renovation.ui.theme.categoryColor
import com.xiaoyuan.renovation.ui.theme.statusColor
import com.xiaoyuan.renovation.util.Fmt
import kotlinx.coroutines.delay

@Composable
fun ItemsScreen(
    vm: ItemsViewModel,
    serverUrl: String,
    refreshKey: Int,
    onEditItem: (Int) -> Unit,
    onOpenServerSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val categoryId by vm.categoryId.collectAsStateWithLifecycle()
    val statusFilter by vm.status.collectAsStateWithLifecycle()
    val selectionMode by vm.selectionMode.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var purchaseTarget by remember { mutableStateOf<ItemDto?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) { vm.load() }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2400)
            vm.consumeMessage()
        }
    }

    when (val current = state) {
        is LoadState.Loading -> LoadingState(text = "正在读取清单…")

        is LoadState.Failed -> ErrorState(
            message = current.message,
            serverUrl = serverUrl,
            hint = current.hint,
            onRetry = vm::load,
            onOpenSettings = onOpenServerSettings,
        )

        is LoadState.Ready -> {
            val data = current.data
            val filtered = vm.filtered(data)
            val selectedCategory = data.categories.firstOrNull { it.id == categoryId }

            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    BrandHeader(
                        title = if (selectionMode) "已选 ${selected.size} 项" else "物料清单",
                        subtitle = "${data.items.size} 项物料 · 当前筛出 ${filtered.size} 项",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (selectionMode) {
                                    GlassIconButton(
                                        icon = Icons.Filled.Close,
                                        onClick = { vm.setSelectionMode(false) },
                                        contentDescription = "退出多选",
                                    )
                                } else {
                                    GlassIconButton(
                                        icon = Icons.Filled.CheckCircle,
                                        onClick = { vm.setSelectionMode(true) },
                                        contentDescription = "多选",
                                    )
                                    GlassIconButton(
                                        icon = Icons.Filled.Refresh,
                                        onClick = vm::load,
                                        contentDescription = "刷新",
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(14.dp))

                    // 搜索与分类并排一行：各占一半宽。原来各占一行、中间还夹着间距，
                    // 光这两项就吃掉近 20% 屏高，内容区被挤得只剩半屏。
                    // 给两者同一个高度，并排时上下边才对得齐
                    val fieldHeight = 56.dp
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppTextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            label = "搜索",
                            placeholder = "名称关键词",
                            leadingIcon = Icons.Filled.Search,
                            imeAction = ImeAction.Search,
                            modifier = Modifier
                                .weight(1f)
                                .height(fieldHeight),
                        )
                        AppSelect(
                            label = "分类",
                            items = data.categories,
                            selected = selectedCategory,
                            itemLabel = { it.name },
                            onSelect = { vm.setCategory(it?.id) },
                            placeholder = "全部",
                            allowClear = true,
                            clearLabel = "全部分类",
                            modifier = Modifier
                                .weight(1f)
                                .height(fieldHeight),
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    // 状态标签与合计并成一行：合计本来就只是两个数字，
                    // 单独占一条卡片太高，挂在标签右边刚好把空档填上。
                    // 标签区拿剩余宽度（内部横向滚动，标签多也不会被挤没），
                    // 合计给个固定宽度，免得跟着标签数量一起变
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChoiceChips(
                            options = StatusFilter.entries.map { it to it.label },
                            selected = statusFilter,
                            onSelect = vm::setStatus,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        TotalsBar(items = filtered, modifier = Modifier.width(200.dp))
                    }

                    val banner = message
                    if (banner != null) {
                        Spacer(Modifier.height(10.dp))
                        InlineBanner(text = banner, accent = Ink.Mint)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f)) {
                        EmptyState(
                            title = if (data.items.isEmpty()) "清单还是空的" else "没有符合条件的物料",
                            hint = if (data.items.isEmpty()) "点下面的「新增物料」加第一条，或在「设置」里导入表格" else "换个关键词或筛选条件试试",
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            ItemCard(
                                item = item,
                                selectionMode = selectionMode,
                                selected = item.id in selected,
                                onToggleSelect = { vm.toggleSelect(item.id) },
                                onOpen = { if (selectionMode) vm.toggleSelect(item.id) else onEditItem(item.id) },
                                onPurchase = { purchaseTarget = item },
                            )
                        }
                    }
                }

                if (selectionMode) {
                    SelectionBar(
                        allSelected = filtered.isNotEmpty() && selected.containsAll(filtered.map { it.id }),
                        selectedCount = selected.size,
                        busy = busy,
                        onSelectAll = { vm.selectAll(filtered.map { it.id }) },
                        onDelete = { confirmBatchDelete = true },
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        NeonButton(
                            text = "新增物料",
                            onClick = { onEditItem(NEW_ITEM_ID) },
                            icon = Icons.Filled.Add,
                            fillWidth = true,
                        )
                    }
                }
            }
        }
    }

    purchaseTarget?.let { item ->
        // 分组名要从已加载的数据里取（这个弹层在页面最外层，够不到上面那个 data）
        val rooms = (vm.state.value as? LoadState.Ready)?.data?.rooms ?: emptyList()
        PurchaseSheet(
            item = vm.findItem(item.id) ?: item,
            rooms = rooms,
            busy = busy,
            onDismiss = { purchaseTarget = null },
            onAdd = { body ->
                vm.addRecord(item.id, body) { purchaseTarget = null }
            },
            onClear = { vm.clearRecords(item.id) },
            onDeleteRecord = { recordId -> vm.deleteRecord(recordId) },
        )
    }

    if (confirmBatchDelete) {
        ConfirmDialog(
            title = "删除所选物料",
            message = "将删除 ${selected.size} 项物料，连同它们的采购记录与分配，且无法撤销。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmBatchDelete = false
                vm.deleteSelected()
            },
            onDismiss = { confirmBatchDelete = false },
        )
    }
}

const val NEW_ITEM_ID = -1

@Composable
private fun TotalsBar(items: List<ItemDto>, modifier: Modifier = Modifier) {
    // 与状态标签并排后只剩半屏宽，所以收成两行：上面「合计」标签，下面两个金额，
    // 不再用「当前筛选合计 …… 日常价 X · 实付 Y」那种一条到底的排法
    GlassCard(corner = 16.dp, padding = 10.dp, modifier = modifier) {
        Column {
            Text(
                text = "合计",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextSecondary,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "日常价 ${Fmt.money(items.sumOf { it.discountTotal })}",
                    fontSize = 12.sp,
                    color = Ink.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = " · ",
                    color = Ink.TextMuted,
                )
                Text(
                    text = "实付 ${Fmt.money(items.sumOf { it.paid })}",
                    fontSize = 12.sp,
                    color = Ink.Mint,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: ItemDto,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onPurchase: () -> Unit,
) {
    val progress = if (item.totalQty > 0) (item.paidQty / item.totalQty).toFloat() else 0f

    GlassCard(
        padding = 14.dp,
        onClick = onOpen,
        accent = if (selected) Ink.Blue else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Ink.Blue,
                        uncheckedColor = Ink.TextMuted,
                        checkmarkColor = Color.White,
                    ),
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(6.dp))
            } else {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(categoryColor(item.categoryName)),
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (item.status == STATUS_DONE) Ink.TextMuted else Ink.TextPrimary,
                textDecoration = if (item.status == STATUS_DONE) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TagPill(
                text = if (item.status == STATUS_PARTIAL) {
                    "部分 ${Fmt.qty(item.paidQty)}/${Fmt.qty(item.totalQty)}"
                } else {
                    statusLabel(item.status)
                },
                color = statusColor(item.status),
                onClick = onPurchase,
            )
        }

        Spacer(Modifier.height(5.dp))
        Text(
            text = buildString {
                append(item.categoryName ?: "未分类")
                if (item.brand.isNotBlank()) append(" · ").append(item.brand)
                if (item.model.isNotBlank()) append(" ").append(item.model)
            },
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(11.dp))
        // 系统字体放大后四栏会挤到截断，这时拆成两行两列
        val dense = LocalDensity.current.fontScale < 1.15f
        if (dense) {
            Row {
                MiniStat("数量", Fmt.qtyUnit(item.totalQty, item.unit), Modifier.weight(1f))
                MiniStat("日常价", Fmt.money(item.discountTotal), Modifier.weight(1f), Ink.TextPrimary)
                MiniStat(
                    label = "已付",
                    value = Fmt.money(item.paid),
                    modifier = Modifier.weight(1f),
                    valueColor = if (item.paid > 0) Ink.Mint else Ink.TextMuted,
                )
                MiniStat(
                    label = "未付",
                    value = Fmt.money(item.unpaid),
                    modifier = Modifier.weight(1f),
                    valueColor = if (item.unpaid > 0) Ink.Amber else Ink.TextMuted,
                )
            }
        } else {
            Row {
                MiniStat("数量", Fmt.qtyUnit(item.totalQty, item.unit), Modifier.weight(1f))
                MiniStat("日常价", Fmt.money(item.discountTotal), Modifier.weight(1f), Ink.TextPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                MiniStat(
                    label = "已付",
                    value = Fmt.money(item.paid),
                    modifier = Modifier.weight(1f),
                    valueColor = if (item.paid > 0) Ink.Mint else Ink.TextMuted,
                )
                MiniStat(
                    label = "未付",
                    value = Fmt.money(item.unpaid),
                    modifier = Modifier.weight(1f),
                    valueColor = if (item.unpaid > 0) Ink.Amber else Ink.TextMuted,
                )
            }
        }

        if (item.totalQty > 0) {
            Spacer(Modifier.height(10.dp))
            ThinProgressBar(
                ratio = progress,
                color = statusColor(item.status),
                height = 5.dp,
            )
        }

        if (item.note.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = item.note,
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink.TextPrimary,
) {
    Column(modifier) {
        Text(label, fontSize = 10.sp, color = Ink.TextMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectionBar(
    allSelected: Boolean,
    selectedCount: Int,
    busy: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TagPill(
            text = if (allSelected) "取消全选" else "全选",
            color = Ink.Indigo,
            onClick = onSelectAll,
        )
        NeonButton(
            text = if (selectedCount > 0) "删除所选 ($selectedCount)" else "删除所选",
            onClick = onDelete,
            icon = Icons.Filled.DeleteSweep,
            enabled = selectedCount > 0 && !busy,
            gradient = Ink.DangerGradient,
            modifier = Modifier.weight(1f),
            fillWidth = true,
        )
    }
}

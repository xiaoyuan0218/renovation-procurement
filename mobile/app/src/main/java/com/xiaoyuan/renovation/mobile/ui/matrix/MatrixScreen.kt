package com.xiaoyuan.renovation.mobile.ui.matrix

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.mobile.data.model.MatrixCellDto
import com.xiaoyuan.renovation.mobile.data.model.MatrixDto
import com.xiaoyuan.renovation.mobile.data.model.MatrixItemDto
import com.xiaoyuan.renovation.mobile.data.model.RoomDto
import com.xiaoyuan.renovation.mobile.data.model.STATUS_DONE
import com.xiaoyuan.renovation.mobile.data.model.STATUS_PARTIAL
import com.xiaoyuan.renovation.mobile.data.model.statusLabel
import com.xiaoyuan.renovation.mobile.ui.common.LoadState
import com.xiaoyuan.renovation.mobile.ui.common.dataOrNull
import com.xiaoyuan.renovation.mobile.ui.design.AppNumberField
import com.xiaoyuan.renovation.mobile.ui.design.AppTextField
import com.xiaoyuan.renovation.mobile.ui.design.ChoiceChips
import com.xiaoyuan.renovation.mobile.ui.design.EmptyState
import com.xiaoyuan.renovation.mobile.ui.design.ErrorState
import com.xiaoyuan.renovation.mobile.ui.design.GhostButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassBottomSheet
import com.xiaoyuan.renovation.mobile.ui.design.GlassCard
import com.xiaoyuan.renovation.mobile.ui.design.GlassIconButton
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.FloatingNotice
import com.xiaoyuan.renovation.mobile.ui.design.LoadingState
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.design.SectionTitle
import com.xiaoyuan.renovation.mobile.ui.design.TagPill
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import com.xiaoyuan.renovation.mobile.ui.theme.categoryColor
import com.xiaoyuan.renovation.mobile.ui.theme.statusColor
import com.xiaoyuan.renovation.mobile.util.Fmt
import kotlinx.coroutines.delay

private val NAME_COL_WIDTH = 126.dp
private val CELL_WIDTH = 62.dp
private val ROW_HEIGHT = 50.dp

/** 正在编辑的单元格。 */
private data class CellTarget(
    val item: MatrixItemDto,
    val room: RoomDto,
    val cell: MatrixCellDto?,
)

@Composable
fun MatrixScreen(
    vm: MatrixViewModel,
    refreshKey: Int,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val onlyPending by vm.onlyPending.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<CellTarget?>(null) }
    var addingTo by remember { mutableStateOf<MatrixItemDto?>(null) }

    LaunchedEffect(refreshKey) { vm.loadIfStale(refreshKey) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2400)
            vm.consumeMessage()
        }
    }

    when (val current = state) {
        is LoadState.Loading -> LoadingState(text = "正在读取分配…")

        is LoadState.Failed -> ErrorState(
            message = current.message,
            hint = current.hint,
            onRetry = vm::load,
        )

        is LoadState.Ready -> {
            val data = current.data
            // 同上：筛选结果要记住，否则每次重组都重算一遍
            val items = remember(data.items, query, onlyPending) {
                vm.filter(data.items, query, onlyPending)
            }

            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    com.xiaoyuan.renovation.mobile.ui.design.BrandHeader(
                        title = "分配矩阵",
                        subtitle = "${data.rooms.size} 个分组 · ${data.items.size} 项物料",
                        trailing = {
                            GlassIconButton(
                                icon = Icons.Filled.Refresh,
                                onClick = vm::load,
                                contentDescription = "刷新",
                            )
                        },
                    )
                    Spacer(Modifier.height(14.dp))

                    AppTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        label = "搜索物料",
                        placeholder = "输入名称筛选",
                        leadingIcon = Icons.Filled.Search,
                        imeAction = ImeAction.Search,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChoiceChips(
                            options = listOf(false to "全部物料", true to "只看未买"),
                            selected = onlyPending,
                            onSelect = { vm.toggleOnlyPending() },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        ModeSwitch(mode = mode, onSelect = vm::setMode)
                    }
                    Spacer(Modifier.height(10.dp))
                    LegendRow()
                    Spacer(Modifier.height(12.dp))
                }

                // 提示条浮在内容之上，不占布局空间（插进布局流会把列表顶下去）
                Box(Modifier.weight(1f)) {
                    if (items.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            EmptyState(
                                title = if (query.isBlank()) "没有可显示的物料" else "没找到「$query」",
                                hint = if (onlyPending) "取消「只看未买」可以看到全部物料" else null,
                            )
                        }
                    } else if (mode == MatrixMode.Grid) {
                        GridView(
                            rooms = data.rooms,
                            items = items,
                            onEdit = { item, room -> editing = CellTarget(item, room, item.cellOf(room.id)) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ListView(
                            items = items,
                            rooms = data.rooms,
                            onEdit = { item, room -> editing = CellTarget(item, room, item.cellOf(room.id)) },
                            onAddRoom = { addingTo = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    val banner = message
                    if (banner != null) {
                        FloatingNotice(
                            text = banner,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        CellEditSheet(
            target = target,
            saving = saving,
            onDismiss = { editing = null },
            onSave = { qty, priceOverride, note ->
                vm.saveCell(
                    itemId = target.item.id,
                    roomId = target.room.id,
                    qty = qty,
                    priceOverride = priceOverride,
                    note = note,
                ) { editing = null }
            },
        )
    }

    addingTo?.let { item ->
        val used = item.cells.keys.mapNotNull { it.toIntOrNull() }.toSet()
        RoomPickerSheet(
            title = "为「${item.name}」选择分组",
            rooms = (state.dataOrNull?.rooms ?: emptyList()).filter { it.id !in used },
            onDismiss = { addingTo = null },
            onPick = { room ->
                addingTo = null
                editing = CellTarget(item, room, null)
            },
        )
    }
}

@Composable
private fun ModeSwitch(mode: MatrixMode, onSelect: (MatrixMode) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Ink.GlassFill)
            .border(1.dp, Ink.GlassBorder, shape)
            .padding(3.dp),
    ) {
        ModeChip(
            icon = Icons.AutoMirrored.Filled.ViewList,
            text = "列表",
            selected = mode == MatrixMode.List,
            onClick = { onSelect(MatrixMode.List) },
        )
        ModeChip(
            icon = Icons.Filled.GridOn,
            text = "矩阵",
            selected = mode == MatrixMode.Grid,
            onClick = { onSelect(MatrixMode.Grid) },
        )
    }
}

@Composable
private fun ModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) Ink.Indigo.copy(alpha = 0.85f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color.White else Ink.TextSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) Color.White else Ink.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun LegendRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(Ink.Indigo, "未买齐")
        LegendItem(Ink.Mint, "该分组已买齐")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Ink.Amber),
            )
            Spacer(Modifier.width(5.dp))
            Text("有备注或单独价", fontSize = 10.sp, color = Ink.TextSecondary)
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.75f)),
        )
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 10.sp, color = Ink.TextSecondary)
    }
}

/* ============================================================
   列表态：一项物料一张卡，分组以标签铺开
   ============================================================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListView(
    items: List<MatrixItemDto>,
    rooms: List<RoomDto>,
    onEdit: (MatrixItemDto, RoomDto) -> Unit,
    onAddRoom: (MatrixItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roomById = remember(rooms) { rooms.associateBy { it.id } }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            GlassCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(categoryColor(item.categoryName)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TagPill(text = statusLabel(item.status), color = statusColor(item.status))
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(item.categoryName ?: "未分类")
                        if (item.model.isNotBlank()) append(" · ").append(item.model)
                        append(" · ¥").append(Fmt.plainMoney(item.price)).append("/").append(item.unit)
                        if (item.status == STATUS_PARTIAL) {
                            append(" · 实付 ").append(Fmt.qty(item.paidQty)).append("/").append(Fmt.qty(item.totalQty))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(12.dp))

                val allocations = item.cells.entries
                    .mapNotNull { (key, cell) -> key.toIntOrNull()?.let { id -> id to cell } }
                    .sortedBy { (roomId, _) -> rooms.indexOfFirst { it.id == roomId } }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allocations.forEach { (roomId, cell) ->
                        val room = roomById[roomId]
                        val fullyPaid = cell.qty > 0 && cell.paidQty >= cell.qty - 1e-9
                        val color = if (fullyPaid) Ink.Mint else Ink.Indigo
                        AllocationChip(
                            roomName = room?.name ?: "分组$roomId",
                            qty = cell.qty,
                            unit = item.unit,
                            color = color,
                            flagged = cell.priceOverride != null || cell.note.isNotBlank(),
                            onClick = { room?.let { onEdit(item, it) } },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Ink.Blue.copy(alpha = 0.12f))
                            .border(1.dp, Ink.Blue.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .clickable { onAddRoom(item) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = Ink.Blue,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("分组", fontSize = 12.sp, color = Ink.Blue)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row {
                    Text(
                        text = "总数量 ${Fmt.qtyUnit(item.totalQty, item.unit)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "原价 ${Fmt.money(item.listTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllocationChip(
    roomName: String,
    qty: Double,
    unit: String,
    color: Color,
    flagged: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = roomName,
            fontSize = 12.sp,
            color = Ink.TextSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = Fmt.qty(qty) + unit,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        if (flagged) {
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Ink.Amber),
            )
        }
    }
}

/* ============================================================
   矩阵态：冻结物料列 + 分组列横滑 + 底部合计行
   ============================================================ */

@Composable
private fun GridView(
    rooms: List<RoomDto>,
    items: List<MatrixItemDto>,
    onEdit: (MatrixItemDto, RoomDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 表头、每一行、合计行共用同一个横向滚动状态 → 天然对齐
    val hScroll = rememberScrollState()

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Ink.GlassFillStrong),
        ) {
            Box(
                Modifier
                    .width(NAME_COL_WIDTH)
                    .fillMaxHeight()
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("物料", fontSize = 12.sp, color = Ink.TextSecondary)
            }
            Row(Modifier.horizontalScroll(hScroll)) {
                rooms.forEach { room ->
                    Box(
                        Modifier
                            .width(CELL_WIDTH)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = room.name,
                            fontSize = 11.sp,
                            color = Ink.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                    Row(
                        Modifier
                            .width(NAME_COL_WIDTH)
                            .fillMaxHeight()
                            .padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(categoryColor(item.categoryName)),
                        )
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Text(
                                text = item.name,
                                fontSize = 13.sp,
                                color = if (item.status == STATUS_DONE) Ink.TextMuted else Ink.TextPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = Fmt.moneyCompact(item.price) + "/" + item.unit,
                                fontSize = 10.sp,
                                color = Ink.TextMuted,
                                maxLines = 1,
                            )
                        }
                    }
                    Row(Modifier.horizontalScroll(hScroll)) {
                        rooms.forEach { room ->
                            val cell = item.cellOf(room.id)
                            MatrixCellBox(
                                cell = cell,
                                unit = item.unit,
                                onClick = { onEdit(item, room) },
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(Ink.GlassFillStrong),
        ) {
            Box(
                Modifier
                    .width(NAME_COL_WIDTH)
                    .fillMaxHeight()
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("合计", fontSize = 12.sp, color = Ink.TextPrimary, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.horizontalScroll(hScroll)) {
                rooms.forEach { room ->
                    val sum = items.sumOf { it.cellOf(room.id)?.qty ?: 0.0 }
                    Box(
                        Modifier
                            .width(CELL_WIDTH)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (sum > 0) Fmt.qty(sum) else "—",
                            fontSize = 12.sp,
                            color = if (sum > 0) Ink.TextPrimary else Ink.TextMuted,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            HintText("左右滑动查看各分组；点格子填数量、覆盖单价与备注。")
        }
    }
}

@Composable
private fun MatrixCellBox(
    cell: MatrixCellDto?,
    unit: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val qty = cell?.qty ?: 0.0
    val paidQty = cell?.paidQty ?: 0.0
    val fullyPaid = qty > 0 && paidQty >= qty - 1e-9
    val background = when {
        qty <= 0 -> Ink.GlassFill
        fullyPaid -> Ink.Mint.copy(alpha = 0.20f)
        else -> Ink.Indigo.copy(alpha = 0.20f)
    }
    val borderColor = when {
        qty <= 0 -> Ink.GlassBorderSoft
        fullyPaid -> Ink.Mint.copy(alpha = 0.55f)
        else -> Ink.Indigo.copy(alpha = 0.5f)
    }
    val flagged = cell != null && (cell.priceOverride != null || cell.note.isNotBlank())

    Box(Modifier.width(CELL_WIDTH).fillMaxHeight().padding(2.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(background)
                .border(1.dp, borderColor, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (qty > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Fmt.qty(qty),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (fullyPaid) Ink.Mint else Ink.TextPrimary,
                    )
                    if (paidQty > 0 && !fullyPaid) {
                        Text(
                            text = "付${Fmt.qty(paidQty)}",
                            fontSize = 9.sp,
                            color = Ink.Amber,
                        )
                    }
                }
            } else {
                Text("·", fontSize = 14.sp, color = Ink.TextMuted)
            }
        }
        if (flagged) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Ink.Amber),
            )
        }
    }
}

/* ============================================================
   弹层：改分配 / 选分组
   ============================================================ */

@Composable
private fun CellEditSheet(
    target: CellTarget,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (qty: Double, priceOverride: Double?, note: String) -> Unit,
) {
    val cell = target.cell
    var qty by remember { mutableStateOf(cell?.qty?.let { Fmt.qty(it) } ?: "") }
    var priceOverride by remember { mutableStateOf(cell?.priceOverride?.let { Fmt.qty(it) } ?: "") }
    var note by remember { mutableStateOf(cell?.note ?: "") }

    GlassBottomSheet(
        title = "${target.room.name} · ${target.item.name}",
        subtitle = buildString {
            append("物料单价 ¥").append(Fmt.plainMoney(target.item.price)).append("/").append(target.item.unit)
            append(" · 总数量 ${Fmt.qtyUnit(target.item.totalQty, target.item.unit)}")
        },
        onDismiss = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppNumberField(
                value = qty,
                onValueChange = { qty = it },
                label = "数量",
                placeholder = "0 表示不在该分组",
                modifier = Modifier.weight(1f),
            )
            AppNumberField(
                value = priceOverride,
                onValueChange = { priceOverride = it },
                label = "覆盖单价",
                placeholder = "留空用物料价",
                modifier = Modifier.weight(1f),
                accent = Ink.Indigo,
            )
        }
        Spacer(Modifier.height(12.dp))
        AppTextField(
            value = note,
            onValueChange = { note = it },
            label = "备注",
            placeholder = "例如：这间先买一半",
            accent = Ink.Indigo,
        )
        Spacer(Modifier.height(8.dp))
        HintText("数量填 0 即清空这个分组的分配。")

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "清空分配",
                onClick = { onSave(0.0, null, "") },
                enabled = cell != null && !saving,
                contentColor = Ink.DangerSoft,
            )
            NeonButton(
                text = "保存",
                onClick = { onSave(Fmt.parseNumberOrZero(qty), Fmt.parseNumber(priceOverride), note.trim()) },
                icon = Icons.Filled.Check,
                enabled = !saving,
                loading = saving,
                modifier = Modifier.weight(1f),
                fillWidth = true,
            )
        }
    }
}

@Composable
private fun RoomPickerSheet(
    title: String,
    rooms: List<RoomDto>,
    onDismiss: () -> Unit,
    onPick: (RoomDto) -> Unit,
) {
    GlassBottomSheet(title = title, onDismiss = onDismiss) {
        if (rooms.isEmpty()) {
            EmptyState(title = "所有分组都排过了", hint = "到「设置」里可以新增分组")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rooms.forEach { room ->
                    GlassCard(
                        corner = 14.dp,
                        padding = 14.dp,
                        onClick = { onPick(room) },
                    ) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink.TextPrimary,
                        )
                    }
                }
            }
        }
    }
}

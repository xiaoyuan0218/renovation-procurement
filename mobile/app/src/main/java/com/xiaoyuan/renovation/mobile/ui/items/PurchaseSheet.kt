package com.xiaoyuan.renovation.mobile.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.model.statusLabel
import com.xiaoyuan.renovation.mobile.ui.design.AppDateField
import com.xiaoyuan.renovation.mobile.ui.design.AppNumberField
import com.xiaoyuan.renovation.mobile.data.model.RecordInDto
import com.xiaoyuan.renovation.mobile.data.model.RoomDto
import com.xiaoyuan.renovation.mobile.ui.design.AppTextField
import com.xiaoyuan.renovation.mobile.ui.design.MultiChoiceChips
import com.xiaoyuan.renovation.mobile.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.mobile.ui.design.GhostButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassBottomSheet
import com.xiaoyuan.renovation.mobile.ui.design.GlassCard
import com.xiaoyuan.renovation.mobile.ui.design.GlassDivider
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.design.TagPill
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import com.xiaoyuan.renovation.mobile.ui.theme.statusColor
import com.xiaoyuan.renovation.mobile.util.Fmt

/**
 * 记一笔采购。对照网页版的「记一笔」弹窗：实付数量 / 实付金额 / 日期，
 * 外加清零与"按日常价付清"两个快捷动作 —— 另外把已记的每一笔列出来，可以直接删。
 */
@Composable
fun PurchaseSheet(
    item: ItemDto,
    rooms: List<RoomDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (RecordInDto) -> Unit,
    onClear: () -> Unit,
    onDeleteRecord: (Int) -> Unit,
) {
    // 弹层里的数量默认填"还差多少"，这是最常见的一次性买齐场景
    var qty by remember(item.id) { mutableStateOf(if (item.unpaidQty > 0) Fmt.qty(item.unpaidQty) else "") }
    var amount by remember(item.id) { mutableStateOf("") }
    var date by remember(item.id) { mutableStateOf(Fmt.today()) }
    var note by remember(item.id) { mutableStateOf("") }
    var vendor by remember(item.id) { mutableStateOf("") }
    var orderNo by remember(item.id) { mutableStateOf("") }
    var roomIds by remember(item.id) { mutableStateOf<Set<Int>>(emptySet()) }

    // 只能勾这条物料实际分到的分组 —— 归到一个它没分到的分组没有意义
    val allocRooms = remember(item.id, rooms) {
        val ids = item.allocations.map { it.roomId }.toSet()
        rooms.filter { it.id in ids }
    }
    var confirmClear by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id, item.paidQty) {
        // 记录变化后刷新默认值（比如刚点了"付清"）
        if (item.unpaidQty > 0) qty = Fmt.qty(item.unpaidQty)
    }

    fun recordInput(q: Double, a: Double) = RecordInDto(
        qty = q, amount = a, date = date, note = note.trim(),
        vendor = vendor.trim(), orderNo = orderNo.trim(),
        roomIds = roomIds.toList(),
    )

    GlassBottomSheet(
        title = "记一笔采购",
        subtitle = "${item.name} · ${statusLabel(item.status)}",
        onDismiss = onDismiss,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GlassCard(corner = 16.dp, padding = 12.dp) {
                Row {
                    SummaryCell("共 ${Fmt.qtyUnit(item.totalQty, item.unit)}", "采购总量", Modifier.weight(1f))
                    SummaryCell(Fmt.money(item.paid), "已付", Modifier.weight(1f), Ink.Mint)
                    SummaryCell("${item.records.size} 笔", "已记", Modifier.weight(1f), Ink.Indigo)
                }
                if (item.unpaidQty > 0) {
                    Spacer(Modifier.height(8.dp))
                    HintText("还差 ${Fmt.qtyUnit(item.unpaidQty, item.unit)} · 未付 ${Fmt.money(item.unpaid)}")
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppNumberField(
                    value = qty,
                    onValueChange = { qty = it },
                    label = "实付数量",
                    placeholder = "本次买了几件",
                    modifier = Modifier.weight(1f),
                )
                AppNumberField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = "实付金额",
                    placeholder = "本次花了多少",
                    modifier = Modifier.weight(1f),
                    accent = Ink.Mint,
                )
            }

            Spacer(Modifier.height(12.dp))
            AppDateField(value = date, onValueChange = { date = it })

            if (allocRooms.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "涉及分组（可多选，勾了谁就只往谁身上算）",
                    color = Ink.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                MultiChoiceChips(
                    options = allocRooms.map { it.id to it.name },
                    selected = roomIds,
                    onToggle = { id ->
                        roomIds = if (id in roomIds) roomIds - id else roomIds + id
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(
                    value = vendor,
                    onValueChange = { vendor = it },
                    label = "商家",
                    placeholder = "选填",
                    modifier = Modifier.weight(1f),
                )
                AppTextField(
                    value = orderNo,
                    onValueChange = { orderNo = it },
                    label = "订单号",
                    placeholder = "选填",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注",
                placeholder = "选填，例如：双十一满减",
                accent = Ink.Indigo,
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = Ink.DangerSoft, fontSize = 12.sp)
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = "按日常价付清",
                    onClick = {
                        val remaining = item.unpaidQty
                        if (remaining <= 0) {
                            error = "已经买齐了"
                        } else {
                            error = null
                            val unitPrice = item.discountPrice ?: item.price
                            onAdd(recordInput(remaining, remaining * unitPrice))
                        }
                    },
                    icon = Icons.Filled.RestartAlt,
                    enabled = !busy,
                )
                GhostButton(
                    text = "清零",
                    onClick = { confirmClear = true },
                    enabled = !busy && item.records.isNotEmpty(),
                    contentColor = Ink.DangerSoft,
                )
                NeonButton(
                    text = "记一笔",
                    onClick = {
                        val q = Fmt.parseNumberOrZero(qty)
                        val a = Fmt.parseNumberOrZero(amount)
                        error = when {
                            q <= 0 && a <= 0 -> "数量和金额不能同时为 0"
                            q < 0 || a < 0 -> "不能填负数"
                            else -> null
                        }
                        if (error == null) onAdd(recordInput(q, a))
                    },
                    icon = Icons.Filled.Add,
                    enabled = !busy,
                    loading = busy,
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                )
            }

            if (item.records.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "已记的每一笔",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                GlassCard(corner = 16.dp, padding = 0.dp) {
                    item.records.forEachIndexed { index, record ->
                        if (index > 0) GlassDivider(Modifier.padding(horizontal = 12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${Fmt.qty(record.qty)}${item.unit}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Ink.TextPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = Fmt.money(record.amount),
                                        fontSize = 14.sp,
                                        color = Ink.Mint,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (record.unitPrice != null) {
                                        Spacer(Modifier.width(8.dp))
                                        TagPill(
                                            text = "均价 ${Fmt.money(record.unitPrice)}",
                                            color = Ink.Indigo,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = buildString {
                                        append(record.date.ifBlank { "未填日期" })
                                        if (record.note.isNotBlank()) append(" · ").append(record.note)
                                    },
                                    fontSize = 11.sp,
                                    color = Ink.TextMuted,
                                )
                            }
                            TagPill(
                                text = "删除",
                                color = Ink.DangerSoft,
                                onClick = { onDeleteRecord(record.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HintText("删除某一笔后，已付金额会立即重算。")
            }

            Spacer(Modifier.height(12.dp))
            HintText("状态一览：${statusLabel(item.status)} · 实付均价 ${Fmt.money(item.paidPrice)}")
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清零采购记录",
            message = "将删除「${item.name}」的全部 ${item.records.size} 笔采购记录，状态会回到未买。",
            confirmText = "清零",
            danger = true,
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SummaryCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink.TextPrimary,
) {
    Column(modifier) {
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = Ink.TextMuted)
    }
}

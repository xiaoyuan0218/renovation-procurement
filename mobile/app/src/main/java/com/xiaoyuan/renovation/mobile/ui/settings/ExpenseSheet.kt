package com.xiaoyuan.renovation.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaoyuan.renovation.mobile.data.model.ExpenseDto
import com.xiaoyuan.renovation.mobile.data.model.ExpenseInDto
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.ui.design.AppDateField
import com.xiaoyuan.renovation.mobile.ui.design.AppNumberField
import com.xiaoyuan.renovation.mobile.ui.design.AppSelect
import com.xiaoyuan.renovation.mobile.ui.design.AppTextField
import com.xiaoyuan.renovation.mobile.ui.design.GhostButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassBottomSheet
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.design.TagPill
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import com.xiaoyuan.renovation.mobile.util.Fmt

/** 常填的类型；用过的类型也会出现在下面，点一下就填进去。 */
private val PRESET_KINDS = listOf("运费", "安装费", "辅料", "其他")

/**
 * 记一笔额外费用（运费、安装费这类不进物料单价的支出）。
 *
 * 这类钱刻意不摊进物料单价 —— 装修里的运费通常按订单、按趟算，摊到某一两件
 * 物料上只会让单价失真。它独立记账，在总览里单独汇总。
 */
@Composable
fun ExpenseSheet(
    items: List<ItemDto>,
    usedKinds: List<String>,
    editing: ExpenseDto?,
    busy: Boolean,
    onSave: (ExpenseInDto) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(editing?.kind ?: "运费") }
    var amount by remember { mutableStateOf(editing?.amount?.takeIf { it != 0.0 }?.let { Fmt.plainMoney(it) } ?: "") }
    var date by remember { mutableStateOf(editing?.date ?: Fmt.today()) }
    var vendor by remember { mutableStateOf(editing?.vendor.orEmpty()) }
    var orderNo by remember { mutableStateOf(editing?.orderNo.orEmpty()) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    var itemId by remember { mutableStateOf(editing?.itemId) }

    val chips = (PRESET_KINDS + usedKinds).distinct()
    val parsedAmount = Fmt.parseNumberOrZero(amount)

    GlassBottomSheet(
        title = if (editing == null) "记一笔额外费用" else "编辑这笔费用",
        subtitle = "运费、安装费这类不进物料单价的支出",
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTextField(
                value = kind,
                onValueChange = { kind = it },
                label = "类型",
                placeholder = "运费 / 安装费 / 辅料…",
                isError = kind.isBlank(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.take(6).forEach { preset ->
                    TagPill(
                        text = preset,
                        color = if (preset == kind) Ink.Cyan else Ink.TextSecondary,
                        filled = preset == kind,
                        onClick = { kind = preset },
                    )
                }
            }

            AppNumberField(
                value = amount,
                onValueChange = { amount = it },
                label = "金额",
                placeholder = "0.00",
                isError = parsedAmount <= 0,
                supportingText = if (parsedAmount <= 0) "填一个大于 0 的金额" else null,
            )

            AppDateField(value = date, onValueChange = { date = it }, label = "日期（选填）")

            if (items.isNotEmpty()) {
                AppSelect(
                    label = "关联物料（选填）",
                    items = items,
                    selected = items.firstOrNull { it.id == itemId },
                    itemLabel = {
                        if (it.model.isNotBlank()) "${it.name} · ${it.model}" else it.name
                    },
                    onSelect = { itemId = it?.id },
                    allowClear = true,
                    clearLabel = "不关联",
                    searchable = true,
                )
                HintText("关联只是为了对账，金额不会算到那条物料头上")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(
                    value = vendor,
                    onValueChange = { vendor = it },
                    label = "商家（选填）",
                    modifier = Modifier.weight(1f),
                )
                AppTextField(
                    value = orderNo,
                    onValueChange = { orderNo = it },
                    label = "订单号（选填）",
                    modifier = Modifier.weight(1f),
                )
            }

            AppTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（选填）",
                singleLine = false,
                minLines = 2,
            )

            Spacer(Modifier.height(2.dp))
            NeonButton(
                text = if (editing == null) "记下这笔" else "保存",
                modifier = Modifier.fillMaxWidth(),
                enabled = parsedAmount > 0 && kind.isNotBlank(),
                loading = busy,
                onClick = {
                    onSave(
                        ExpenseInDto(
                            kind = kind.trim(),
                            amount = parsedAmount,
                            date = date.trim(),
                            vendor = vendor.trim(),
                            orderNo = orderNo.trim(),
                            note = note.trim(),
                            itemId = itemId,
                        ),
                    )
                },
            )
            if (onDelete != null) {
                GhostButton(
                    text = "删除这笔费用",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDelete,
                )
            }
        }
    }
}

/** 费用行里的摘要文字：类型 + 日期 + 商家，空的部分不占位。 */
fun expenseSubtitle(e: ExpenseDto): String {
    val parts = mutableListOf<String>()
    if (e.date.isNotBlank()) parts += e.date
    if (e.vendor.isNotBlank()) parts += e.vendor
    if (e.orderNo.isNotBlank()) parts += "单号 ${e.orderNo}"
    if (e.itemName.isNotBlank()) parts += "→ ${e.itemName}"
    return parts.joinToString(" · ").ifBlank { "未填日期与商家" }
}

/** 列表里那行金额的颜色：金额为负时提示一下（理论上不该出现，出现就是填错了）。 */
fun expenseAmountColor(amount: Double) = if (amount < 0) Ink.Amber else Ink.TextPrimary

package com.xiaoyuan.renovation.mobile.ui.lists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import com.xiaoyuan.renovation.mobile.ui.design.AppSelect
import com.xiaoyuan.renovation.mobile.ui.design.AppTextField
import com.xiaoyuan.renovation.mobile.ui.design.ChoiceChips
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

/**
 * 新建清单：可以建一张空白的，也可以照抄某份现有清单的分组与分类。
 *
 * 默认空白 —— 复制是显式动作，免得随手新建却凭空多出十几个分组；
 * 复制只搬结构，物料、分配、采购记录都不带。
 */
@Composable
fun NewListDialog(
    lists: List<ItemListDto>,
    defaultSourceId: Int?,
    onConfirm: (name: String, copyFrom: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var copyStructure by remember { mutableStateOf(false) }
    var sourceId by remember { mutableStateOf(defaultSourceId ?: lists.firstOrNull()?.id) }

    val source = lists.firstOrNull { it.id == sourceId }
    val canSubmit = name.isNotBlank() && (!copyStructure || source != null)
    val submit = { onConfirm(name.trim(), if (copyStructure) sourceId else null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text("新建清单", color = Ink.TextPrimary,
                style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "清单名",
                    imeAction = ImeAction.Done,
                    onDone = { if (canSubmit) submit() },
                )
                Spacer(Modifier.height(14.dp))
                Text("起步方式", color = Ink.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                ChoiceChips(
                    options = listOf(false to "空白清单", true to "复制分组和分类"),
                    selected = copyStructure,
                    onSelect = { copyStructure = it },
                )
                if (copyStructure) {
                    Spacer(Modifier.height(12.dp))
                    AppSelect(
                        label = "复制自",
                        items = lists,
                        selected = source,
                        itemLabel = {
                            "${it.name}（${it.roomCount} 个分组 · ${it.categoryCount} 个分类）"
                        },
                        onSelect = { sourceId = it?.id },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "只照抄分组和分类，物料与采购记录不会带过来。",
                        color = Ink.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = submit) {
                Text(
                    "创建",
                    color = if (canSubmit) Ink.Blue else Ink.TextMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Ink.TextSecondary) }
        },
    )
}

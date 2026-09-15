package com.xiaoyuan.renovation.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.ui.theme.Ink
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun glassFieldColors(accent: Color = Ink.Blue, isError: Boolean = false): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = Ink.TextPrimary,
        unfocusedTextColor = Ink.TextPrimary,
        disabledTextColor = Ink.TextMuted,
        errorTextColor = Ink.TextPrimary,
        cursorColor = accent,
        focusedBorderColor = accent.copy(alpha = 0.8f),
        unfocusedBorderColor = Ink.GlassBorder,
        disabledBorderColor = Ink.GlassBorderSoft,
        errorBorderColor = Ink.Danger,
        focusedContainerColor = Ink.GlassFill,
        unfocusedContainerColor = Ink.GlassFill,
        disabledContainerColor = Ink.GlassFill,
        errorContainerColor = Ink.GlassFill,
        focusedLabelColor = accent,
        unfocusedLabelColor = Ink.TextSecondary,
        disabledLabelColor = Ink.TextMuted,
        errorLabelColor = Ink.Danger,
        focusedPlaceholderColor = Ink.TextMuted,
        unfocusedPlaceholderColor = Ink.TextMuted,
        focusedSupportingTextColor = Ink.TextSecondary,
        unfocusedSupportingTextColor = Ink.TextSecondary,
        errorSupportingTextColor = Ink.Danger,
    )

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isError: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    accent: Color = Ink.Blue,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text) } },
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        isError = isError,
        shape = RoundedCornerShape(14.dp),
        colors = glassFieldColors(accent, isError),
        leadingIcon = leadingIcon?.let { icon ->
            { Icon(icon, contentDescription = null, tint = Ink.TextSecondary, modifier = Modifier.size(18.dp)) }
        },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onDone?.invoke() },
            onSearch = { onDone?.invoke() },
        ),
        supportingText = supportingText?.let { text ->
            { Text(text, style = MaterialTheme.typography.bodySmall) }
        },
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

/** 只允许数字和一个小数点，避免用户在小键盘上误输导致解析失败。 */
fun sanitizeDecimalInput(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered.take(12)
    val head = filtered.substring(0, firstDot + 1)
    val tail = filtered.substring(firstDot + 1).replace(".", "")
    return (head + tail).take(12)
}

@Composable
fun AppNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    accent: Color = Ink.Blue,
) {
    AppTextField(
        value = value,
        onValueChange = { onValueChange(sanitizeDecimalInput(it)) },
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        keyboardType = KeyboardType.Decimal,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        accent = accent,
    )
}

/** 下拉选择：用自绘玻璃框 + DropdownMenu，避免 ExposedDropdownMenuBox 的实验 API。 */
@Composable
fun <T> AppSelect(
    label: String,
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "未选择",
    allowClear: Boolean = false,
    clearLabel: String = "不设置",
    enabled: Boolean = true,
    isItemEnabled: (T) -> Boolean = { true },
    isItemSelected: (T) -> Boolean = { selected == it },
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Ink.GlassFill)
                    .border(1.dp, Ink.GlassBorder, shape)
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected?.let(itemLabel) ?: placeholder,
                    color = if (selected != null) Ink.TextPrimary else Ink.TextMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Ink.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Ink.BgMid),
            ) {
                if (allowClear) {
                    DropdownMenuItem(
                        text = { Text(clearLabel, color = Ink.TextSecondary) },
                        onClick = {
                            onSelect(null)
                            expanded = false
                        },
                    )
                }
                items.forEach { item ->
                    val itemEnabled = isItemEnabled(item)
                    val isSelected = isItemSelected(item)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = itemLabel(item),
                                color = when {
                                    !itemEnabled -> Ink.TextMuted
                                    isSelected -> Ink.Blue
                                    else -> Ink.TextPrimary
                                },
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        enabled = itemEnabled,
                        onClick = {
                            onSelect(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 横向滚动的单选胶囊组（筛选用）。 */
@Composable
fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Ink.Blue,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val shape = RoundedCornerShape(50)
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) Color.White else Ink.TextSecondary,
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) accent.copy(alpha = 0.85f) else Ink.GlassFill)
                    .border(1.dp, if (isSelected) accent else Ink.GlassBorder, shape)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** 日期输入：文本框 + 日历选择 + 今天快捷键。值一律是 yyyy-MM-dd 字符串。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "日期",
    modifier: Modifier = Modifier,
    showQuickChips: Boolean = true,
    trailingExtra: (@Composable () -> Unit)? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    val initialMillis = remember(value) {
        runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrNull()
    }

    Column(modifier) {
        AppTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = "YYYY-MM-DD",
            keyboardType = KeyboardType.Number,
            supportingText = null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "清空日期",
                            tint = Ink.TextMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onValueChange("") },
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = "选",
                        color = Ink.Blue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { showPicker = true },
                    )
                    Spacer(Modifier.width(4.dp))
                    trailingExtra?.invoke()
                }
            },
        )
        if (showQuickChips) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickDateChip("今天", Ink.Blue) { onValueChange(today.toString()) }
                QuickDateChip("昨天", Ink.Indigo) { onValueChange(today.minusDays(1).toString()) }
                QuickDateChip("前天", Ink.Indigo) { onValueChange(today.minusDays(2).toString()) }
            }
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onValueChange(date.toString())
                    }
                    showPicker = false
                }) { Text("确定", color = Ink.Blue) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消", color = Ink.TextSecondary) }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun QuickDateChip(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.3f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** 统一的确认弹窗。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    dismissText: String = "取消",
    danger: Boolean = false,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(title, color = Ink.TextPrimary, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column {
                Text(message, color = Ink.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                extraContent?.invoke(this)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (danger) Ink.Danger else Ink.Blue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = Ink.TextSecondary)
            }
        },
    )
}

/** 单行输入弹窗（房间/类目改名）。 */
@Composable
fun TextPromptDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String = "名称",
    confirmText: String = "保存",
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, color = Ink.TextPrimary, style = MaterialTheme.typography.titleMedium) },
        text = {
            AppTextField(
                value = value,
                onValueChange = { value = it },
                label = label,
                imeAction = ImeAction.Done,
                onDone = { if (value.isNotBlank()) onConfirm(value.trim()) },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text(confirmText, color = Ink.Blue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Ink.TextSecondary) }
        },
    )
}

/** 纯提示弹窗（导入报告、错误详情等）。 */
@Composable
fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "知道了",
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, color = Ink.TextPrimary, style = MaterialTheme.typography.titleMedium) },
        text = { Column(content = content) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(confirmText, color = Ink.Blue, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

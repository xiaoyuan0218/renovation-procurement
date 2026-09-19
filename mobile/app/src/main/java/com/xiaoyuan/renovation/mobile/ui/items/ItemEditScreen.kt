package com.xiaoyuan.renovation.mobile.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.mobile.data.model.statusLabel
import com.xiaoyuan.renovation.mobile.di.AppContainer
import com.xiaoyuan.renovation.mobile.ui.common.containerViewModel
import com.xiaoyuan.renovation.mobile.ui.design.AppDateField
import com.xiaoyuan.renovation.mobile.ui.design.MultiChoiceChips
import com.xiaoyuan.renovation.mobile.ui.design.AppNumberField
import com.xiaoyuan.renovation.mobile.ui.design.AppSelect
import com.xiaoyuan.renovation.mobile.ui.design.AppTextField
import com.xiaoyuan.renovation.mobile.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.mobile.ui.design.ErrorState
import com.xiaoyuan.renovation.mobile.ui.design.FloatingNotice
import com.xiaoyuan.renovation.mobile.ui.design.GhostButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassCard
import com.xiaoyuan.renovation.mobile.ui.design.GlassDivider
import com.xiaoyuan.renovation.mobile.ui.design.GlassIconButton
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.KeyValueRow
import com.xiaoyuan.renovation.mobile.ui.design.LoadingState
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.design.SectionTitle
import com.xiaoyuan.renovation.mobile.ui.design.TagPill
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import com.xiaoyuan.renovation.mobile.ui.theme.statusColor
import com.xiaoyuan.renovation.mobile.util.Fmt
import kotlinx.coroutines.delay

/**
 * 新增 / 编辑物料的全屏页。
 * 手机上放不下网页版那个四列弹窗，所以整页展开：基础信息 + 价格 + 采购记录 + 分配明细。
 */
@Composable
fun ItemEditScreen(
    container: AppContainer,
    itemId: Int,
    onClose: () -> Unit,
) {
    val vm = containerViewModel(container) { c -> ItemEditViewModel(c.repo) }
    val form by vm.form.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) { vm.start(itemId) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            vm.consumeMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onClose,
                contentDescription = "返回",
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (form.isNew) "新增物料" else "编辑物料",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.TextPrimary,
                )
                Text(
                    text = if (form.isNew) "填完记得点保存" else form.name.ifBlank { "未命名物料" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                )
            }
            NeonButton(
                text = "保存",
                onClick = { vm.save { onClose() } },
                icon = Icons.Filled.Check,
                loading = saving,
                enabled = !loading,
            )
        }

        when {
            loading -> Box(Modifier.weight(1f)) { LoadingState(text = "正在读取物料…") }

            loadError != null -> Box(Modifier.weight(1f)) {
                ErrorState(
                    message = loadError!!,
                    onRetry = { vm.retryLoad(itemId) },
                )
            }

            else -> {
                val preview = vm.preview(form)
                // 提示条浮在表单之上（不占布局空间）：插在滚动区里会顶着整张
                // 表单往下走，出现与消失时都在跳
                Box(Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(top = 6.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        /* ---------- 基本信息 ---------- */
                        Column {
                            SectionTitle("基本信息")
                            Spacer(Modifier.height(10.dp))
                            GlassCard {
                                AppTextField(
                                    value = form.name,
                                    onValueChange = { v -> vm.edit { it.copy(name = v) } },
                                    label = "名称",
                                    placeholder = "例如：抽纸",
                                    isError = form.name.isBlank() && !form.isNew,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AppTextField(
                                        value = form.brand,
                                        onValueChange = { v -> vm.edit { it.copy(brand = v) } },
                                        label = "品牌",
                                        modifier = Modifier.weight(1f),
                                    )
                                    AppTextField(
                                        value = form.model,
                                        onValueChange = { v -> vm.edit { it.copy(model = v) } },
                                        label = "型号",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                AppSelect(
                                    label = "分类",
                                    items = categories,
                                    selected = categories.firstOrNull { it.id == form.categoryId },
                                    itemLabel = { it.name },
                                    onSelect = { vm.edit { f -> f.copy(categoryId = it?.id) } },
                                    placeholder = "未分类",
                                    allowClear = true,
                                    clearLabel = "未分类",
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AppTextField(
                                        value = form.unit,
                                        onValueChange = { v -> vm.edit { it.copy(unit = v) } },
                                        label = "单位",
                                        placeholder = "个",
                                        modifier = Modifier.weight(1f),
                                    )
                                    AppNumberField(
                                        value = form.qtyTotal,
                                        onValueChange = { v -> vm.edit { it.copy(qtyTotal = v) } },
                                        label = "数量",
                                        enabled = !form.hasAllocations,
                                        supportingText = if (form.hasAllocations) "总量由分配合计决定" else null,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                AppTextField(
                                    value = form.note,
                                    onValueChange = { v -> vm.edit { it.copy(note = v) } },
                                    label = "备注",
                                    placeholder = "选填",
                                    minLines = 2,
                                    singleLine = false,
                                )
                            }
                        }

                        /* ---------- 价格 ---------- */
                        Column {
                            SectionTitle("价格", caption = "日常单价留空按原价算")
                            Spacer(Modifier.height(10.dp))
                            GlassCard {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AppNumberField(
                                        value = form.price,
                                        onValueChange = { v -> vm.edit { it.copy(price = v) } },
                                        label = "单价（原价）",
                                        modifier = Modifier.weight(1f),
                                    )
                                    AppNumberField(
                                        value = form.discountPrice,
                                        onValueChange = { v -> vm.edit { it.copy(discountPrice = v) } },
                                        label = "日常单价",
                                        accent = Ink.Cyan,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        /* ---------- 实时汇总 ---------- */
                        Column {
                            SectionTitle("汇总", caption = "按当前填写实时计算")
                            Spacer(Modifier.height(10.dp))
                            GlassCard(accent = statusColor(preview.status)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "采购状态",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink.TextSecondary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TagPill(
                                        text = if (preview.status == "partial") {
                                            "部分已买 ${Fmt.qty(preview.paidQty)}/${Fmt.qty(preview.totalQty)}"
                                        } else {
                                            statusLabel(preview.status)
                                        },
                                        color = statusColor(preview.status),
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                KeyValueRow("总数量", Fmt.qtyUnit(preview.totalQty, form.unit))
                                Spacer(Modifier.height(6.dp))
                                KeyValueRow("原价小计", Fmt.money(preview.listTotal))
                                Spacer(Modifier.height(6.dp))
                                KeyValueRow("日常价小计", Fmt.money(preview.discountTotal), valueColor = Ink.Cyan)
                                Spacer(Modifier.height(6.dp))
                                KeyValueRow("已付", Fmt.money(preview.paid), valueColor = Ink.Mint)
                                Spacer(Modifier.height(6.dp))
                                KeyValueRow("未付", Fmt.money(preview.unpaid), valueColor = Ink.Amber)
                                if (preview.paidUnitPrice != null) {
                                    Spacer(Modifier.height(6.dp))
                                    KeyValueRow("实付均价", Fmt.money(preview.paidUnitPrice), valueColor = Ink.Indigo)
                                }
                            }
                        }

                        /* ---------- 采购记录 ---------- */
                        Column {
                            SectionTitle(
                                title = "采购记录",
                                caption = "共 ${form.records.size} 笔 · 已付 ${Fmt.money(preview.paid)}",
                                trailing = {
                                    TagPill(
                                        text = "添加",
                                        color = Ink.Mint,
                                        onClick = vm::addRecordRow,
                                    )
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            if (form.records.isEmpty()) {
                                GlassCard(corner = 16.dp) {
                                    HintText("还没有采购记录。买过一次就记一笔，状态会自动变成「部分已买」或「已买完」。")
                                }
                            }
                            form.records.forEach { row ->
                                GlassCard(corner = 16.dp, padding = 12.dp) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AppNumberField(
                                            value = row.qty,
                                            onValueChange = { v -> vm.updateRecordRow(row.key) { it.copy(qty = v) } },
                                            label = "实付数量",
                                            modifier = Modifier.weight(1f),
                                        )
                                        AppNumberField(
                                            value = row.amount,
                                            onValueChange = { v -> vm.updateRecordRow(row.key) { it.copy(amount = v) } },
                                            label = "实付金额",
                                            accent = Ink.Mint,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    AppDateField(
                                        value = row.date,
                                        onValueChange = { v -> vm.updateRecordRow(row.key) { it.copy(date = v) } },
                                        showQuickChips = false,
                                    )
                                    // 什么时候记的：老数据与刚加的行还没有时间戳，就不显示
                                    if (row.createdAt.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        // 'YYYY-MM-DD HH:MM:SS' 掐掉年份，列表里空间紧
                                        HintText("记录于 " + row.createdAt.drop(5).take(11))
                                    }
                                    // 这条记录涉及哪几间：只列这条物料分到的分组
                                    val rowRooms = rooms.filter { r ->
                                        form.allocations.any { it.roomId == r.id }
                                    }
                                    if (rowRooms.isNotEmpty()) {
                                        Spacer(Modifier.height(10.dp))
                                        HintText("涉及分组（勾了谁就只往谁身上算）")
                                        Spacer(Modifier.height(6.dp))
                                        MultiChoiceChips(
                                            options = rowRooms.map { it.id to it.name },
                                            selected = row.roomIds,
                                            onToggle = { id ->
                                                vm.updateRecordRow(row.key) {
                                                    it.copy(
                                                        roomIds = if (id in it.roomIds) {
                                                            it.roomIds - id
                                                        } else {
                                                            it.roomIds + id
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AppTextField(
                                            value = row.vendor,
                                            onValueChange = { v ->
                                                vm.updateRecordRow(row.key) { it.copy(vendor = v) }
                                            },
                                            label = "商家",
                                            modifier = Modifier.weight(1f),
                                        )
                                        AppTextField(
                                            value = row.orderNo,
                                            onValueChange = { v ->
                                                vm.updateRecordRow(row.key) { it.copy(orderNo = v) }
                                            },
                                            label = "订单号",
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AppTextField(
                                            value = row.note,
                                            onValueChange = { v -> vm.updateRecordRow(row.key) { it.copy(note = v) } },
                                            label = "备注",
                                            accent = Ink.Indigo,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        GlassIconButton(
                                            icon = Icons.Filled.Delete,
                                            onClick = { vm.removeRecordRow(row.key) },
                                            contentDescription = "删除这条记录",
                                            tint = Ink.DangerSoft,
                                        )
                                    }
                                    val rowUnitPrice = Fmt.parseNumber(row.amount)?.let { amount ->
                                        Fmt.parseNumberOrZero(row.qty).takeIf { it > 0 }?.let { q -> amount / q }
                                    }
                                    if (rowUnitPrice != null) {
                                        Spacer(Modifier.height(8.dp))
                                        HintText("实付单价 ${Fmt.money(rowUnitPrice)}")
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }

                        /* ---------- 分配明细 ---------- */
                        Column {
                            SectionTitle(
                                title = "分配（按分组）",
                                caption = if (form.allocations.isEmpty()) {
                                    "还没有分配 · 总数量按上面的数量字段走"
                                } else {
                                    "共 ${Fmt.qtyUnit(preview.totalQty, form.unit)} · 原价小计 ${Fmt.money(preview.listTotal)}"
                                },
                                trailing = {
                                    TagPill(
                                        text = "添加",
                                        color = Ink.Indigo,
                                        onClick = vm::addAllocRow,
                                    )
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            if (form.allocations.isEmpty()) {
                                GlassCard(corner = 16.dp) {
                                    HintText("填了分配后，总数量会自动按各分组加起来，数量输入框会锁定。")
                                }
                            }
                            form.allocations.forEach { row ->
                                val usedElsewhere = form.allocations
                                    .filter { it.key != row.key }
                                    .mapNotNull { it.roomId }
                                    .toSet()
                                GlassCard(corner = 16.dp, padding = 12.dp) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(Modifier.weight(1.1f)) {
                                            AppSelect(
                                                label = "分组",
                                                items = rooms,
                                                selected = rooms.firstOrNull { it.id == row.roomId },
                                                itemLabel = { it.name },
                                                onSelect = { room ->
                                                    vm.updateAllocRow(row.key) { it.copy(roomId = room?.id) }
                                                },
                                                placeholder = "选分组",
                                                isItemEnabled = { room -> room.id !in usedElsewhere },
                                            )
                                        }
                                        AppNumberField(
                                            value = row.qty,
                                            onValueChange = { v -> vm.updateAllocRow(row.key) { it.copy(qty = v) } },
                                            label = "数量",
                                            modifier = Modifier.weight(0.8f),
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AppNumberField(
                                            value = row.priceOverride,
                                            onValueChange = { v ->
                                                vm.updateAllocRow(row.key) { it.copy(priceOverride = v) }
                                            },
                                            label = "覆盖单价",
                                            placeholder = "留空用物料单价",
                                            accent = Ink.Indigo,
                                            modifier = Modifier.weight(1f),
                                        )
                                        AppTextField(
                                            value = row.note,
                                            onValueChange = { v -> vm.updateAllocRow(row.key) { it.copy(note = v) } },
                                            label = "备注",
                                            accent = Ink.Indigo,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val unitPrice = Fmt.parseNumber(row.priceOverride)
                                            ?: Fmt.parseNumber(form.price)
                                            ?: 0.0
                                        HintText("该分组小计 ${Fmt.money(unitPrice * Fmt.parseNumberOrZero(row.qty))}")
                                        Spacer(Modifier.weight(1f))
                                        GhostButton(
                                            text = "移除",
                                            onClick = { vm.removeAllocRow(row.key) },
                                            contentColor = Ink.DangerSoft,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }

                        /* ---------- 删除 ---------- */
                        if (!form.isNew) {
                            GlassDivider()
                            GhostButton(
                                text = "删除这个物料",
                                onClick = { confirmDelete = true },
                                icon = Icons.Filled.Delete,
                                contentColor = Ink.DangerSoft,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            HintText("删除后会从清单里移走，它的采购记录与分配也一起收起来。")
                        }
                    }

                    if (message != null) {
                        FloatingNotice(
                            text = message!!,
                            accent = Ink.DangerSoft,
                            isError = true,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "移入回收站",
            message = "把「${form.name}」移入回收站？它的 ${form.records.size} 条采购记录与 "
                + "${form.allocations.size} 条分配都会留着，之后可以在「设置 → 回收站」里恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = false
                vm.delete { onClose() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

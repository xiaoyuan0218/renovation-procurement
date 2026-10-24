package com.xiaoyuan.renovation.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.data.model.LedgerRecordDto
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.ui.design.AppDateField
import com.xiaoyuan.renovation.ui.design.AppSelect
import com.xiaoyuan.renovation.ui.design.AppTextField
import com.xiaoyuan.renovation.ui.design.EmptyState
import com.xiaoyuan.renovation.ui.design.ErrorState
import com.xiaoyuan.renovation.ui.design.GhostButton
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.design.GlassPanel
import com.xiaoyuan.renovation.ui.design.LoadingState
import com.xiaoyuan.renovation.ui.design.TagPill
import com.xiaoyuan.renovation.ui.settings.SettingsPage
import com.xiaoyuan.renovation.ui.theme.Ink
import com.xiaoyuan.renovation.util.Fmt
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val LEDGER_PAGE_SIZE = 50

data class LedgerData(
    /** 拼平后的全量流水，已按日期倒序（同一天内后记的在前） */
    val rows: List<LedgerRecordDto> = emptyList(),
    /** 涉及分组 id → 名字，把 room_ids 翻译成人话 */
    val roomNames: Map<Int, String> = emptyMap(),
    /** 流水里实际出现过的商家，去重排序后当筛选选项 */
    val vendorOptions: List<String> = emptyList(),
)

/**
 * 跨物料的采购流水：像银行流水一样核对每一笔货款。
 * 记录随 /api/items 一起回来，这里本地拼平聚合（与网页版流水页同一套做法），
 * 筛选与合计都是净额口径 —— 退款记录的金额是负数，求和时自动抵回。
 */
class RecordsViewModel(private val repo: RenovationRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<LedgerData>>(LoadState.Loading)
    val state: StateFlow<LoadState<LedgerData>> = _state.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _vendor = MutableStateFlow("")
    val vendor: StateFlow<String> = _vendor.asStateFlow()

    private val _orderNo = MutableStateFlow("")
    val orderNo: StateFlow<String> = _orderNo.asStateFlow()

    private val _dateFrom = MutableStateFlow("")
    val dateFrom: StateFlow<String> = _dateFrom.asStateFlow()

    private val _dateTo = MutableStateFlow("")
    val dateTo: StateFlow<String> = _dateTo.asStateFlow()

    private val _page = MutableStateFlow(1)
    val page: StateFlow<Int> = _page.asStateFlow()

    private var loading = false

    fun setQuery(value: String) { _query.value = value; _page.value = 1 }
    fun setVendor(value: String) { _vendor.value = value; _page.value = 1 }
    fun setOrderNo(value: String) { _orderNo.value = value; _page.value = 1 }
    fun setDateFrom(value: String) { _dateFrom.value = value; _page.value = 1 }
    fun setDateTo(value: String) { _dateTo.value = value; _page.value = 1 }

    fun setPage(value: Int) { _page.value = value }

    /** 清空全部筛选条件（数据不动，纯本地过滤）。 */
    fun reset() {
        _query.value = ""
        _vendor.value = ""
        _orderNo.value = ""
        _dateFrom.value = ""
        _dateTo.value = ""
        _page.value = 1
    }

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading
            // 分组名只是列表里的点缀，拉不到不影响流水本身
            val itemsDeferred = async { repo.items() }
            val roomsDeferred = async { repo.rooms() }
            when (val result = itemsDeferred.await()) {
                is ApiResult.Ok -> {
                    val roomNames = (roomsDeferred.await() as? ApiResult.Ok)
                        ?.data?.associate { it.id to it.name } ?: emptyMap()
                    // 日期读出口是等宽的 YYYY-MM-DD，字典序就是时间序；
                    // 没填日期的（空串）最小，倒序时稳定沉底
                    val rows = result.data.flatMap { item ->
                        item.records.map { r ->
                            LedgerRecordDto(
                                itemId = item.id,
                                recordId = r.id,
                                itemName = item.name,
                                unit = item.unit,
                                qty = r.qty,
                                amount = r.amount,
                                isDeposit = r.isDeposit,
                                date = r.date,
                                note = r.note,
                                vendor = r.vendor,
                                orderNo = r.orderNo,
                                roomIds = r.roomIds,
                                createdAt = r.createdAt,
                            )
                        }
                    }.sortedWith(
                        compareByDescending<LedgerRecordDto> { it.date }
                            .thenByDescending { it.createdAt },
                    )
                    _state.value = LoadState.Ready(
                        LedgerData(
                            rows = rows,
                            roomNames = roomNames,
                            vendorOptions = rows.map { it.vendor.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .sorted(),
                        ),
                    )
                }

                is ApiResult.Err -> _state.value = LoadState.Failed(result.message, result.hint)
            }
            loading = false
        }
    }

    /** 客户端筛选：商家精确匹配、订单号包含、关键词搜「物料名/备注/商家/订单号」拼接串。 */
    fun filtered(data: LedgerData): List<LedgerRecordDto> {
        val q = _query.value.trim()
        val vendor = _vendor.value.trim()
        val orderNo = _orderNo.value.trim()
        val from = _dateFrom.value.trim()
        val to = _dateTo.value.trim()
        if (q.isEmpty() && vendor.isEmpty() && orderNo.isEmpty() && from.isEmpty() && to.isEmpty()) {
            return data.rows
        }
        return data.rows.filter { r ->
            if (vendor.isNotEmpty() && r.vendor.trim() != vendor) return@filter false
            if (orderNo.isNotEmpty() && !r.orderNo.contains(orderNo)) return@filter false
            // 没填日期的记录对不上范围：先不算进来，合计口径保持"看得见的都是范围内的"
            if (from.isNotEmpty() || to.isNotEmpty()) {
                if (r.date.isBlank()) return@filter false
                if (from.isNotEmpty() && r.date < from) return@filter false
                if (to.isNotEmpty() && r.date > to) return@filter false
            }
            if (q.isNotEmpty()) {
                val hay = "${r.itemName}\n${r.note}\n${r.vendor}\n${r.orderNo}"
                if (!hay.contains(q)) return@filter false
            }
            true
        }
    }
}

@Composable
fun RecordsScreen(vm: RecordsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val vendor by vm.vendor.collectAsStateWithLifecycle()
    val orderNo by vm.orderNo.collectAsStateWithLifecycle()
    val dateFrom by vm.dateFrom.collectAsStateWithLifecycle()
    val dateTo by vm.dateTo.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.load() }

    SettingsPage(
        title = "采购流水",
        caption = "跨物料的统一台账 · 按日期/商家/订单号对账",
        onBack = onBack,
    ) {
        item {
            AppTextField(
                value = query,
                onValueChange = vm::setQuery,
                label = "关键词",
                placeholder = "物料名、备注、商家、订单号",
                leadingIcon = Icons.Filled.Search,
                imeAction = ImeAction.Search,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppSelect(
                    label = "商家",
                    items = (state as? LoadState.Ready)?.data?.vendorOptions.orEmpty(),
                    selected = vendor.takeIf { it.isNotEmpty() },
                    itemLabel = { it },
                    onSelect = { vm.setVendor(it ?: "") },
                    placeholder = "全部商家",
                    allowClear = true,
                    clearLabel = "全部商家",
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                )
                AppTextField(
                    value = orderNo,
                    onValueChange = vm::setOrderNo,
                    label = "订单号",
                    placeholder = "包含即命中",
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppDateField(
                    value = dateFrom,
                    onValueChange = vm::setDateFrom,
                    label = "日期从",
                    showQuickChips = false,
                    modifier = Modifier.weight(1f),
                )
                AppDateField(
                    value = dateTo,
                    onValueChange = vm::setDateTo,
                    label = "日期到",
                    showQuickChips = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = "重置",
                    onClick = vm::reset,
                    icon = Icons.Filled.RestartAlt,
                )
                GhostButton(
                    text = "刷新",
                    onClick = vm::load,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        when (val current = state) {
            is LoadState.Loading -> item { LoadingState(text = "正在读取流水…") }

            is LoadState.Failed -> item {
                ErrorState(
                    message = current.message,
                    hint = current.hint,
                    onRetry = vm::load,
                )
            }

            is LoadState.Ready -> {
                val data = current.data
                val filtered = vm.filtered(data)
                val pageCount = if (filtered.isEmpty()) 1 else (filtered.size + LEDGER_PAGE_SIZE - 1) / LEDGER_PAGE_SIZE
                val safePage = page.coerceIn(1, pageCount)
                val pageItems = filtered.drop((safePage - 1) * LEDGER_PAGE_SIZE).take(LEDGER_PAGE_SIZE)

                item {
                    // 合计是净额：退款记录的金额是负数，求和时自动抵回（与网页版同一口径）
                    val totalAmount = filtered.sumOf { it.amount }
                    val refund = -filtered.filter { it.amount < 0.0 }.sumOf { it.amount }
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "共 ${filtered.size} 笔 · 净额 ${Fmt.money(totalAmount)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink.TextPrimary,
                            )
                            if (refund > 0.0) {
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text(
                                    text = "含退款 ${Fmt.money(refund)}",
                                    fontSize = 12.sp,
                                    color = Ink.Amber,
                                )
                            }
                        }
                    }
                }

                if (pageItems.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (data.rows.isEmpty()) "还没有采购记录" else "没有匹配的记录",
                            hint = if (data.rows.isEmpty()) null else "放宽条件或重置筛选再试",
                        )
                    }
                }

                items(pageItems, key = { "${it.itemId}-${it.recordId}" }) { record ->
                    LedgerRow(record = record, roomNames = data.roomNames)
                }

                if (pageCount > 1) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GhostButton(
                                text = "上一页",
                                onClick = { vm.setPage(safePage - 1) },
                                enabled = safePage > 1,
                            )
                            Text(
                                text = "第 $safePage / $pageCount 页 · 共 ${filtered.size} 笔",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.TextSecondary,
                            )
                            GhostButton(
                                text = "下一页",
                                onClick = { vm.setPage(safePage + 1) },
                                enabled = safePage < pageCount,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerRow(record: LedgerRecordDto, roomNames: Map<Int, String>) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.itemName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (record.isDeposit) {
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        TagPill(text = "定金", color = Ink.Amber)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(record.date.ifBlank { "未填日期" })
                        append(" · ")
                        append(Fmt.qty(record.qty))
                        if (record.unit.isNotBlank()) append(record.unit)
                        if (record.vendor.isNotBlank()) append(" · ").append(record.vendor)
                        if (record.orderNo.isNotBlank()) append(" · 单").append(record.orderNo)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val rooms = record.roomIds.mapNotNull { roomNames[it] }
                if (rooms.isNotEmpty() || record.note.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            if (rooms.isNotEmpty()) append(rooms.joinToString("、"))
                            if (record.note.isNotBlank()) {
                                if (rooms.isNotEmpty()) append(" · ")
                                append(record.note)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 退款是负数金额：跟着净额口径直接显示负值
            Text(
                text = Fmt.money(record.amount),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (record.amount < 0) Ink.Amber else Ink.Mint,
            )
        }
    }
}

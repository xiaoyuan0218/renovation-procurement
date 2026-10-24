package com.xiaoyuan.renovation.mobile.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.model.LedgerRecordDto
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import com.xiaoyuan.renovation.mobile.data.repo.okData
import com.xiaoyuan.renovation.mobile.ui.common.LoadState
import com.xiaoyuan.renovation.mobile.ui.design.AppDateField
import com.xiaoyuan.renovation.mobile.ui.design.AppTextField
import com.xiaoyuan.renovation.mobile.ui.design.EmptyState
import com.xiaoyuan.renovation.mobile.ui.design.ErrorState
import com.xiaoyuan.renovation.mobile.ui.design.GhostButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassCard
import com.xiaoyuan.renovation.mobile.ui.design.GlassPanel
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.LoadingState
import com.xiaoyuan.renovation.mobile.ui.design.TagPill
import com.xiaoyuan.renovation.mobile.ui.settings.SettingsPage
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import com.xiaoyuan.renovation.mobile.util.Fmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 流水页要的数据：拼平的全部记录 + 筛选结果 + 合计 + 分组名对照。 */
data class LedgerData(
    /** 全部记录（没筛过）：给「上次记到谁」这类全量参考用 */
    val all: List<LedgerRecordDto> = emptyList(),
    /** 当前筛选条件下的记录（已按日期倒序） */
    val rows: List<LedgerRecordDto> = emptyList(),
    /** 筛选结果的金额合计（定金、负优惠都如实计入） */
    val totalAmount: Double = 0.0,
    /** 涉及分组 id → 名字，把 room_ids 翻译成人话 */
    val roomNames: Map<Int, String> = emptyMap(),
)

class RecordsViewModel(private val repo: LocalRepository) : ViewModel() {

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

    private var loadedKey: Int? = null

    private var loading = false

    fun setQuery(value: String) { _query.value = value }
    fun setVendor(value: String) { _vendor.value = value }
    fun setOrderNo(value: String) { _orderNo.value = value }
    fun setDateFrom(value: String) { _dateFrom.value = value }
    fun setDateTo(value: String) { _dateTo.value = value }

    fun reset() {
        _query.value = ""
        _vendor.value = ""
        _orderNo.value = ""
        _dateFrom.value = ""
        _dateTo.value = ""
    }

    /** 只在数据版本变过时才重读（与清单页同一套节奏），筛选拼平在本地做，即时生效。 */
    fun loadIfStale(refreshKey: Int) {
        val stale = loadedKey != refreshKey
        val failed = _state.value is LoadState.Failed
        if (!stale && !failed) return
        loadedKey = refreshKey
        load()
    }

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading
            // 分组名只是行上的点缀，拉不到不影响流水本身
            val itemsResult = repo.items()
            val roomsResult = repo.rooms()
            when (val result = itemsResult) {
                is ApiResult.Ok -> {
                    val roomNames = (roomsResult.okData ?: emptyList())
                        .associate { it.id to it.name }
                    val all = flatten(result.data)
                    _state.value = LoadState.Ready(
                        LedgerData(all = all, rows = all, roomNames = roomNames),
                    )
                }

                is ApiResult.Err ->
                    _state.value = LoadState.Failed(result.message, result.hint)
            }
            loading = false
        }
    }

    /** 物料记录拼平成一行一条：名字、单位跟过来，日期走读出口径（已归一化）。 */
    private fun flatten(items: List<ItemDto>) =
        items.flatMap { item ->
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
        }
}

/**
 * 跨物料的采购流水：像银行流水一样核对每一笔货款。
 * 大促对账、找某笔订单、按商家汇总，不用再逐个物料翻详情。
 */
@Composable
fun RecordsScreen(vm: RecordsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val vendor by vm.vendor.collectAsStateWithLifecycle()
    val orderNo by vm.orderNo.collectAsStateWithLifecycle()
    val dateFrom by vm.dateFrom.collectAsStateWithLifecycle()
    val dateTo by vm.dateTo.collectAsStateWithLifecycle()

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
                imeAction = ImeAction.Done,
                onClear = { vm.setQuery("") },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = vendor,
                    onValueChange = vm::setVendor,
                    label = "商家",
                    placeholder = "如：淘宝",
                    modifier = Modifier.weight(1f),
                    onClear = { vm.setVendor("") },
                )
                AppTextField(
                    value = orderNo,
                    onValueChange = vm::setOrderNo,
                    label = "订单号",
                    placeholder = "精确匹配",
                    modifier = Modifier.weight(1f),
                    onClear = { vm.setOrderNo("") },
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
            GhostButton(
                text = "重置筛选",
                onClick = vm::reset,
                icon = Icons.Filled.RestartAlt,
                modifier = Modifier.fillMaxWidth(),
            )
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
                // 本地筛选零成本，输入即生效；排序与网页版同一口径：
                // 日期倒序（没填日期的沉底），同日内后记的排前面。
                // 这里是 LazyColumn 的构建段（每次重组跑一遍），几百条的
                // filter/sort 可忽略，不值得为了 remember 把代码搬进 item
                val keyword = query.trim()
                val rows = data.rows
                    .filter { r ->
                        val matchKeyword = keyword.isEmpty() ||
                            r.itemName.contains(keyword, ignoreCase = true) ||
                            r.note.contains(keyword, ignoreCase = true) ||
                            r.vendor.contains(keyword, ignoreCase = true) ||
                            r.orderNo.contains(keyword, ignoreCase = true)
                        val matchVendor = vendor.isBlank() ||
                            r.vendor.contains(vendor.trim(), ignoreCase = true)
                        val matchOrder = orderNo.isBlank() || r.orderNo == orderNo.trim()
                        val matchDate = (dateFrom.isBlank() && dateTo.isBlank()) ||
                            (r.date.isNotEmpty() &&
                                (dateFrom.isBlank() || r.date >= dateFrom) &&
                                (dateTo.isBlank() || r.date <= dateTo))
                        matchKeyword && matchVendor && matchOrder && matchDate
                    }
                    .sortedWith(
                        compareByDescending<LedgerRecordDto> { it.date }
                            .thenByDescending { it.createdAt },
                    )
                val totalAmount = rows.sumOf { it.amount }

                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "共 ${rows.size} 笔",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ink.TextPrimary,
                            )
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text(
                                text = "合计 ${Fmt.money(totalAmount)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink.Mint,
                            )
                        }
                    }
                }

                if (rows.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (data.all.isEmpty()) "还没记过采购" else "没有匹配的记录",
                            hint = if (data.all.isEmpty()) "在清单里点物料的采购状态标签记第一笔" else "放宽条件或重置筛选再试",
                        )
                    }
                }

                items(rows, key = { "${it.itemId}-${it.recordId}" }) { record ->
                    LedgerRow(record = record, roomNames = data.roomNames)
                }

                if (rows.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        HintText("日期倒序 · 定金也算已付的一笔，找订单按订单号搜最快")
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
                        text = record.itemName.ifBlank { "已删除的物料" },
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
            Text(
                text = Fmt.money(record.amount),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.Mint,
            )
        }
    }
}

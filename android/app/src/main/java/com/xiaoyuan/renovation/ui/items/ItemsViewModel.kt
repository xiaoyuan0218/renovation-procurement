package com.xiaoyuan.renovation.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.model.CategoryDto
import com.xiaoyuan.renovation.data.model.ItemDto
import com.xiaoyuan.renovation.data.model.RecordInDto
import com.xiaoyuan.renovation.data.model.RoomDto
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.data.repo.okData
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.ui.common.dataOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class StatusFilter(val label: String) {
    All("全部"),
    Pending("未买齐"),
    Unbought("未买"),
    Partial("部分已买"),
    Done("已买完"),
}

/** 清单页需要的数据：物料 + 类目（筛选下拉）+ 房间（编辑弹层里用）。 */
data class ItemsData(
    val items: List<ItemDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val rooms: List<RoomDto> = emptyList(),
)

class ItemsViewModel(private val repo: RenovationRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<ItemsData>>(LoadState.Loading)
    val state: StateFlow<LoadState<ItemsData>> = _state.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _categoryId = MutableStateFlow<Int?>(null)
    val categoryId: StateFlow<Int?> = _categoryId.asStateFlow()

    private val _status = MutableStateFlow(StatusFilter.All)
    val status: StateFlow<StatusFilter> = _status.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selected = MutableStateFlow<Set<Int>>(emptySet())
    val selected: StateFlow<Set<Int>> = _selected.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var loading = false

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading

            val itemsResult = repo.items()
            val categoriesResult = repo.categories()
            val roomsResult = repo.rooms()

            val failure = listOf(itemsResult, categoriesResult, roomsResult)
                .filterIsInstance<ApiResult.Err>()
                .firstOrNull()

            if (failure != null) {
                _state.value = LoadState.Failed(failure.message, failure.hint)
            } else {
                val items = itemsResult.okData.orEmpty()
                _state.value = LoadState.Ready(
                    ItemsData(
                        items = items,
                        categories = categoriesResult.okData.orEmpty(),
                        rooms = roomsResult.okData.orEmpty(),
                    ),
                )
                // 物料被别处删掉后，选中集合里的悬空 id 要清掉
                _selected.value = _selected.value intersect items.map { it.id }.toSet()
            }
            loading = false
        }
    }

    /* ---------- 筛选 ---------- */

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setCategory(id: Int?) {
        _categoryId.value = id
    }

    fun setStatus(value: StatusFilter) {
        _status.value = value
    }

    /** 总览页「去处理」跳过来时用。 */
    fun focusPending() {
        _status.value = StatusFilter.Pending
        _query.value = ""
        _categoryId.value = null
    }

    fun filtered(data: ItemsData): List<ItemDto> {
        val keyword = _query.value.trim()
        val categoryId = _categoryId.value
        val filter = _status.value
        return data.items
            .filter { item ->
                val matchKeyword = keyword.isEmpty() || item.name.contains(keyword, ignoreCase = true)
                val matchCategory = categoryId == null || item.categoryId == categoryId
                val matchStatus = when (filter) {
                    StatusFilter.All -> true
                    StatusFilter.Pending -> item.status == "unbought" || item.status == "partial"
                    StatusFilter.Unbought -> item.status == "unbought"
                    StatusFilter.Partial -> item.status == "partial"
                    StatusFilter.Done -> item.status == "done"
                }
                matchKeyword && matchCategory && matchStatus
            }
            .sortedByDescending { it.discountTotal }
    }

    /* ---------- 多选与批量删除 ---------- */

    fun setSelectionMode(enabled: Boolean) {
        _selectionMode.value = enabled
        if (!enabled) _selected.value = emptySet()
    }

    fun toggleSelect(id: Int) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun selectAll(ids: List<Int>) {
        _selected.value = if (_selected.value.containsAll(ids) && ids.isNotEmpty()) emptySet() else ids.toSet()
    }

    fun deleteSelected(onDone: () -> Unit = {}) {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.batchDeleteItems(ids)) {
                is ApiResult.Ok -> {
                    _message.value = "已删除 ${result.data.deleted} 项物料"
                    _selected.value = emptySet()
                    _selectionMode.value = false
                    load()
                    onDone()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun deleteItem(id: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.deleteItem(id)) {
                is ApiResult.Ok -> {
                    _message.value = "已删除"
                    load()
                    onDone()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    /* ---------- 记一笔采购 ---------- */

    fun addRecord(
        itemId: Int,
        qty: Double,
        amount: Double,
        date: String,
        note: String,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _busy.value = true
            val result = repo.addRecord(itemId, RecordInDto(qty = qty, amount = amount, date = date, note = note))
            when (result) {
                is ApiResult.Ok -> {
                    _message.value = "已记录一笔"
                    load()
                    onDone()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun clearRecords(itemId: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.clearRecords(itemId)) {
                is ApiResult.Ok -> {
                    _message.value = "已清零"
                    load()
                    onDone()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun deleteRecord(recordId: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.deleteRecord(recordId)) {
                is ApiResult.Ok -> {
                    _message.value = "已删除该笔记录"
                    load()
                    onDone()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun findItem(id: Int): ItemDto? = _state.value.dataOrNull?.items?.firstOrNull { it.id == id }
}

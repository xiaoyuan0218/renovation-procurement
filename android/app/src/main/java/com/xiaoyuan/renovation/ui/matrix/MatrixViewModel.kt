package com.xiaoyuan.renovation.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.model.MatrixDto
import com.xiaoyuan.renovation.data.model.MatrixItemDto
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.ui.common.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MatrixMode { List, Grid }

class MatrixViewModel(private val repo: RenovationRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<MatrixDto>>(LoadState.Loading)
    val state: StateFlow<LoadState<MatrixDto>> = _state.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _onlyPending = MutableStateFlow(false)
    val onlyPending: StateFlow<Boolean> = _onlyPending.asStateFlow()

    private val _mode = MutableStateFlow(MatrixMode.List)
    val mode: StateFlow<MatrixMode> = _mode.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private var loading = false

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading
            when (val result = repo.matrix()) {
                is ApiResult.Ok -> _state.value = LoadState.Ready(result.data)
                is ApiResult.Err -> _state.value = LoadState.Failed(result.message, result.hint)
            }
            loading = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setMode(value: MatrixMode) {
        _mode.value = value
    }

    fun toggleOnlyPending() {
        _onlyPending.value = !_onlyPending.value
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 保存单元格布点：数量为 0 时后端会删掉这条布点。 */
    fun saveCell(
        itemId: Int,
        roomId: Int,
        qty: Double,
        priceOverride: Double?,
        note: String,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _saving.value = true
            when (val result = repo.saveCell(itemId, roomId, qty, priceOverride, note)) {
                is ApiResult.Ok -> {
                    _message.value = if (result.data.deleted) "已清空该布点" else "已保存"
                    load()
                    onSaved()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _saving.value = false
        }
    }

    /** 只看未买齐：与网页版一致，未买 + 部分已买。 */
    fun filter(items: List<MatrixItemDto>, query: String, onlyPending: Boolean): List<MatrixItemDto> {
        val keyword = query.trim()
        return items.filter { item ->
            val matchKeyword = keyword.isEmpty() || item.name.contains(keyword, ignoreCase = true)
            val matchPending = !onlyPending || item.status == "unbought" || item.status == "partial"
            matchKeyword && matchPending
        }
    }
}

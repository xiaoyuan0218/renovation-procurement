package com.xiaoyuan.renovation.mobile.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.mobile.data.model.MatrixDto
import com.xiaoyuan.renovation.mobile.data.model.MatrixItemDto
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import com.xiaoyuan.renovation.mobile.ui.common.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MatrixMode { List, Grid }

class MatrixViewModel(private val repo: LocalRepository) : ViewModel() {

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

    private var loadedKey: Int? = null

    /**
     * 只在数据版本变过时才重读。
     *
     * 切个标签页回来、或者从别的页面返回，都不该重新读库再全量算一遍 ——
     * 那会让"进入页面"多等一拍，手快一点就是肉眼可见的卡顿。失败过的要允许重试，
     * 所以出错时不记住这个版本号。
     */
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

    /** 保存单元格分配：数量为 0 时后端会删掉这条分配。 */
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
                    _message.value = if (result.data.deleted) "已清空该分配" else "已保存"
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

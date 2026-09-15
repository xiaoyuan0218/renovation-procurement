package com.xiaoyuan.renovation.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.model.SummaryDto
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.ui.common.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(private val repo: RenovationRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SummaryDto>>(LoadState.Loading)
    val state: StateFlow<LoadState<SummaryDto>> = _state.asStateFlow()

    private var loading = false

    /** 首次进来显示骨架，之后刷新保留旧数据避免闪烁。 */
    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading
            when (val result = repo.summary()) {
                is ApiResult.Ok -> _state.value = LoadState.Ready(result.data)
                is ApiResult.Err -> _state.value = LoadState.Failed(result.message, result.hint)
            }
            loading = false
        }
    }
}

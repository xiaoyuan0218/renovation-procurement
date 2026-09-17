package com.xiaoyuan.renovation.mobile.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.mobile.data.model.SummaryDto
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import com.xiaoyuan.renovation.mobile.data.repo.okData
import com.xiaoyuan.renovation.mobile.ui.common.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 总览的数字全部来自 [SummaryCompute] —— 它与后端 `summary.py` 的口径一致
 * （同一批对照数据在两端跑过，逐字段相等）。
 */
data class DashboardData(
    val listName: String = "",
    val summary: SummaryDto = SummaryDto(),
)

class DashboardViewModel(private val repo: LocalRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<DashboardData>>(LoadState.Loading)
    val state: StateFlow<LoadState<DashboardData>> = _state.asStateFlow()

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

            val listResult = repo.currentList()
            val summaryResult = repo.summary()

            val failure = listOf(listResult, summaryResult)
                .filterIsInstance<ApiResult.Err>()
                .firstOrNull()

            _state.value = if (failure != null) {
                LoadState.Failed(failure.message, failure.hint)
            } else {
                LoadState.Ready(
                    DashboardData(
                        listName = listResult.okData?.name.orEmpty(),
                        summary = summaryResult.okData ?: SummaryDto(),
                    ),
                )
            }
            loading = false
        }
    }
}

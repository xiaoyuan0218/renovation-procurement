package com.xiaoyuan.renovation.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.mobile.data.model.CategoryDto
import com.xiaoyuan.renovation.mobile.data.model.ExpenseDto
import com.xiaoyuan.renovation.mobile.data.model.ExpenseInDto
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.model.RoomDto
import com.xiaoyuan.renovation.mobile.data.model.TrashItemDto
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import com.xiaoyuan.renovation.mobile.data.repo.okData
import com.xiaoyuan.renovation.mobile.ui.common.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页管的东西：分组与分类、额外费用、回收站。
 * 前两样都是**当前清单**的；费用与回收站也是。
 */
data class SettingsData(
    val rooms: List<RoomDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val expenses: List<ExpenseDto> = emptyList(),
    val trash: List<TrashItemDto> = emptyList(),
    /** 费用的「关联物料」下拉用 */
    val items: List<ItemDto> = emptyList(),
) {
    val expensesTotal: Double get() = expenses.sumOf { it.amount }

    /** 已经用过的费用类型（下拉的候选，按出现顺序） */
    val expenseKinds: List<String>
        get() = expenses.map { it.kind }.filter { it.isNotBlank() }.distinct()
}

class SettingsViewModel(private val repo: LocalRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SettingsData>>(LoadState.Loading)
    val state: StateFlow<LoadState<SettingsData>> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 上一条 [message] 是失败来的吗（决定提示条用红还是薄荷绿）。 */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

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
            val roomsResult = repo.rooms()
            val categoriesResult = repo.categories()
            val expensesResult = repo.expenses()
            val trashResult = repo.trash()
            val itemsResult = repo.items()

            val failure = listOf(roomsResult, categoriesResult, expensesResult, trashResult, itemsResult)
                .filterIsInstance<ApiResult.Err>()
                .firstOrNull()

            _state.value = if (failure != null) {
                LoadState.Failed(failure.message, failure.hint)
            } else {
                LoadState.Ready(
                    SettingsData(
                        rooms = roomsResult.okData.orEmpty(),
                        categories = categoriesResult.okData.orEmpty(),
                        expenses = expensesResult.okData.orEmpty(),
                        trash = trashResult.okData.orEmpty(),
                        items = itemsResult.okData.orEmpty(),
                    ),
                )
            }
            loading = false
        }
    }

    /* ---------- 分组 ---------- */

    fun createRoom(name: String) =
        submit("已新增分组「${name.trim()}」") { repo.createRoom(name) }

    fun renameRoom(id: Int, name: String) =
        submit("已改名为「${name.trim()}」") { repo.renameRoom(id, name) }

    /** 删分组：它的布点跟着走，采购记录只解除关联（钱还在）。 */
    fun deleteRoom(id: Int) = submit("分组已删除") { repo.deleteRoom(id) }

    /* ---------- 分类 ---------- */

    fun createCategory(name: String) =
        submit("已新增分类「${name.trim()}」") { repo.createCategory(name) }

    fun renameCategory(id: Int, name: String) =
        submit("已改名为「${name.trim()}」") { repo.renameCategory(id, name) }

    /** 删分类：下面还挂着物料时会被拒绝（与电脑端同口径）。 */
    fun deleteCategory(id: Int) = submit("分类已删除") { repo.deleteCategory(id) }

    /* ---------- 额外费用（运费/安装费） ---------- */

    fun createExpense(body: ExpenseInDto) = submit("已记录这笔费用") { repo.createExpense(body) }

    fun updateExpense(id: Int, body: ExpenseInDto) = submit("已保存") { repo.updateExpense(id, body) }

    fun deleteExpense(id: Int) = submit("费用已删除") { repo.deleteExpense(id) }

    /* ---------- 回收站 ---------- */

    fun restoreItem(id: Int) = submit("已恢复这条物料") { repo.restoreItem(id) }

    /** 彻底删除：连它的分配与采购记录一起，不可恢复。 */
    fun purgeItem(id: Int) = submit("已彻底删除") { repo.purgeItem(id) }

    fun purgeTrash(count: Int) = submit("已彻底删除 $count 条") { repo.purgeTrash() }

    fun consumeMessage() {
        _message.value = null
    }

    /** 写操作统一的收尾：提示 + 重新读一遍（列表与计数都是新的）。 */
    private fun submit(success: String, call: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            _busy.value = true
            when (val result = call()) {
                is ApiResult.Ok -> {
                    _messageIsError.value = false
                    _message.value = success
                    load()
                }

                is ApiResult.Err -> {
                    _messageIsError.value = true
                    _message.value = result.message
                }
            }
            _busy.value = false
        }
    }
}

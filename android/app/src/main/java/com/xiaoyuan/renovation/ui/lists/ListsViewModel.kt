package com.xiaoyuan.renovation.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.model.ItemListDto
import com.xiaoyuan.renovation.data.prefs.SettingsStore
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 清单：互相隔离的多份项目。当前是哪一份属于全局上下文（每个请求都要带），
 * 所以这个 VM 挂在主壳上，给顶部那条切换栏用。
 *
 * 切换只改本地记录 + 让各页面重新拉数据 —— 请求头由 OkHttp 拦截器统一加，
 * 页面自己不用关心当前在哪份清单，也就不会漏掉某个接口。
 */
class ListsViewModel(
    private val repo: RenovationRepository,
    private val settings: SettingsStore,
    private val onSwitched: () -> Unit,
) : ViewModel() {

    private val _lists = MutableStateFlow<List<ItemListDto>>(emptyList())
    val lists: StateFlow<List<ItemListDto>> = _lists.asStateFlow()

    val currentId: StateFlow<Int?> = settings.currentListId

    /** 一次性的提示（新建成功、操作失败……），界面提示过就清掉。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load() {
        viewModelScope.launch {
            when (val res = repo.lists()) {
                is ApiResult.Ok -> {
                    _lists.value = res.data
                    // 记住的那份可能已经在别的设备上被删了，那就退回第一份
                    val id = currentId.value
                    if (res.data.isNotEmpty() && res.data.none { it.id == id }) {
                        switchTo(res.data.first().id)
                    }
                }
                is ApiResult.Err -> _message.value = res.message
            }
        }
    }

    fun select(id: Int) {
        if (id == currentId.value) return
        viewModelScope.launch { switchTo(id) }
    }

    /**
     * 新建清单。[copyFrom] 传另一份清单的 id 时照抄它的分组与分类（不带物料），
     * 不传就是一张空白清单。
     */
    fun create(name: String, copyFrom: Int? = null) {
        viewModelScope.launch {
            when (val res = repo.createList(name, copyFrom = copyFrom)) {
                is ApiResult.Ok -> {
                    _lists.value = _lists.value + res.data
                    val copied = if (res.data.roomCount > 0) {
                        "，已复制 ${res.data.roomCount} 个分组、${res.data.categoryCount} 个分类"
                    } else {
                        ""
                    }
                    _message.value = "已新建「${res.data.name}」并切换过去$copied"
                    switchTo(res.data.id)
                }
                is ApiResult.Err -> _message.value = res.message
            }
        }
    }

    fun rename(id: Int, name: String) {
        val old = _lists.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            when (val res = repo.renameList(id, name, old.note, old.sort)) {
                is ApiResult.Ok -> {
                    _lists.value = _lists.value.map { if (it.id == id) res.data else it }
                    _message.value = "已改名为「${res.data.name}」"
                }
                is ApiResult.Err -> _message.value = res.message
            }
        }
    }

    fun delete(id: Int) {
        viewModelScope.launch {
            when (val res = repo.deleteList(id)) {
                is ApiResult.Ok -> {
                    _lists.value = _lists.value.filterNot { it.id == id }
                    _message.value = "清单已删除"
                    // 删掉的正是当前这份：切到剩下的第一份
                    if (currentId.value == id) {
                        val next = _lists.value.firstOrNull()
                        if (next != null) switchTo(next.id) else onSwitched()
                    }
                }
                is ApiResult.Err -> _message.value = res.message
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private suspend fun switchTo(id: Int) {
        settings.setCurrentListId(id)
        onSwitched()
    }
}

package com.xiaoyuan.renovation.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.model.CategoryDto
import com.xiaoyuan.renovation.data.model.ExpenseDto
import com.xiaoyuan.renovation.data.model.ExpenseInDto
import com.xiaoyuan.renovation.data.model.ImportReportDto
import com.xiaoyuan.renovation.data.model.ItemDto
import com.xiaoyuan.renovation.data.model.RoomDto
import com.xiaoyuan.renovation.data.model.TrashItemDto
import com.xiaoyuan.renovation.data.prefs.SettingsStore
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.data.repo.okData
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.util.FileUtils
import com.xiaoyuan.renovation.util.Fmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsData(
    val rooms: List<RoomDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    /** 回收站里的条目（删掉的物料，可以捞回来） */
    val trash: List<TrashItemDto> = emptyList(),
    /** 额外费用：运费/安装费这类不进单价的支出 */
    val expenses: List<ExpenseDto> = emptyList(),
    /** 费用可以关联到某条物料的货，这里给它备着选项 */
    val items: List<ItemDto> = emptyList(),
)

/** 导入方式：覆盖 or 按名称合并。 */
enum class ImportMode(val label: String, val wire: String) {
    Replace("覆盖现有数据", "replace"),
    Merge("按名称合并", "merge"),
}

class SettingsViewModel(
    private val repo: RenovationRepository,
    private val settings: SettingsStore,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<SettingsData>>(LoadState.Loading)
    val state: StateFlow<LoadState<SettingsData>> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _importMode = MutableStateFlow(ImportMode.Merge)
    val importMode: StateFlow<ImportMode> = _importMode.asStateFlow()

    private val _report = MutableStateFlow<ImportReportDto?>(null)
    val report: StateFlow<ImportReportDto?> = _report.asStateFlow()

    private val _connectionOk = MutableStateFlow<Boolean?>(null)
    val connectionOk: StateFlow<Boolean?> = _connectionOk.asStateFlow()

    private var loading = false

    val baseUrl: StateFlow<String?> get() = settings.baseUrl

    val username: StateFlow<String?> get() = settings.username

    /**
     * 退出登录：先让服务端清掉 Cookie（失败也无所谓，本地才是权威），
     * 再清本地 token —— 顶层路由监听到 sessionState 变化会自动切回登录页。
     * 服务器地址保留，不用重新配置。
     */
    fun logout() {
        viewModelScope.launch {
            repo.logout()
            settings.clearSession()
        }
    }

    /** 动作完之后刷新：先把闸门放开，否则连着的 load() 会被挡掉 */
    fun reload() {
        loading = false
        load()
    }

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading
            val roomsResult = repo.rooms()
            val categoriesResult = repo.categories()
            val trashResult = repo.trash()
            val expensesResult = repo.expenses()
            // 关联物料只是费用表单里的选填项，拉不到不影响页面
            val itemsResult = repo.items()
            val failure = listOf(roomsResult, categoriesResult, trashResult, expensesResult)
                .filterIsInstance<ApiResult.Err>()
                .firstOrNull()

            if (failure != null) {
                _state.value = LoadState.Failed(failure.message, failure.hint)
            } else {
                _state.value = LoadState.Ready(
                    SettingsData(
                        rooms = roomsResult.okData.orEmpty(),
                        categories = categoriesResult.okData.orEmpty(),
                        trash = trashResult.okData.orEmpty(),
                        expenses = expensesResult.okData.orEmpty(),
                        items = itemsResult.okData.orEmpty(),
                    ),
                )
            }
            loading = false
        }
    }

    /* ---------- 连接 ---------- */

    fun testConnection() {
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.testConnection()) {
                is ApiResult.Ok -> {
                    _connectionOk.value = true
                    _message.value = "连接正常 · 共 ${result.data.totals.itemCount} 项物料"
                }

                is ApiResult.Err -> {
                    _connectionOk.value = false
                    _message.value = result.message
                }
            }
            _busy.value = false
        }
    }

    /* ---------- 分组 ---------- */

    fun addRoom(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.createRoom(trimmed)) {
                is ApiResult.Ok -> {
                    _message.value = "已添加分组「${result.data.name}」"
                    load()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun renameRoom(id: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.renameRoom(id, trimmed)) {
                is ApiResult.Ok -> {
                    _message.value = "已改名"
                    load()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun deleteRoom(id: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.deleteRoom(id)) {
                is ApiResult.Ok -> {
                    _message.value = "已删除分组"
                    load()
                    onDone()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    /* ---------- 分类 ---------- */

    fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.createCategory(trimmed)) {
                is ApiResult.Ok -> {
                    _message.value = "已添加分类「${result.data.name}」"
                    load()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun renameCategory(id: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.renameCategory(id, trimmed)) {
                is ApiResult.Ok -> {
                    _message.value = "已改名"
                    load()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun deleteCategory(id: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.deleteCategory(id)) {
                is ApiResult.Ok -> {
                    _message.value = "已删除分类"
                    load()
                    onDone()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    /* ---------- 导入导出 ---------- */

    fun setImportMode(mode: ImportMode) {
        _importMode.value = mode
    }

    /** 导出 xlsx 并唤起系统分享（可存文件、发微信、发邮件）。 */
    fun exportToShare() {
        downloadAndShare(fallbackName = "采知道.xlsx", isTemplate = false)
    }

    fun downloadTemplateToShare() {
        downloadAndShare(fallbackName = "采知道 导入模板.xlsx", isTemplate = true)
    }

    private fun downloadAndShare(fallbackName: String, isTemplate: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            val result = if (isTemplate) repo.downloadTemplate() else repo.downloadExport()
            when (result) {
                is ApiResult.Ok -> {
                    val file = result.data
                    val written = FileUtils.writeToExports(appContext, file.fileName.ifBlank { fallbackName }, file.bytes)
                    if (written == null) {
                        _message.value = "文件写入失败"
                    } else {
                        val shared = FileUtils.share(
                            context = appContext,
                            uri = written.second,
                            mime = RenovationRepository.XLSX_MIME,
                            title = if (isTemplate) "分享导入模板" else "分享采知道",
                        )
                        _message.value = if (shared) {
                            "已生成 ${written.first.name}（${file.bytes.size / 1024} KB）"
                        } else {
                            "文件已生成（${written.first.name}），但系统里没有能接收它的应用"
                        }
                    }
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    /** 读取用户选中的 xlsx 并上传导入。 */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val bytes = FileUtils.readBytes(appContext, uri)
            if (bytes == null || bytes.isEmpty()) {
                _message.value = "读不到这个文件"
                _busy.value = false
                return@launch
            }
            val name = FileUtils.displayName(appContext, uri)
            when (val result = repo.importExcel(bytes, name, _importMode.value.wire)) {
                is ApiResult.Ok -> {
                    _report.value = result.data
                    _message.value = "导入完成"
                    load()
                }

                is ApiResult.Err -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun consumeReport() {
        _report.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    /* ---------- 回收站 ---------- */

    fun restoreItem(id: Int) {
        viewModelScope.launch {
            when (val r = repo.restoreItem(id)) {
                is ApiResult.Ok -> {
                    _message.value = "已恢复「${r.data.name}」"
                    reload()
                }
                is ApiResult.Err -> _message.value = r.message
            }
        }
    }

    fun purgeItem(id: Int) {
        viewModelScope.launch {
            when (val r = repo.purgeItem(id)) {
                is ApiResult.Ok -> {
                    _message.value = "已彻底删除（分配与采购记录一并清掉）"
                    reload()
                }
                is ApiResult.Err -> _message.value = r.message
            }
        }
    }

    fun purgeTrash() {
        viewModelScope.launch {
            when (val r = repo.purgeTrash()) {
                is ApiResult.Ok -> {
                    _message.value = "已清空回收站（${r.data.deleted} 条）"
                    reload()
                }
                is ApiResult.Err -> _message.value = r.message
            }
        }
    }

    /* ---------- 额外费用 ---------- */

    fun addExpense(body: ExpenseInDto) {
        viewModelScope.launch {
            when (val r = repo.createExpense(body)) {
                is ApiResult.Ok -> {
                    _message.value = "已记一笔 ${r.data.kind} ${Fmt.money(r.data.amount)}"
                    reload()
                }
                is ApiResult.Err -> _message.value = r.message
            }
        }
    }

    fun deleteExpense(id: Int) {
        viewModelScope.launch {
            when (val r = repo.deleteExpense(id)) {
                is ApiResult.Ok -> {
                    _message.value = "已删除这笔费用"
                    reload()
                }
                is ApiResult.Err -> _message.value = r.message
            }
        }
    }
}

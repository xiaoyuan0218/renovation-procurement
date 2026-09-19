package com.xiaoyuan.renovation.mobile.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.mobile.data.db.SyncBindingEntity
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import com.xiaoyuan.renovation.mobile.data.repo.okData
import com.xiaoyuan.renovation.mobile.data.sync.MergeConflict
import com.xiaoyuan.renovation.mobile.data.sync.ServerSession
import com.xiaoyuan.renovation.mobile.data.sync.SyncEngine
import com.xiaoyuan.renovation.mobile.data.sync.UploadChoice
import com.xiaoyuan.renovation.mobile.data.sync.UploadDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 页面上的一条提示：失败要显眼（红底警示条），成功用薄荷绿。 */
data class SyncNotice(val text: String, val error: Boolean = false)

/** 「设置 → 服务器」那一块的状态。 */
data class SyncUiState(
    val url: String = "",
    val username: String = "",
    val loggedIn: Boolean = false,
    /** 本地清单 id → 绑定信息；没有条目就是纯本地清单 */
    val bindings: Map<Int, SyncBindingEntity> = emptyMap(),
    /** 服务器上有哪些清单（上传/拉取时给用户挑） */
    val remoteLists: List<ItemListDto> = emptyList(),
    val busy: Boolean = false,
    val notice: SyncNotice? = null,
    /**
     * 每成功改动一次清单数据就 +1（上传、拉取、同步、解绑）。
     * 界面监听它通知外层刷新 —— 拉取完不刷新的话，新清单要重启 App 才看得见。
     */
    val changed: Int = 0,
    /** 需要用户拍板的冲突 */
    val conflicts: List<MergeConflict> = emptyList(),
    /**
     * 上传时认出服务器上已有同一份、但两边从没同步过 —— 该覆盖还是合并
     * 得用户定，非空时弹选择框。
     */
    val uploadDecision: UploadDecision? = null,
)

/**
 * 连服务器的界面逻辑。
 *
 * 动作都不擅自决定冲突：先把冲突列出来，用户选完"以手机为准 / 以电脑为准"再同步一次。
 */
class SyncViewModel(
    private val engine: SyncEngine,
    private val repo: LocalRepository,
    private val session: ServerSession,
    dataVersion: Flow<Int>,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** 冲突待裁决的清单（用户在界面上选完就清掉）。 */
    private var pendingListId: Int? = null

    init {
        viewModelScope.launch {
            combine(session.url, session.username, session.token) { url, user, token ->
                Triple(url, user, token.isNotBlank())
            }.collect { (url, user, loggedIn) ->
                _state.value = _state.value.copy(url = url, username = user, loggedIn = loggedIn)
            }
        }
        refreshBindings()
        // 后台自动同步改完数据或绑定点后，「上次同步时间」跟着更新
        viewModelScope.launch {
            dataVersion.drop(1).collect { refreshBindings() }
        }
    }

    fun refreshBindings() {
        viewModelScope.launch {
            // 先把绑定点查出来再改 state：写成 `copy(bindings = engine.allBindings())`
            // 的话，`_state.value` 会在挂起**之前**求值 —— 查询跑完（几十毫秒）拿旧快照
            // 覆盖，把期间刚更新的登录态一起抹掉（设置页显示"未连接"就是这么来的）
            val bindings = engine.allBindings()
            _state.value = _state.value.copy(bindings = bindings)
        }
    }

    /* ---------------- 登录 ---------------- */

    fun login(url: String, username: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = engine.login(url, username, password)) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(
                        notice = SyncNotice("已连接 ${url.trim().trimEnd('/')}"),
                    )
                    loadRemoteLists()
                }

                is ApiResult.Err -> {
                    val hint = result.hint?.let { "（$it）" }.orEmpty()
                    _state.value = _state.value.copy(
                        notice = SyncNotice(result.message + hint, error = true),
                    )
                }
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun logout() {
        viewModelScope.launch {
            engine.logout()
            _state.value = _state.value.copy(notice = SyncNotice("已退出登录"), remoteLists = emptyList())
        }
    }

    /** 拉服务器上的清单列表 —— 上传/拉取前先看服务器上有什么。 */
    fun loadRemoteLists() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = engine.remoteLists()
            _state.value = _state.value.copy(
                busy = false,
                remoteLists = result.okData.orEmpty(),
                notice = (result as? ApiResult.Err)?.let { SyncNotice(it.message, error = true) }
                    ?: _state.value.notice,
            )
        }
    }

    /* ---------------- 上传 / 拉取 ---------------- */

    /**
     * 用户点了"上传到服务器"。
     *
     * 按**编号**判断服务器上是不是已经有同一份（不再按名字）：名字可以重复、
     * 也随时会改，编号才是身份。认出同一份时，engine 会看有没有共同基线 ——
     * 绑过的直接合并，从没同步过的（分不清谁改了什么）把两边情况带回来，
     * 由用户定覆盖还是合并。
     */
    fun uploadRequested(list: ItemListDto) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = engine.upload(list.id, list.name)
            val decision = (result as? ApiResult.Ok)?.data?.needsUploadDecision
            if (decision != null) {
                // 不弹提示，先把选择摆出来 —— 这一步没有"默认答案"
                _state.value = _state.value.copy(busy = false, uploadDecision = decision)
                refreshBindings()
                return@launch
            }
            report(
                result,
                if (result is ApiResult.Ok && result.data.createdListId == null) {
                    "服务器上已有这份清单，已对齐两边"
                } else {
                    "已上传到服务器，以后可以和这份清单双向同步"
                },
            )
            if (result is ApiResult.Ok) {
                markChanged()
                loadRemoteLists() // 服务器上可能多了一份，「服务器上的清单」要跟着更新
            }
            refreshBindings()
        }
    }

    /** 用户在"上传撞上同一份"的弹窗里选了一种处理方式。 */
    fun resolveUpload(listId: Int, choice: UploadChoice) {
        val decision = _state.value.uploadDecision ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, uploadDecision = null)
            val result = engine.resolveUpload(listId, decision.remoteListId, choice)
            report(
                result,
                when (choice) {
                    UploadChoice.OverwriteRemote -> "已用手机上的内容覆盖服务器"
                    UploadChoice.MergeBoth -> "两边已合并，各自独有的都留着"
                    UploadChoice.KeepRemote -> "已改用电脑上的内容"
                },
            )
            if (result is ApiResult.Ok) {
                markChanged()
                loadRemoteLists()
            }
            refreshBindings()
        }
    }

    /** 关掉弹窗、什么都不做（用户还没想好）。 */
    fun dismissUploadDecision() {
        _state.value = _state.value.copy(uploadDecision = null)
    }

    /** 把服务器上的一份清单拉到本地；本地已有同一份（编号相同）则直接对齐。 */
    fun pullAsNew(remoteListId: Int, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = engine.pullAsNewList(remoteListId, name)
            report(result, "已拉到本地，之后可以和它双向同步")
            if (result is ApiResult.Ok) markChanged()
            refreshBindings()
        }
    }

    /* ---------------- 同步 ---------------- */

    fun syncNow(listId: Int, preferLocal: Boolean = false) {
        pendingListId = listId
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, conflicts = emptyList())
            when (val result = engine.sync(listId, preferLocal)) {
                is ApiResult.Ok -> {
                    val outcome = result.data
                    _state.value = _state.value.copy(
                        busy = false,
                        conflicts = outcome.conflicts,
                        // 服务器那份没了：engine 已经自动解绑，提示走应用级通道
                        // （AppShellHost 的 Snackbar，挂在导航之上，任何页面都看得到），
                        // 这里不再单独提示，免得同一件事弹两条
                        notice = when {
                            outcome.hasConflicts -> null
                            outcome.remoteMissing -> null
                            else -> SyncNotice("已同步")
                        },
                    )
                    // 解绑也是状态变化，界面要跟着更新
                    if (!outcome.hasConflicts) {
                        markChanged()
                        refreshBindings()
                    }
                }

                is ApiResult.Err -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        notice = SyncNotice(result.message, error = true),
                    )
                }
            }
        }
    }

    /** 用户在冲突提示里选了"以手机为准"（true）或"以电脑为准"（false）。 */
    fun resolveConflicts(preferLocal: Boolean) {
        val listId = pendingListId ?: return
        _state.value = _state.value.copy(conflicts = emptyList())
        syncNow(listId, preferLocal = preferLocal)
    }

    fun unbind(listId: Int, keepRemote: Boolean = true) {
        viewModelScope.launch {
            engine.unbind(listId)
            markChanged()
            refreshBindings()
            _state.value = _state.value.copy(
                notice = SyncNotice(if (keepRemote) "已解除绑定，服务器上那份还留着" else "已解除绑定"),
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(notice = null)
    }

    /** 数据变过了：通知界面刷新（清单列表、看板、下拉里的条目数都跟着变）。 */
    private fun markChanged() {
        _state.value = _state.value.copy(changed = _state.value.changed + 1)
    }

    private fun report(result: ApiResult<*>, success: String) {
        _state.value = _state.value.copy(
            busy = false,
            notice = when (result) {
                is ApiResult.Ok -> SyncNotice(success)
                is ApiResult.Err -> SyncNotice(result.message, error = true)
            },
        )
    }
}

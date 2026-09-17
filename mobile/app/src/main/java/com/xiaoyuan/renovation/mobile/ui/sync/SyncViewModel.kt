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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** 需要用户拍板的冲突 */
    val conflicts: List<MergeConflict> = emptyList(),
    /** 服务器上那份清单已经不存在了 */
    val remoteMissing: Boolean = false,
    /** 服务器上有同名清单，等用户选"新建一份"还是"覆盖那一份" */
    val uploadChoice: Pair<ItemListDto, ItemListDto>? = null,
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
    }

    fun refreshBindings() {
        viewModelScope.launch {
            _state.value = _state.value.copy(bindings = engine.allBindings())
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
     * 先**现拉一次**服务器清单再判断有没有同名的 —— 用登录时缓存的那份会漏：
     * 用户在电脑上刚改过清单名，这边还以为不重名，就默默新建了一份。
     */
    fun uploadRequested(list: ItemListDto) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val remote = engine.remoteLists().okData.orEmpty()
            val same = remote.firstOrNull { it.name == list.name }
            _state.value = _state.value.copy(
                busy = false,
                remoteLists = remote,
                uploadChoice = if (same == null) null else list to same,
            )
            if (same == null) uploadAsNew(list.id, list.name)
        }
    }

    fun clearUploadChoice() {
        _state.value = _state.value.copy(uploadChoice = null)
    }

    /** 上传成服务器上的一份新清单。 */
    fun uploadAsNew(listId: Int, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = engine.upload(listId, name, remoteListId = null)
            report(result, "已上传到服务器，以后可以和这份清单双向同步")
            refreshBindings()
        }
    }

    /** 覆盖服务器上已有的那一份（调用方要先确认过）。 */
    fun uploadOverwrite(listId: Int, remoteListId: Int, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = engine.upload(listId, name, remoteListId = remoteListId, force = true)
            report(result, "已覆盖服务器上那一份")
            refreshBindings()
        }
    }

    /** 把服务器上的一份清单拉到本地，成为一份新的本地清单。 */
    fun pullAsNew(remoteListId: Int, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = engine.pullAsNewList(remoteListId, name)
            report(result, "已拉到本地，之后可以和它双向同步")
            refreshBindings()
        }
    }

    /* ---------------- 同步 ---------------- */

    fun syncNow(listId: Int, preferLocal: Boolean = false) {
        pendingListId = listId
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, conflicts = emptyList(), remoteMissing = false)
            when (val result = engine.sync(listId, preferLocal)) {
                is ApiResult.Ok -> {
                    val outcome = result.data
                    _state.value = _state.value.copy(
                        busy = false,
                        conflicts = outcome.conflicts,
                        remoteMissing = outcome.remoteMissing,
                        notice = when {
                            outcome.hasConflicts -> null
                            outcome.remoteMissing -> null
                            else -> SyncNotice("已同步")
                        },
                    )
                    if (!outcome.hasConflicts && !outcome.remoteMissing) refreshBindings()
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

    /** 服务器上那份清单没了：重新上传成一份新的，或者干脆解除绑定。 */
    fun resolveRemoteMissing(reuploadAsNew: Boolean, listId: Int, name: String) {
        _state.value = _state.value.copy(remoteMissing = false)
        viewModelScope.launch {
            if (reuploadAsNew) {
                engine.unbind(listId)
                uploadAsNew(listId, name)
            } else {
                engine.unbind(listId)
                refreshBindings()
                _state.value = _state.value.copy(notice = SyncNotice("已解除绑定，这份清单继续在本地用"))
            }
        }
    }

    fun unbind(listId: Int, keepRemote: Boolean = true) {
        viewModelScope.launch {
            engine.unbind(listId)
            refreshBindings()
            _state.value = _state.value.copy(
                notice = SyncNotice(if (keepRemote) "已解除绑定，服务器上那份还留着" else "已解除绑定"),
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(notice = null)
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

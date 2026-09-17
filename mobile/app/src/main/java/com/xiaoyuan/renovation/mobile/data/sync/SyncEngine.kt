package com.xiaoyuan.renovation.mobile.data.sync

import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.SyncBindingEntity
import com.xiaoyuan.renovation.mobile.data.db.nowStamp
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import com.xiaoyuan.renovation.mobile.data.model.LoginResultDto
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.CurrentListHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 服务器地址与登录凭证的内存副本。
 *
 * 每次发请求都要读地址和 token，而 DataStore 是挂起的；这里缓存一份，
 * 请求路径上就能同步取用（写的时候两边一起改）。
 */
class ServerSession(private val prefs: AppPrefs, scope: CoroutineScope) {

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    val isLoggedIn: Boolean get() = _token.value.isNotBlank()

    init {
        scope.launch { prefs.serverUrl.collect { _url.value = it } }
        scope.launch { prefs.username.collect { _username.value = it } }
        scope.launch { prefs.token.collect { _token.value = it } }
    }

    suspend fun save(url: String, username: String, token: String) {
        prefs.saveServer(url, username, token)
        _url.value = url
        _username.value = username
        _token.value = token
    }

    suspend fun clearCredentials() {
        prefs.clearCredentials()
        _username.value = ""
        _token.value = ""
    }
}

/** 一次同步/搬运的结果。有冲突时不算失败 —— 那是要用户拍板的事。 */
data class SyncOutcome(
    val conflicts: List<MergeConflict> = emptyList(),
    /** 服务器上那份清单已经不存在了（在电脑上被删掉） */
    val remoteMissing: Boolean = false,
    val createdListId: Int? = null,
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

/**
 * 把「导出 / 覆盖 / 新建」三个接口编排成用户看得懂的动作：
 * 上传、拉取、以及已绑定清单的双向对齐。
 *
 * 冲突一律不擅自决定：交给界面列出来让用户选，选完再调一次（[preferLocal] 决定听谁的）。
 */
class SyncEngine(
    private val db: AppDatabase,
    private val prefs: AppPrefs,
    private val currentList: CurrentListHolder,
    private val session: ServerSession,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun api() = SyncApi({ session.url.value }, { session.token.value })

    /* ---------------- 登录 ---------------- */

    /** 用用户刚填的地址登录，成功后把地址、账号、token 一起存下来。 */
    suspend fun login(url: String, username: String, password: String): ApiResult<LoginResultDto> {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isBlank()) return ApiResult.Err("先填服务器地址")
        val result = SyncApi({ cleaned }, { "" }).login(username.trim(), password)
        if (result is ApiResult.Ok) {
            session.save(cleaned, result.data.username, result.data.token)
        }
        return result
    }

    suspend fun logout() = session.clearCredentials()

    /* ---------------- 绑定 ---------------- */

    suspend fun binding(listId: Int): SyncBindingEntity? = db.sync().byList(listId)

    /** 本地清单 id → 绑定信息。界面上用它标出哪几份清单是连着服务器的。 */
    suspend fun allBindings(): Map<Int, SyncBindingEntity> =
        db.sync().all().associateBy { it.listId }

    /** 服务器上有哪些清单（上传/拉取前让用户挑目标）。 */
    suspend fun remoteLists(): ApiResult<List<ItemListDto>> = api().lists()

    /** 解除绑定：只断开关联，服务器上那份清单原样留着。 */
    suspend fun unbind(listId: Int) {
        db.sync().delete(listId)
    }

    suspend fun setAutoSync(listId: Int, enabled: Boolean) {
        db.sync().setAutoSync(listId, enabled)
    }

    /* ---------------- 上传：把本地清单搬到服务器 ---------------- */

    /**
     * 上传本地清单。[remoteListId] 为 null 时在服务器上新建一份；
     * 给了就整份覆盖那一份（服务器会先自动留一份整库备份）。
     */
    suspend fun upload(
        listId: Int,
        name: String,
        remoteListId: Int? = null,
        force: Boolean = false,
    ): ApiResult<SyncOutcome> {
        val payload = Snapshot.capture(db, listId).copy(list = SyncListMeta(name = name))
        val api = api()

        return when (remoteListId) {
            null -> when (val created = api.createList(payload)) {
                is ApiResult.Ok -> {
                    adopt(
                        db, prefs, json, listId,
                        remoteListId = created.data.listId,
                        remoteName = name,
                        fingerprint = created.data.fingerprint,
                        payload = created.data.payload,
                    )
                    ApiResult.Ok(SyncOutcome(createdListId = created.data.listId))
                }

                is ApiResult.Err -> created
            }

            else -> when (
                val pushed = api.pushList(
                    remoteListId,
                    SyncPush.of(payload, baseFingerprint = null, force = force),
                )
            ) {
                is ApiResult.Ok -> {
                    adopt(
                        db, prefs, json, listId,
                        remoteListId = remoteListId,
                        remoteName = name,
                        fingerprint = pushed.data.fingerprint,
                        payload = pushed.data.payload,
                    )
                    ApiResult.Ok(SyncOutcome())
                }

                is ApiResult.Err -> pushed
            }
        }
    }

    /* ---------------- 拉取：把服务器清单带到本地 ---------------- */

    /** 把服务器上的一份清单拉到本地，成为一份新的本地清单。 */
    suspend fun pullAsNewList(remoteListId: Int, name: String): ApiResult<SyncOutcome> =
        when (val exported = api().exportList(remoteListId)) {
            is ApiResult.Ok -> {
                val snapshot = exported.data
                val listId = Snapshot.createList(db, snapshot.payload, name)
                // 拉到本地后就是绑定关系：以后可以继续双向同步
                saveBinding(
                    listId = listId,
                    remoteListId = remoteListId,
                    remoteName = name,
                    fingerprint = snapshot.fingerprint,
                    payload = snapshot.payload,
                    localMap = emptyMap(),
                    previous = db.sync().byList(listId),
                )
                ApiResult.Ok(SyncOutcome(createdListId = listId))
            }

            is ApiResult.Err -> exported
        }

    /* ---------------- 已绑定清单的双向同步 ---------------- */

    /**
     * 和服务器对齐。
     *
     * [preferLocal] 只在"两边都改过同一行"时起作用：false 听服务器的、true 听手机的。
     * 有冲突且用户还没表态时不会推送，先把冲突列出来让界面问。
     */
    suspend fun sync(listId: Int, preferLocal: Boolean = false): ApiResult<SyncOutcome> {
        val binding = db.sync().byList(listId)
            ?: return ApiResult.Err("这份清单还没绑定服务器")

        return when (val remote = api().exportList(binding.remoteListId)) {
            is ApiResult.Ok -> syncWith(binding, remote.data, preferLocal)

            // 服务器上那份被删了（多半是在电脑上删的）：不擅自处理，交给界面问用户
            is ApiResult.Err ->
                if (remote.code == 404) {
                    ApiResult.Ok(SyncOutcome(remoteMissing = true))
                } else {
                    remote
                }
        }
    }

    private suspend fun syncWith(
        binding: SyncBindingEntity,
        snapshot: SyncSnapshot,
        preferLocal: Boolean,
    ): ApiResult<SyncOutcome> {
        val listId = binding.listId
        val mine = Snapshot.capture(db, listId)
        val base = readBaseline(binding)

        // 服务器没动过：把本地直接推上去（本地也没变时这次推送无害，只是重写成一样的内容）
        val remoteChanged = snapshot.fingerprint != binding.fingerprint
        val payload: SyncPayload
        val conflicts: List<MergeConflict>
        if (remoteChanged) {
            val merged = Merger.merge(base, mine, snapshot.payload, preferLocal)
            if (merged.hasConflicts && !preferLocal) {
                return ApiResult.Ok(SyncOutcome(conflicts = merged.conflicts))
            }
            payload = merged.payload
            conflicts = merged.conflicts
        } else {
            payload = mine
            conflicts = emptyList()
        }

        val request = SyncPush.of(
            payload,
            baseFingerprint = snapshot.fingerprint,
            force = remoteChanged && preferLocal,
        )
        return when (val pushed = api().pushList(binding.remoteListId, request)) {
            is ApiResult.Ok -> {
                adopt(
                    db, prefs, json, listId,
                    remoteListId = binding.remoteListId,
                    remoteName = binding.remoteName,
                    fingerprint = pushed.data.fingerprint,
                    payload = pushed.data.payload,
                )
                ApiResult.Ok(SyncOutcome(conflicts = conflicts))
            }

            is ApiResult.Err -> pushed
        }
    }

    /* ---------------- 内部 ---------------- */

    /** 把推送回来的结果落到本地，并记下新的基线（含 id 映射）。 */
    private suspend fun adopt(
        db: AppDatabase,
        prefs: AppPrefs,
        json: Json,
        listId: Int,
        remoteListId: Int,
        remoteName: String,
        fingerprint: String,
        payload: SyncPayload,
    ) {
        val localMap = Snapshot.apply(db, listId, payload)
        saveBinding(listId, remoteListId, remoteName, fingerprint, payload, localMap, db.sync().byList(listId))
    }

    private suspend fun saveBinding(
        listId: Int,
        remoteListId: Int,
        remoteName: String,
        fingerprint: String,
        payload: SyncPayload,
        localMap: Map<String, Int>,
        previous: SyncBindingEntity?,
    ) {
        val baseline = SyncBaseline(
            fingerprint = fingerprint,
            payload = payload,
            localMap = localMap,
        )
        db.sync().upsert(
            SyncBindingEntity(
                listId = listId,
                serverUrl = session.url.value,
                remoteListId = remoteListId,
                remoteName = remoteName,
                fingerprint = fingerprint,
                baseline = json.encodeToString(SyncBaseline.serializer(), baseline),
                lastSyncedAt = nowStamp(),
                autoSync = previous?.autoSync ?: true,
            ),
        )
    }

    private fun readBaseline(binding: SyncBindingEntity): SyncBaseline =
        if (binding.baseline.isBlank()) {
            SyncBaseline()
        } else {
            runCatching {
                json.decodeFromString(SyncBaseline.serializer(), binding.baseline)
            }.getOrElse { SyncBaseline() }
        }
}

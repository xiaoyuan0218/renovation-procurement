package com.xiaoyuan.renovation.mobile.data.sync

import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.SyncBindingEntity
import com.xiaoyuan.renovation.mobile.data.db.nowStamp
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import com.xiaoyuan.renovation.mobile.data.model.LoginResultDto
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import com.xiaoyuan.renovation.mobile.data.repo.ApiResult
import com.xiaoyuan.renovation.mobile.data.repo.CurrentListHolder
import com.xiaoyuan.renovation.mobile.domain.AddressParse
import com.xiaoyuan.renovation.mobile.domain.ServerAddress
import com.xiaoyuan.renovation.mobile.util.ListCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

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
        scope.launch {
            // 读不出来就当未登录，但别让收集器静默死掉 —— 不然登录态永远恢复不了
            runCatching { prefs.token.collect { _token.value = it } }
                .onFailure { android.util.Log.w("ServerSession", "token 读取失败", it) }
        }
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
    /**
     * 上传时认出服务器上已有同一份（按编号），但两边**从没同步过** —— 该覆盖
     * 还是该合并由用户定，这里把两边的样子带回去给界面问。
     */
    val needsUploadDecision: UploadDecision? = null,
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

/** 上传前要问用户的事：服务器上那份和手机上这份，谁说了算。 */
data class UploadDecision(
    val remoteListId: Int,
    /** 手机上这份有多少内容 */
    val local: SideSummary,
    /** 服务器上那份有多少内容 */
    val remote: SideSummary,
)

/**
 * 一边的规模与最后改动时间，用来让用户判断"哪边数据更全、哪边更新"。
 *
 * [lastChangedAt] 取的是**内容里最新的那个修改时间**，不是清单自身的
 * `updated_at` —— 后者只在改清单名/备注时才刷新，改物料根本不动它，
 * 拿它当"最后改动"会一直显示很久以前，反而误导。
 */
data class SideSummary(
    val items: Int,
    val rooms: Int,
    val categories: Int,
    val lastChangedAt: String,
) {
    val isEmpty: Boolean get() = items == 0 && rooms == 0 && categories == 0
}

/** 用户在"上传撞上同一份"时选的处理方式。 */
enum class UploadChoice {
    /** 手机这份整份覆盖服务器那份（电脑上的改动丢掉） */
    OverwriteRemote,

    /** 两边都留着：按名字配对，同名取较新的，各自独有的都保留 */
    MergeBoth,

    /** 听服务器的：本地这份换成服务器上的内容（手机上的改动丢掉） */
    KeepRemote,
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

    /**
     * 需要让用户知道的同步事件（应用级，任意页面都能弹提示）。
     *
     * 主要是"服务器上那份清单被删了、已自动解绑"——这事发生在后台自动同步里，
     * 界面上不一定开着服务器页，所以走一条独立的通道播出去。
     */
    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events.asStateFlow()

    /** 提示已展示，清掉（避免重组时重复弹）。 */
    fun consumeEvent() {
        _events.value = null
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun api() = SyncApi({ session.url.value }, { session.token.value })

    /* ---------------- 登录 ---------------- */

    /** 用用户刚填的地址登录，成功后把地址、账号、token 一起存下来。 */
    suspend fun login(url: String, username: String, password: String): ApiResult<LoginResultDto> {
        // 用户随手填的地址要先收拾成合法 baseUrl：`192.168.31.200:8000` 这种省略
        // 协议、甚至省略端口的写法必须支持 —— 原样塞给 OkHttp 会抛
        // "Expected URL scheme ..."，用户只看到一串英文，就成了"连不上"
        val cleaned = when (val parsed = ServerAddress.parse(url)) {
            is AddressParse.Ok -> parsed.normalized
            AddressParse.Empty -> return ApiResult.Err("先填服务器地址")
            is AddressParse.Invalid -> return ApiResult.Err(parsed.reason)
        }
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
     * 上传本地清单，按**编号**决定这是新建还是撞上了已有那份。
     *
     * 编号是清单的身份（名字会改、也允许重名）：服务器上已有同编号的清单，
     * 说明本来就是同一份 —— 但**怎么对齐要看情况**：
     * - 之前绑过、有共同基线：直接走三方合并，两边改动都保住（这是常规路径）
     * - 从没同步过、没有基线：分不清谁改了什么，三方合并无从谈起。把两边的
     *   样子带回去让用户定（覆盖 / 合并 / 听服务器的）—— 硬合会在服务器上
     *   留下两份同名分组、同名分类，整份传不上去。
     *
     * [remoteListId] 是调用方已经认出来的服务器 id（按编号匹配的结果），
     * 给了就直接对齐它，省一次查询。
     */
    suspend fun upload(
        listId: Int,
        name: String,
        remoteListId: Int? = null,
        force: Boolean = false,
    ): ApiResult<SyncOutcome> {
        val local = db.lists().byId(listId) ?: return ApiResult.Err("清单不存在")
        // 带上本地编号：服务器靠它认出"这是哪一份"，也给新建的那份定身份
        val payload = Snapshot.capture(db, listId).copy(
            list = SyncListMeta(
                name = name,
                note = local.note,
                sort = local.sort,
                code = local.code,
                createdAt = local.createdAt,
                updatedAt = local.updatedAt,
            ),
        )
        val api = api()

        // 先按编号在服务器上找同一份（调用方没给才查）
        val target = remoteListId ?: findRemoteByCode(api, local.code)

        return when (target) {
            null -> when (val created = api.createList(payload)) {
                is ApiResult.Ok -> {
                    adopt(
                        db, prefs, json, listId,
                        remoteListId = created.data.listId,
                        remoteName = created.data.payload.list.name,
                        fingerprint = created.data.fingerprint,
                        payload = created.data.payload,
                    )
                    ApiResult.Ok(SyncOutcome(createdListId = created.data.listId))
                }

                is ApiResult.Err -> created
            }

            else -> {
                val existing = db.sync().byList(listId)
                if (existing != null && existing.baseline.isNotBlank()) {
                    // 绑过、有基线：常规三方合并
                    sync(listId, preferLocal = force)
                } else {
                    // 从没同步过：问用户怎么对齐
                    when (val remote = api.exportList(target)) {
                        is ApiResult.Ok -> ApiResult.Ok(
                            SyncOutcome(
                                needsUploadDecision = UploadDecision(
                                    remoteListId = target,
                                    local = summarize(payload),
                                    remote = summarize(remote.data.payload),
                                ),
                            ),
                        )

                        is ApiResult.Err -> remote
                    }
                }
            }
        }
    }

    /**
     * 用户在"上传撞上同一份"里选完之后真正执行。
     *
     * 三种选法对应三条路径：整份覆盖上去、按名字合并两边、或者反过来把服务器
     * 那份拿下来。前两种都会在服务器上写，第三种只动本地。
     */
    suspend fun resolveUpload(
        listId: Int,
        remoteListId: Int,
        choice: UploadChoice,
    ): ApiResult<SyncOutcome> {
        val local = db.lists().byId(listId) ?: return ApiResult.Err("清单不存在")
        val mine = Snapshot.capture(db, listId).copy(
            list = SyncListMeta(
                name = local.name,
                note = local.note,
                sort = local.sort,
                code = local.code,
                createdAt = local.createdAt,
                updatedAt = local.updatedAt,
            ),
        )
        val exported = api().exportList(remoteListId)
        if (exported is ApiResult.Err) return exported
        val snapshot = (exported as ApiResult.Ok).data

        if (choice == UploadChoice.KeepRemote) {
            // 听服务器的：本地换成它那份。这条不推送，服务器上原样不动
            adopt(
                db, prefs, json, listId,
                remoteListId = remoteListId,
                remoteName = snapshot.payload.list.name,
                fingerprint = snapshot.fingerprint,
                payload = snapshot.payload,
            )
            return ApiResult.Ok(SyncOutcome())
        }

        val toPush = when (choice) {
            UploadChoice.OverwriteRemote -> mine
            else -> Merger.mergeWithoutBase(mine, snapshot.payload)
        }
        // 覆盖是用户明确要的"以我为准"，带 force 跳过指纹校验；合并则是基于刚
        // 取到的服务器内容算出来的，带上它的指纹 —— 万一这中间服务器又变了，
        // 宁可报错让用户重来，也别把没算进去的改动抹掉
        val request = SyncPush.of(
            toPush,
            baseFingerprint = if (choice == UploadChoice.MergeBoth) snapshot.fingerprint else null,
            force = choice == UploadChoice.OverwriteRemote,
        )
        return when (val pushed = api().pushList(remoteListId, request)) {
            is ApiResult.Ok -> {
                adopt(
                    db, prefs, json, listId,
                    remoteListId = remoteListId,
                    remoteName = pushed.data.payload.list.name,
                    fingerprint = pushed.data.fingerprint,
                    payload = pushed.data.payload,
                )
                ApiResult.Ok(SyncOutcome())
            }

            is ApiResult.Err -> pushed
        }
    }

    /** 数一数一边有多少内容、最后是什么时候动的，让用户判断哪边更全更新。 */
    private fun summarize(payload: SyncPayload) = SideSummary(
        items = payload.items.size,
        rooms = payload.rooms.size,
        categories = payload.categories.size,
        lastChangedAt = (
            payload.rooms.map { it.updatedAt } +
                payload.categories.map { it.updatedAt } +
                payload.items.map { it.updatedAt } +
                payload.expenses.map { it.updatedAt } +
                listOf(payload.list.updatedAt)
            ).filter { it.isNotBlank() }.maxOrNull().orEmpty(),
    )

    /** 按编号在服务器清单里找同一份；本地没编号（老数据）就返回 null。 */
    private suspend fun findRemoteByCode(api: SyncApi, code: String): Int? {
        val wanted = ListCodes.normalize(code)
        if (wanted.isEmpty()) return null
        return when (val remote = api.lists()) {
            is ApiResult.Ok -> remote.data
                .firstOrNull { ListCodes.normalize(it.code) == wanted }?.id
            is ApiResult.Err -> null
        }
    }

    /* ---------------- 拉取：把服务器清单带到本地 ---------------- */

    /**
     * 把服务器上的一份清单拉到本地。
     *
     * 按**编号**先看本地有没有同一份：有就直接绑定它并同步（不重复创建一份
     * 同编号的清单 —— 那会让"哪份是哪份"彻底乱掉）；没有才新建。
     */
    suspend fun pullAsNewList(remoteListId: Int, name: String): ApiResult<SyncOutcome> {
        val exported = api().exportList(remoteListId)
        if (exported is ApiResult.Err) return exported
        val snapshot = (exported as ApiResult.Ok).data
        val code = ListCodes.normalize(snapshot.payload.list.code)
        val existing = if (code.isEmpty()) null else db.lists().byCode(code)

        if (existing != null) {
            // 本地已经有这一份了：直接绑定，然后把两边对齐（不重复建一份同编号的）
            saveBinding(
                listId = existing.id,
                remoteListId = remoteListId,
                remoteName = name,
                fingerprint = snapshot.fingerprint,
                payload = snapshot.payload,
                localMap = emptyMap(),
                previous = db.sync().byList(existing.id),
            )
            val synced = sync(existing.id)
            return if (synced is ApiResult.Ok) {
                ApiResult.Ok(synced.data.copy(createdListId = existing.id))
            } else {
                synced
            }
        }

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
        return ApiResult.Ok(SyncOutcome(createdListId = listId))
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

            // 服务器上那份被删了（多半是在电脑上删的）：直接解除绑定，本地数据原样留着，
            // 变回一份纯本地清单。要不要再传上去由用户自己决定 —— 弹窗问"重新传还是留本地"
            // 其实没有第三种可能，问一次不如直接把结果做掉、用提示条告知。
            is ApiResult.Err ->
                if (remote.code == 404) {
                    val name = db.lists().byId(listId)?.name.orEmpty()
                    db.sync().delete(listId)
                    _events.value = "服务器上的「$name」已被删除，已解除绑定；" +
                        "这份清单继续留在这台手机上"
                    ApiResult.Ok(SyncOutcome(remoteMissing = true))
                } else {
                    remote
                }
        }
    }

    /**
     * 本地一有改动就调它（后台自动同步的入口）。
     *
     * 已登录、当前清单已绑定、且开着自动对齐时才安静对齐一次；离线、失败、
     * 有冲突 —— 一律返回 false 不动声色：自动同步不该打扰用户。
     * 唯一例外是"服务器上那份被删了"：sync 里已经自动解绑，这里返回 true
     * 让界面刷新（绑定状态变了，得让它看见）。
     *
     * 返回值表示**本地内容或绑定关系是否真的变了**：变了调用方要刷新界面；
     * 没变就别刷 —— 刷界面会再触发一轮同步，白跑。
     */
    suspend fun autoSyncCurrent(): Boolean {
        if (session.token.value.isBlank()) return false
        val listId = currentList.flow.value ?: return false
        val binding = db.sync().byList(listId) ?: return false
        if (!binding.autoSync) return false

        // 比对要**忽略 id 与时间戳**：同步落地会重建本地行（id 全变）、也会刷新
        // 时间戳，拿完整 JSON 比就会把"其实没变"当成变了，于是刷新界面 → 再触发
        // 一轮同步 → 又"变了"，自动同步会自己把自己转起来。
        val before = canonical(Snapshot.capture(db, listId))
        val result = sync(listId)
        if (result !is ApiResult.Ok) return false
        if (result.data.hasConflicts) return false
        // 服务器那份没了：解绑已经做掉，界面要跟着更新（否则还显示"双向同步"）
        if (result.data.remoteMissing) return true
        val after = canonical(Snapshot.capture(db, listId))
        return before != after
    }

    private suspend fun syncWith(
        binding: SyncBindingEntity,
        snapshot: SyncSnapshot,
        preferLocal: Boolean,
    ): ApiResult<SyncOutcome> {
        val listId = binding.listId
        val mine = Snapshot.capture(db, listId)
        val base = readBaseline(binding)

        // 服务器没动过：把本地推上去。
        //
        // 但本地也跟基线一模一样时**什么都别做** —— 推上去会让服务器把整份内容
        // 重建一遍（id 全变），落回本地又是全新的 id，于是"内容变了"再次成立，
        // 自动同步就被自己一轮轮触发下去（实测每几秒一次 PUT，本地行 id 反复
        // 重排）。比对用指纹口径：忽略时间戳与行序，只认内容。
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
            if (sameContent(mine, base.payload)) {
                // 两边都没动：这次同步没有任何事要做，别去打扰服务器
                return ApiResult.Ok(SyncOutcome())
            }
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
                // preferLocal 的这轮推送是用户在冲突框里拍板后的执行 —— merge 返回
                // 的冲突列表只是"判不出时选了哪边"的记录，已经按用户的意愿解决了。
                // 照样塞回去的话，同一个冲突会立刻再弹一遍，用户得对着它选两次。
                ApiResult.Ok(
                    SyncOutcome(conflicts = if (preferLocal) emptyList() else conflicts),
                )
            }

            is ApiResult.Err -> pushed
        }
    }

    /* ---------------- 内部 ---------------- */

    /** 两份内容是不是"同一份"（只看语义，忽略 id、时间戳与行序）。 */
    private fun sameContent(a: SyncPayload, b: SyncPayload): Boolean =
        canonical(a) == canonical(b)

    /**
     * 把一份内容归一成可比较的字符串（只看语义，忽略 id、时间戳与行序）。
     *
     * 必须忽略 id：本地是自增主键、服务器是另一套自增，同一份内容的 id 天然
     * 不同；而覆盖之后本地行还会整体重排。带上它们比，就会把"没变"误判成
     * "变了" —— 那正是自动同步自我触发的成因（推一次 → id 全变 → 认为内容
     * 变了 → 再推一次，如此循环）。
     *
     * 引用（物料的分类、分配的分组、费用的物料）先翻译成**名字**再比：光忽略
     * id 会把"同一个物料改挂到另一个分组"这种改动漏掉。
     */
    private fun canonical(payload: SyncPayload): String {
        val roomName = payload.rooms.associate { (it.id ?: 0) to it.name }
        val categoryName = payload.categories.associate { (it.id ?: 0) to it.name }
        val itemName = payload.items.associate { (it.id ?: 0) to it.name }

        val rooms = payload.rooms.map { "${it.name}\u0000${it.sort}" }.sorted()
        val categories = payload.categories.map { "${it.name}\u0000${it.sort}" }.sorted()

        val items = payload.items.map { item ->
            val allocations = item.allocations
                .map { "${roomName[it.roomId ?: 0].orEmpty()}\u0000${it.qty}\u0000${it.priceOverride}\u0000${it.note}" }
                .sorted()
            val records = item.records.map { rec ->
                val where = rec.roomIds.map { roomName[it].orEmpty() }.sorted()
                "${rec.qty}\u0000${rec.amount}\u0000${rec.date}\u0000${rec.note}\u0000" +
                    "${rec.vendor}\u0000${rec.orderNo}\u0000${where}"
            }.sorted()
            listOf(
                item.name, categoryName[item.categoryId ?: 0].orEmpty(), item.unit,
                item.brand, item.model, item.qtyTotal, item.price, item.discountPrice,
                item.note, item.sort, item.deletedAt,
                allocations, records,
            ).joinToString("\u0001")
        }.sorted()

        val expenses = payload.expenses.map { exp ->
            listOf(
                exp.kind, exp.amount, exp.date, exp.vendor, exp.orderNo, exp.note,
                itemName[exp.itemId ?: 0].orEmpty(),
            ).joinToString("\u0001")
        }.sorted()

        return listOf(
            payload.list.name, payload.list.note, payload.list.sort, payload.list.code,
            rooms, categories, items, expenses,
        ).joinToString("\n")
    }

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

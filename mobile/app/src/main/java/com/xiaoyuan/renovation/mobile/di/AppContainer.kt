package com.xiaoyuan.renovation.mobile.di

import android.app.Application
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ensureListCodes
import com.xiaoyuan.renovation.mobile.data.db.seedIfEmpty
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import com.xiaoyuan.renovation.mobile.data.repo.CurrentListHolder
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import com.xiaoyuan.renovation.mobile.data.sync.ServerSession
import com.xiaoyuan.renovation.mobile.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 本地依赖容器。单机版没有网络层要注入，这里把本地库、配置、当前清单
 * 和仓储收在一处，界面按需取用。
 */
class AppContainer(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase by lazy { AppDatabase.get(app) }
    val prefs: AppPrefs by lazy { AppPrefs(app) }
    val currentList: CurrentListHolder by lazy { CurrentListHolder(prefs, db, scope) }

    /**
     * 数据版本号：任何写操作成功后 +1。
     *
     * 页面用它做「别处改了数据我也要重读」的信号 —— 在物料页记了一笔，切到
     * 总览就是新数字，不需要页面之间互相通知（与网络版同一套机制）。
     */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    fun bumpDataVersion() {
        _dataVersion.value += 1
    }

    val repo: LocalRepository by lazy {
        LocalRepository(db, currentList, onDataChanged = ::bumpDataVersion)
    }

    /** 服务器地址与登录态（内存缓存，请求路径上同步可读）。 */
    val session: ServerSession by lazy { ServerSession(prefs, scope) }

    /** 连服务器：上传、拉取、双向同步。平时用不到，纯本地也能一直用下去。 */
    val sync: SyncEngine by lazy { SyncEngine(db, prefs, currentList, session) }

    init {
        // 首次启动写入默认清单（分组/分类已在 Seed 里备好）
        scope.launch {
            seedIfEmpty(db)
            ensureListCodes(db)
        }
    }
}

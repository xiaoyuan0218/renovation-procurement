package com.xiaoyuan.renovation.di

import android.content.Context
import com.xiaoyuan.renovation.data.prefs.SettingsStore
import com.xiaoyuan.renovation.data.remote.ApiClientFactory
import com.xiaoyuan.renovation.data.remote.ApiService
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 手写依赖容器：这个规模的项目不值得引入 Hilt。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsStore(appContext, appScope)

    val appContextRef: Context get() = appContext

    /** 数据版本号：任何写操作后 +1，界面靠它知道"该重新拉数据了"。 */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    /**
     * 清单切换这类"不是写操作、但各页面看到的完全是另一批数据"的场合，
     * 走这里让界面重新拉一遍。
     */
    fun bumpDataVersion() {
        _dataVersion.value += 1
    }

    private val api: ApiService by lazy {
        ApiClientFactory.create({ settings.baseUrl.value }, { settings.token.value },
            { settings.currentListId.value })
    }

    private val transferApi: ApiService by lazy {
        ApiClientFactory.createForTransfer({ settings.baseUrl.value }, { settings.token.value },
            { settings.currentListId.value })
    }

    val repo: RenovationRepository by lazy {
        RenovationRepository(
            api = api,
            transferApi = transferApi,
            onDataChanged = { _dataVersion.value += 1 },
            // 任何请求撞上 401（token 过期、或在别处改了密码）就清掉本地会话，
            // 顶层路由监听到 sessionState 变化会自动切回登录页
            onUnauthorized = { appScope.launch { settings.clearSession() } },
        )
    }
}

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

/** 手写依赖容器：这个规模的项目不值得引入 Hilt。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsStore(appContext, appScope)

    val appContextRef: Context get() = appContext

    /** 数据版本号：任何写操作后 +1，界面靠它知道"该重新拉数据了"。 */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()

    private val api: ApiService by lazy {
        ApiClientFactory.create { settings.baseUrl.value }
    }

    private val transferApi: ApiService by lazy {
        ApiClientFactory.createForTransfer { settings.baseUrl.value }
    }

    val repo: RenovationRepository by lazy {
        RenovationRepository(api, transferApi) { _dataVersion.value += 1 }
    }
}

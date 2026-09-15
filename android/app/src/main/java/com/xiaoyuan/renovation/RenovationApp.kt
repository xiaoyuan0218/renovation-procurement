package com.xiaoyuan.renovation

import android.app.Application
import com.xiaoyuan.renovation.di.AppContainer
import kotlinx.coroutines.launch

class RenovationApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 提前把已保存的服务器地址读进内存，拦截器才能在首个请求就用上
        container.appScope.launch { container.settings.warmUp() }
    }
}

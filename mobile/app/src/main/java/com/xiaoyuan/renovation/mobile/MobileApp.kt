package com.xiaoyuan.renovation.mobile

import android.app.Application
import com.xiaoyuan.renovation.mobile.di.AppContainer

class MobileApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

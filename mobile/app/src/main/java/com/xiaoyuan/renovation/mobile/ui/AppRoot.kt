package com.xiaoyuan.renovation.mobile.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xiaoyuan.renovation.mobile.di.AppContainer
import com.xiaoyuan.renovation.mobile.ui.design.GlowBackground
import com.xiaoyuan.renovation.mobile.ui.nav.AppShellHost

/**
 * 单机版的顶层：没有服务器地址要配、没有账号要登，直接进主壳。
 * 连服务器是后面的可选动作，配置入口放在设置页里，不影响日常使用。
 */
@Composable
fun AppRoot(container: AppContainer) {
    GlowBackground(Modifier.fillMaxSize()) {
        AppShellHost(container = container)
    }
}

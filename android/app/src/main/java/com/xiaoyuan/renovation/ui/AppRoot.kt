package com.xiaoyuan.renovation.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.data.prefs.ServerState
import com.xiaoyuan.renovation.data.prefs.SessionState
import com.xiaoyuan.renovation.di.AppContainer
import com.xiaoyuan.renovation.ui.design.GlowBackground
import com.xiaoyuan.renovation.ui.login.LoginScreen
import com.xiaoyuan.renovation.ui.nav.AppShellHost
import com.xiaoyuan.renovation.ui.setup.ServerSetupScreen
import com.xiaoyuan.renovation.ui.theme.Ink

/**
 * 顶层路由：读磁盘 → 没配地址进引导页 → 配了地址但没登录进登录页 → 都齐了进主壳。
 * 地址和登录态是仅有的两个决定因素，所以在设置里改地址、或会话失效被清掉，
 * 都会自然地让界面切到对应的那一层。
 */
@Composable
fun AppRoot(container: AppContainer) {
    val serverState by container.settings.serverState.collectAsStateWithLifecycle()

    GlowBackground(Modifier.fillMaxSize()) {
        when (val state = serverState) {
            ServerState.Loading -> CircularProgressIndicator(
                color = Ink.Blue,
                modifier = Modifier.align(Alignment.Center),
            )

            ServerState.NotConfigured -> ServerSetupScreen(
                container = container,
                currentAddress = null,
                onSaved = { },
            )

            is ServerState.Configured -> SessionGate(container, state.url)
        }
    }
}

/** 地址已配好之后的第二层：还需要一个有效的登录态。 */
@Composable
private fun BoxScope.SessionGate(container: AppContainer, baseUrl: String) {
    val sessionState by container.settings.sessionState.collectAsStateWithLifecycle()

    when (sessionState) {
        SessionState.Loading -> CircularProgressIndicator(
            color = Ink.Blue,
            modifier = Modifier.align(Alignment.Center),
        )

        SessionState.LoggedOut -> LoginScreen(container)

        is SessionState.LoggedIn -> AppShellHost(container = container, baseUrl = baseUrl)
    }
}

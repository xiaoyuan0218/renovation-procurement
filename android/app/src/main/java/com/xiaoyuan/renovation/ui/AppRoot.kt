package com.xiaoyuan.renovation.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.data.prefs.ServerState
import com.xiaoyuan.renovation.di.AppContainer
import com.xiaoyuan.renovation.ui.design.GlowBackground
import com.xiaoyuan.renovation.ui.nav.AppShellHost
import com.xiaoyuan.renovation.ui.setup.ServerSetupScreen
import com.xiaoyuan.renovation.ui.theme.Ink

/**
 * 顶层路由：读磁盘 → 没配地址进引导页 → 配好了进主壳。
 * 地址是唯一决定因素，所以在设置里改地址会自然地让整套界面重建。
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

            is ServerState.Configured -> AppShellHost(
                container = container,
                baseUrl = state.url,
            )
        }
    }
}

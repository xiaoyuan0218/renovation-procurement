package com.xiaoyuan.renovation.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "renovation_settings")

/** 服务器地址的三种处境：还没读出来 / 没配过 / 配好了。 */
sealed interface ServerState {
    data object Loading : ServerState
    data object NotConfigured : ServerState
    data class Configured(val url: String) : ServerState
}

/** 登录态的三种处境，和 [ServerState] 同样多一个 Loading 避免首帧闪登录页。 */
sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val username: String?) : SessionState
}

/** 服务器地址、登录 token 等偏好设置。都以 StateFlow 暴露，供 OkHttp 拦截器同步读取。 */
class SettingsStore(
    private val context: Context,
    scope: CoroutineScope,
) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyToken = stringPreferencesKey("auth_token")
    private val keyUsername = stringPreferencesKey("auth_username")

    val baseUrl: StateFlow<String?> = context.dataStore.data
        .map { it[keyBaseUrl]?.takeIf { url -> url.isNotBlank() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val token: StateFlow<String?> = context.dataStore.data
        .map { it[keyToken]?.takeIf { t -> t.isNotBlank() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val username: StateFlow<String?> = context.dataStore.data
        .map { it[keyUsername]?.takeIf { n -> n.isNotBlank() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 给顶层路由用：多一个 Loading 态，避免启动瞬间因为还没读到磁盘
     * 而闪一下"配置服务器"页面。
     */
    val serverState: StateFlow<ServerState> = context.dataStore.data
        .map { prefs ->
            val url = prefs[keyBaseUrl]?.takeIf { it.isNotBlank() }
            if (url == null) ServerState.NotConfigured else ServerState.Configured(url)
        }
        .stateIn(scope, SharingStarted.Eagerly, ServerState.Loading)

    val sessionState: StateFlow<SessionState> = context.dataStore.data
        .map { prefs ->
            val token = prefs[keyToken]?.takeIf { it.isNotBlank() }
            if (token == null) SessionState.LoggedOut
            else SessionState.LoggedIn(prefs[keyUsername]?.takeIf { it.isNotBlank() })
        }
        .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[keyBaseUrl] = url }
    }

    suspend fun clearBaseUrl() {
        context.dataStore.edit { it.remove(keyBaseUrl) }
    }

    suspend fun setSession(token: String, username: String) {
        context.dataStore.edit {
            it[keyToken] = token
            it[keyUsername] = username
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(keyToken)
            it.remove(keyUsername)
        }
    }

    /** 首次读取前先等一次磁盘，避免启动瞬间拦截器拿到 null。 */
    suspend fun warmUp() {
        context.dataStore.data.first()
    }
}

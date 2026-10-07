package com.xiaoyuan.renovation.mobile.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_prefs")

/**
 * 轻量配置：当前看的是哪份清单，以及四期要用的服务器地址与登录凭证。
 *
 * 这些**不进**本地数据库 —— 它们是"这台设备"的设置，不该跟着清单数据一起
 * 被同步或搬运到服务器上去。
 */
class AppPrefs(private val context: Context) {

    private object Keys {
        val CURRENT_LIST = intPreferencesKey("current_list_id")
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val TOKEN = stringPreferencesKey("token")
        val GUIDE_SEEN = booleanPreferencesKey("guide_seen")
        val DONATE_OFF = booleanPreferencesKey("donate_off")
    }

    /** 首次打开时弹一次新手引导，看过就不再打扰 */
    val guideSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.GUIDE_SEEN] ?: false }

    suspend fun setGuideSeen() {
        context.dataStore.edit { it[Keys.GUIDE_SEEN] = true }
    }

    /** 打赏提醒：默认开着，每次打开弹一次收款码；关掉后不再出现 */
    val donateEnabled: Flow<Boolean> = context.dataStore.data.map { !(it[Keys.DONATE_OFF] ?: false) }

    suspend fun setDonateEnabled(on: Boolean) {
        context.dataStore.edit { it[Keys.DONATE_OFF] = !on }
    }

    val currentListId: Flow<Int?> =
        context.dataStore.data.map { it[Keys.CURRENT_LIST] }

    suspend fun setCurrentListId(id: Int) {
        context.dataStore.edit { it[Keys.CURRENT_LIST] = id }
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[Keys.SERVER_URL] ?: "" }
    val username: Flow<String> = context.dataStore.data.map { it[Keys.USERNAME] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[Keys.TOKEN] ?: "" }

    suspend fun saveServer(url: String, user: String, token: String) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = url
            it[Keys.USERNAME] = user
            it[Keys.TOKEN] = token
        }
    }

    /** 退出登录：只清凭证，服务器地址留着（免得每次都要重填）。 */
    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(Keys.USERNAME)
            it.remove(Keys.TOKEN)
        }
    }
}

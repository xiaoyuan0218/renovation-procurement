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
/** 装在手机上的版本名（debug 包带 -debug 后缀，同一安装内稳定）。取不到就空串 —— 与任何记录都对不上，等于"没关过"。 */
private fun readVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

class AppPrefs(
    private val context: Context,
    /** 装在手机上的版本；测试可以换成别的版本模拟"更新了" */
    private val appVersion: String = readVersionName(context),
) {

    private object Keys {
        val CURRENT_LIST = intPreferencesKey("current_list_id")
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val TOKEN = stringPreferencesKey("token")
        val GUIDE_SEEN = booleanPreferencesKey("guide_seen")
        val DONATE_OFF = booleanPreferencesKey("donate_off")

        /** 在哪个版本里把打赏关掉的（见 donateEnabled） */
        val DONATE_OFF_VERSION = stringPreferencesKey("donate_off_version")
    }

    /** 首次打开时弹一次新手引导，看过就不再打扰 */
    val guideSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.GUIDE_SEEN] ?: false }

    suspend fun setGuideSeen() {
        context.dataStore.edit { it[Keys.GUIDE_SEEN] = true }
    }

    /**
     * 打赏提醒：默认开着，每次打开弹一次收款码。
     *
     * 「关掉」只对**当时那个版本**有效：更新到新版本后开关自动回到打开、再提示
     * 一次（在那个新版本里再关才算数）。所以关闭记录要连版本号一起存，读取时
     * 版本对不上就当作还开着。老记录（1.2.4 及以前关的）没有版本号，正好按这条
     * 规则重新打开。
     */
    val donateEnabled: Flow<Boolean> = context.dataStore.data.map {
        !donateOffStillValid(
            off = it[Keys.DONATE_OFF] ?: false,
            offVersion = it[Keys.DONATE_OFF_VERSION],
            currentVersion = appVersion,
        )
    }

    suspend fun setDonateEnabled(on: Boolean) {
        context.dataStore.edit {
            it[Keys.DONATE_OFF] = !on
            // 关掉时记下版本：将来换了新版本，这条记录就作废、自动重新打开
            if (on) it.remove(Keys.DONATE_OFF_VERSION) else it[Keys.DONATE_OFF_VERSION] = appVersion
        }
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

/**
 * 打赏的关闭记录还算不算数：只认"同一个版本里关的"。
 *
 * 更新到新版本（或重新部署换了一版前端）之后，老记录一律作废、开关自动回到
 * 打开；老版本留下的记录没有版本号（null），也按作废处理。
 */
internal fun donateOffStillValid(
    off: Boolean,
    offVersion: String?,
    currentVersion: String,
): Boolean = off && offVersion != null && offVersion == currentVersion

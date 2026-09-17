package com.xiaoyuan.renovation.mobile.data.repo

import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「现在看的是哪份清单」。
 *
 * 网络版靠给每个请求加 `X-List-Id` 头，单机版没有请求，就把这个上下文放在这里，
 * 由仓储读取。存的那份可能已经被删掉了，读取时一律落回第一份清单。
 */
class CurrentListHolder(
    private val prefs: AppPrefs,
    private val db: AppDatabase,
    scope: CoroutineScope,
) {

    private val _id = MutableStateFlow<Int?>(null)
    val flow: StateFlow<Int?> = _id.asStateFlow()

    init {
        scope.launch {
            prefs.currentListId.collect { stored ->
                _id.value = stored?.takeIf { db.lists().byId(it) != null }
                    ?: db.lists().all().firstOrNull()?.id
            }
        }
    }

    /** 取当前清单 id；一份清单都没有时抛错（容器启动时已经播过种子）。 */
    suspend fun require(): Int =
        _id.value ?: db.lists().all().firstOrNull()?.id ?: error("还没有任何清单")

    suspend fun set(id: Int) {
        prefs.setCurrentListId(id)
        _id.value = id
    }
}

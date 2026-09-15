package com.xiaoyuan.renovation.ui.common

/** 页面级加载状态：加载中 / 拿到数据 / 失败（带一句下一步建议）。 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
    data class Failed(val message: String, val hint: String? = null) : LoadState<Nothing>
}

val <T> LoadState<T>.dataOrNull: T? get() = (this as? LoadState.Ready)?.data
val <T> LoadState<T>.isFailed: Boolean get() = this is LoadState.Failed

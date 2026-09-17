package com.xiaoyuan.renovation.mobile.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoyuan.renovation.mobile.di.AppContainer

class SimpleVmFactory<VM : ViewModel>(private val creator: () -> VM) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}

/** 取一个挂在当前导航条目上的 ViewModel —— 同一个容器里同名 VM 会复用同一实例。 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    container: AppContainer,
    crossinline creator: (AppContainer) -> VM,
): VM {
    val factory = remember(container) { SimpleVmFactory { creator(container) } }
    return viewModel(factory = factory)
}

/**
 * 取一个挂在 Activity 上的 ViewModel。
 *
 * 主界面和设置子页面是两个不同的导航条目，[containerViewModel] 在各自条目里
 * 会各拿一份实例 —— 于是出现「设置里显示未连接、点进去其实连着」这种对不上的
 * 状态，传出去的改动（拉取清单）也不会立刻反映到主界面。要跨页面一致的用这个。
 */
@Composable
inline fun <reified VM : ViewModel> sharedViewModel(
    container: AppContainer,
    crossinline creator: (AppContainer) -> VM,
): VM {
    val owner = LocalContext.current as? ComponentActivity
        ?: error("sharedViewModel 需要 Activity 作为 ViewModelStoreOwner")
    val factory = remember(container) { SimpleVmFactory { creator(container) } }
    return viewModel(viewModelStoreOwner = owner, factory = factory)
}

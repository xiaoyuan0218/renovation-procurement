package com.xiaoyuan.renovation.mobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

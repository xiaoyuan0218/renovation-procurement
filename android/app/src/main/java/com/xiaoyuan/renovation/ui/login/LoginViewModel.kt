package com.xiaoyuan.renovation.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.prefs.SettingsStore
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录 / 创建管理员。两种形态共用一个界面：
 * 后端还没有账号时是"创建管理员"，否则是"登录"。
 */
class LoginViewModel(
    private val repo: RenovationRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    data class UiState(
        val username: String = "",
        val password: String = "",
        val confirm: String = "",
        val isSetup: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
    ) {
        val canSubmit: Boolean get() = username.isNotBlank() && !loading
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 问一次后端：该显示"创建管理员"还是"登录"。 */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repo.authState()) {
                is ApiResult.Ok -> _state.update {
                    it.copy(loading = false, isSetup = !result.data.initialized)
                }

                is ApiResult.Err -> _state.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onConfirmChange(value: String) = _state.update { it.copy(confirm = value, error = null) }

    /**
     * 登录成功后只做一件事：把 token 写进偏好设置。
     * 顶层路由监听到 sessionState 变化会自动切进主界面，这里不需要回调。
     */
    fun submit() {
        val current = _state.value
        val name = current.username.trim()

        val problem = when {
            name.isEmpty() -> "请填写用户名"
            current.password.length < 6 -> "密码至少 6 位"
            current.isSetup && current.password != current.confirm -> "两次输入的密码不一致"
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = if (current.isSetup) {
                repo.setup(name, current.password)
            } else {
                repo.login(name, current.password)
            }
            when (result) {
                is ApiResult.Ok -> {
                    settings.setSession(result.data.token, result.data.username)
                    _state.update { it.copy(loading = false, password = "", confirm = "") }
                }

                is ApiResult.Err -> _state.update {
                    it.copy(
                        loading = false,
                        password = "",
                        confirm = "",
                        error = buildString {
                            append(result.message)
                            result.hint?.let { hint -> append("：").append(hint) }
                        },
                    )
                }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}

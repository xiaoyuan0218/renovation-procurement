package com.xiaoyuan.renovation.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.prefs.SettingsStore
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.domain.AddressParse
import com.xiaoyuan.renovation.domain.ServerAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServerSetupViewModel(
    private val repo: RenovationRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    data class UiState(
        val input: String = "",
        val parse: AddressParse = AddressParse.Empty,
        val testing: Boolean = false,
        val saving: Boolean = false,
        val testMessage: String? = null,
        val testOk: Boolean = false,
        val testedAddress: String? = null,
        val askSaveAnyway: Boolean = false,
    ) {
        val normalized: String? get() = (parse as? AddressParse.Ok)?.normalized
        val canSubmit: Boolean get() = parse is AddressParse.Ok && !testing && !saving

        /** 当前输入还没被验证过（改动过或没测过）。 */
        val needsTest: Boolean get() = !testOk || testedAddress != normalized
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun prefill(existing: String?) {
        if (existing.isNullOrBlank()) return
        if (_state.value.input.isNotBlank()) return
        onInputChange(existing)
    }

    fun onInputChange(value: String) {
        _state.update {
            it.copy(
                input = value,
                parse = ServerAddress.parse(value),
                testMessage = null,
                testOk = false,
                testedAddress = null,
            )
        }
    }

    fun test() {
        val url = _state.value.normalized ?: return
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testMessage = null, testOk = false) }
            when (val result = repo.testConnection(url)) {
                is ApiResult.Ok -> {
                    _state.update {
                        it.copy(
                            testing = false,
                            testOk = true,
                            testedAddress = url,
                            // 探针打的是免登录的 /api/auth/state，顺带告诉用户下一步是登录还是建号
                            testMessage = if (result.data.initialized) {
                                "连接成功 · 服务器已就绪，下一步用账号登录"
                            } else {
                                "连接成功 · 服务器还没有账号，下一步创建管理员"
                            },
                        )
                    }
                }

                is ApiResult.Err -> {
                    _state.update {
                        it.copy(
                            testing = false,
                            testOk = false,
                            testedAddress = url,
                            testMessage = buildString {
                                append(result.message)
                                result.hint?.let { hint -> append("：").append(hint) }
                            },
                        )
                    }
                }
            }
        }
    }

    /** 保存并切换到主界面；[onSaved] 在主线程回调。 */
    fun save(onSaved: (String) -> Unit) {
        val current = _state.value
        val url = current.normalized ?: return
        if (current.needsTest && !current.askSaveAnyway) {
            _state.update { it.copy(askSaveAnyway = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            settings.setBaseUrl(url)
            _state.update { it.copy(saving = false, askSaveAnyway = false) }
            onSaved(url)
        }
    }

    fun dismissSaveAnyway() {
        _state.update { it.copy(askSaveAnyway = false) }
    }
}

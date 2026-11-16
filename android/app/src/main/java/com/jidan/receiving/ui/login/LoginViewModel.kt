package com.jidan.receiving.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jidan.receiving.AppContainer
import com.jidan.receiving.BuildConfig
import com.jidan.receiving.data.ApiError
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.SessionUser
import com.jidan.receiving.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String = "",
    val phone: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val error: String = "",
    val busy: Boolean = false,
    val expiredHint: Boolean = false,
    val updateUrl: String? = null,
    val updateLabel: String? = null,
) {
    val step: LoginStep get() = loginStep(serverUrl, phone, password, busy)
}

class LoginViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        LoginUiState(
            serverUrl = container.prefs.serverUrl,
            phone = container.prefs.lastPhone,
        ),
    )
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    init {
        refreshVersion()
    }

    fun markExpired() {
        _ui.update {
            it.copy(
                error = "登录已失效",
                expiredHint = true,
                password = "",
                phone = container.prefs.lastPhone.ifBlank { it.phone },
            )
        }
    }

    fun onServerUrl(value: String) {
        _ui.update { it.copy(serverUrl = value, error = "", expiredHint = false) }
    }

    fun onPhone(value: String) {
        _ui.update { it.copy(phone = digitsPhone(value), error = "", expiredHint = false) }
    }

    fun onPassword(value: String) {
        _ui.update { it.copy(password = value, error = "", expiredHint = false) }
    }

    fun togglePassword() {
        _ui.update { it.copy(showPassword = !it.showPassword) }
    }

    fun refreshVersion() {
        val base = container.prefs.normalizedBaseUrl()
        if (base.isEmpty()) {
            _ui.update { it.copy(updateUrl = null, updateLabel = null) }
            return
        }
        viewModelScope.launch {
            try {
                container.apiClient.invalidate()
                val v = container.auth.appVersion()
                val show = shouldShowUpdate(
                    BuildConfig.VERSION_CODE,
                    AppVersionLike(v.versionCode, v.downloadUrl),
                )
                _ui.update {
                    it.copy(
                        updateUrl = if (show) v.downloadUrl else null,
                        updateLabel = if (show) {
                            "有新版本 ${v.versionName ?: v.versionCode}，点此下载"
                        } else {
                            null
                        },
                    )
                }
            } catch (_: Exception) {
                // P2-2 / 部署未就绪时接口不存在，隐藏入口即可
                _ui.update { it.copy(updateUrl = null, updateLabel = null) }
            }
        }
    }

    fun login(onSuccess: () -> Unit) {
        val snap = _ui.value
        if (snap.step != LoginStep.READY) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = "") }
            container.prefs.serverUrl = snap.serverUrl
            container.apiClient.invalidate()
            try {
                val result = container.auth.login(digitsPhone(snap.phone), snap.password)
                container.prefs.lastPhone = digitsPhone(snap.phone)
                container.setSession(
                    SessionUser(
                        token = result.token,
                        name = result.name,
                        phone = digitsPhone(snap.phone),
                        role = result.role,
                    ),
                )
                // 待 P2-2：bootstrap 失败则保持 ch.4 默认值
                val boot = container.auth.bootstrapOrNull()
                container.applyOptions(AppOptions.from(boot?.options))
                _ui.update { it.copy(busy = false, password = "", error = "") }
                onSuccess()
            } catch (e: Throwable) {
                val msg = e.userMessage()
                val clearPw = e is ApiError && (e.code == "BAD_CREDENTIALS" || e.code == "WRONG_CLIENT")
                _ui.update {
                    it.copy(
                        busy = false,
                        error = msg,
                        password = if (clearPw) "" else it.password,
                    )
                }
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(container) as T
    }
}

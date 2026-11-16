package com.jidan.receiving.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jidan.receiving.AppContainer
import com.jidan.receiving.BuildConfig
import com.jidan.receiving.data.ApiError
import com.jidan.receiving.data.SessionUser
import com.jidan.receiving.data.userMessage
import com.jidan.receiving.ui.update.UpdateOffer
import com.jidan.receiving.ui.update.fetchUpdateOffer
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
    val update: UpdateOffer? = null,
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
            _ui.update { it.copy(update = null) }
            return
        }
        viewModelScope.launch {
            container.apiClient.invalidate()
            val offer = fetchUpdateOffer(BuildConfig.VERSION_CODE) { container.auth.appVersion() }
            _ui.update { it.copy(update = offer) }
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
                container.refreshOptions()
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

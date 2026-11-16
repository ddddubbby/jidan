package com.jidan.receiving

import android.content.Context
import com.jidan.receiving.data.ApiClient
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.AuthRepository
import com.jidan.receiving.data.Prefs
import com.jidan.receiving.data.SessionUser
import com.jidan.receiving.data.TokenStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Activity 级手工组装（开发规格 9.4）。P6-1 先接网络与登录；设备、Session 等后续任务往这里挂。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val prefs = Prefs(appContext)
    val tokenStore = TokenStore(appContext)

    private val _session = MutableStateFlow(tokenStore.readSession())
    val session: StateFlow<SessionUser?> = _session.asStateFlow()

    private val _options = MutableStateFlow(AppOptions.defaults())
    val options: StateFlow<AppOptions> = _options.asStateFlow()

    /** 任意接口返回 UNAUTHENTICATED(401) 时发出，MainActivity 转到登录页。 */
    private val _unauthenticated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthenticated: SharedFlow<Unit> = _unauthenticated.asSharedFlow()

    val apiClient = ApiClient(
        prefs = prefs,
        tokenStore = tokenStore,
        onUnauthenticated = {
            clearSession()
            _unauthenticated.tryEmit(Unit)
        },
    )

    val auth = AuthRepository(apiClient)

    fun setSession(user: SessionUser) {
        tokenStore.save(user)
        _session.value = user
    }

    fun clearSession() {
        tokenStore.clear()
        _session.value = null
        // 登录失效后选项回到默认；等 P2-2 bootstrap 合入后再在登录成功时刷新
        _options.value = AppOptions.defaults()
    }

    fun applyOptions(options: AppOptions) {
        _options.value = options
    }
}

fun Context.appContainer(): AppContainer =
    (applicationContext as ReceivingApp).container

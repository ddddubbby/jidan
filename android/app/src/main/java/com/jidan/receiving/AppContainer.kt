package com.jidan.receiving

import android.content.Context
import com.jidan.receiving.data.ApiClient
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.AuthRepository
import com.jidan.receiving.data.CatalogRepository
import com.jidan.receiving.data.OrderDraftStore
import com.jidan.receiving.data.OrderRepository
import com.jidan.receiving.data.Prefs
import com.jidan.receiving.data.SessionStore
import com.jidan.receiving.data.SessionUser
import com.jidan.receiving.data.TokenStore
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LinkKeeper
import com.jidan.receiving.device.Scale
import com.jidan.receiving.device.fake.FakePrinter
import com.jidan.receiving.device.fake.FakeScale
import com.jidan.receiving.platform.BluetoothGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Activity 级手工组装（开发规格 9.4）。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs = Prefs(appContext)
    val tokenStore = TokenStore(appContext)
    val bluetoothGate = BluetoothGate(appContext).also {
        // 模拟版无真蓝牙时仍可走查连接 UI
        if (DeviceFactory.isSim) it.forceReady = true
    }

    private val _session = MutableStateFlow(tokenStore.readSession())
    val session: StateFlow<SessionUser?> = _session.asStateFlow()

    private val _options = MutableStateFlow(AppOptions.defaults())
    val options: StateFlow<AppOptions> = _options.asStateFlow()

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
    val catalog = CatalogRepository(apiClient)
    val orders = OrderRepository(apiClient)
    val orderDraft = OrderDraftStore()
    val sessionStore = SessionStore(appContext)

    val scale: Scale = DeviceFactory.createScale(
        scope,
        prefs,
        _options.value.get("device.scale-model"),
    )
    val printer: LabelPrinter = DeviceFactory.createPrinter(
        scope,
        prefs,
        _options.value.get("device.printer-model"),
    )

    val linkKeeper = LinkKeeper(
        scale = scale,
        printer = printer,
        scope = scope,
        isScaleReachable = {
            val fake = scale as? FakeScale
            fake?.reachable ?: true
        },
        isPrinterReachable = {
            val fake = printer as? FakePrinter
            fake?.reachable ?: true
        },
        persistScaleId = { prefs.pairedScaleId = it },
        persistPrinterId = { prefs.pairedPrinterId = it },
    )

    fun setSession(user: SessionUser) {
        tokenStore.save(user)
        _session.value = user
        linkKeeper.start()
    }

    fun clearSession() {
        linkKeeper.stop()
        tokenStore.clear()
        _session.value = null
        _options.value = AppOptions.defaults()
        orderDraft.clear()
        // 退出登录不清称重草稿：重登后可恢复（开发规格 9.4）
    }

    fun applyOptions(options: AppOptions) {
        _options.value = options
    }

    /** 取 `GET /app/bootstrap` 的业务设置与选项。 */
    suspend fun refreshOptions() {
        applyOptions(AppOptions.from(auth.bootstrap().allValues()))
    }

    /** 进程启动时已有会话：恢复自动连，并重新取一次下发的设置。 */
    fun onAppStart() {
        if (_session.value == null) return
        linkKeeper.start()
        scope.launch { runCatching { refreshOptions() } }
    }
}

fun Context.appContainer(): AppContainer =
    (applicationContext as ReceivingApp).container

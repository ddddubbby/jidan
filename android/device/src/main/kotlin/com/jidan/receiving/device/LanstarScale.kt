package com.jidan.receiving.device

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.SystemClock
import android.util.Log
import com.ble.mylibrary.LansManager
import com.ble.mylibrary.interfaces.PeelMode
import com.ble.mylibrary.interfaces.WeightUpdateCallback
import com.ble.mylibrary.outdevice.LScaleHelper
import com.jidan.receiving.core.Jin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝石 BLE 电子秤适配器（开发规格 9.3、P8-1；v7 7.2）。
 *
 * **未经实物验证。** 开发方没有真秤，也没有蓝石的 `companyKey`；这个文件只做到“能编译、
 * 逐方法对照示例核对过调用顺序”。连上之后的一切（读数、稳定标志、去皮、电量、断线）
 * 都要联调方用真秤确认（阶段 H-1），确认前不要当作已通过。
 *
 * 依据：
 * - `io.github.lansdialog:blelibrary:2.5.9` 的二进制（`javap` 看公开签名；随包的
 *   sources.jar 与二进制不完全一致，只当注释参考）
 * - 示例仓库 `lans_ble_demo`（核对时的提交 9c83e97，2025-07-23）：`MainApp.kt`（初始化）、
 *   `PopBlueSearchPop.kt`（搜索、连接）、`MainActivity2.kt`（重量与电量回调）、
 *   `BleModel.kt`（去皮、数字皮、清皮的返回码）、`Presenter.java`（各接口的注释）
 * - v7 7.2：`setComponyKey(key).init(context)`；连接码 1 成功 / 2 失败 / 3 断开
 *
 * 和示例的调用顺序对应：
 * 1. 构造时（`Application.onCreate` 里）`LansManager.getInstance().[setComponyKey].init(app)`，
 *    紧接着 `setWUCallback` 与 `getBatteryStatus` 各注册一次——示例是在 `MainApp` 与首个
 *    Activity 的 `onCreate` 里做的，同样早于任何搜索与连接。
 * 2. `search()`：`startscan(false, cb)` → 等一段时间 → `stopscan()`。示例是弹窗打开时
 *    `startscan(true, cb)`、关闭时 `stopscan()`。这里传 `false`：连哪台由界面选，
 *    不让 SDK 自己挑设备连。
 * 3. `connect(id)`：`connect(BluetoothDevice, cb)`；`cb` 在连接存续期间一直有效，
 *    收到 1 成功、2 失败、3 主动断开、31 意外断开。
 * 4. 去皮：`tare()`、`setPeel("2.16")`、`clearTare()` 都是阻塞调用（内部 `take()` 等回调），
 *    所以放到 IO 线程里调。
 *
 * 与示例不同的地方：
 * - 不开 SDK 的自动连接（`setBleAutoConnect`、`setBleDisAutoConnect`）。自动重连归
 *   `LinkKeeper`，适配器里只剩对 SDK 的直接调用（开发规格 2.2）。
 * - 称重皮与数字皮互斥（v7 7.2）：设数字皮、称重去皮之前先 `clearTare()`，由适配器内部处理。
 * - `weight` 原样上报，不扣皮；读数是否已扣皮（`Q-03`）在 `NetWeight.kt` 处理。
 *
 * 未确认、等真秤定的事（都收成下面的常量，或在注释里写明）：
 * - 蓝石示例没说秤的蓝牙名长什么样，SDK 扫描只滤掉没名字的设备，所以列表里可能混进
 *   别的蓝牙设备。联调时把 [NAME_FILTER] 填成蓝石秤的名字特征。
 * - 秤的稳定标志、`isTared`、`peelMode` 与皮重的关系；电量低的门限（见 [LanstarMapping]）。
 * - `companyKey` 不填时 SDK 的表现。
 *
 * @param companyKey 向蓝石申请的 key，不入库；实机版从构建配置里取（见 `sdk/README.md`）。空则不调 `setComponyKey`。
 * @param initialPairedId 上次连接的设备 id（蓝牙地址），登录后由 `LinkKeeper` 自动连它。
 */
@SuppressLint("MissingPermission") // 蓝牙权限由 :app 的 BluetoothGate 在调用前把关（开发规格 9.3）
class LanstarScale(
    private val application: Application,
    companyKey: String,
    initialPairedId: String? = null,
    /** 选项 `scale.unit`：秤按公斤还是按斤计；每次读数、下发皮重时取当前值 */
    private val unit: () -> ScaleUnit = { ScaleUnit.KG },
) : Scale {
    private val _link = MutableStateFlow(LinkStatus.DISCONNECTED)
    private val _readings = MutableStateFlow<DeviceScaleReading?>(null)
    private val _batteryLow = MutableStateFlow(false)
    private val _paired = MutableStateFlow(initialPairedId)

    override val linkStatus: StateFlow<LinkStatus> = _link.asStateFlow()
    override val readings: StateFlow<DeviceScaleReading?> = _readings.asStateFlow()
    override val batteryLow: StateFlow<Boolean> = _batteryLow.asStateFlow()
    override val pairedId: StateFlow<String?> = _paired.asStateFlow()

    private val helper: LScaleHelper

    /** 最近一次搜索看到的设备，按蓝牙地址存；连接时优先用它。 */
    private val found = ConcurrentHashMap<String, BluetoothDevice>()

    /** 我们是否想连着：`connect` 置真，`disconnect` 置假。断开时据此分“意外丢失”与“主动断开”。 */
    @Volatile private var wanted = false

    /** 这次 `connect` 是从“丢失”状态发起的重连：失败时回到丢失，`LinkKeeper` 才会继续重试。 */
    @Volatile private var retryingFromLost = false

    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null

    /** `LansManager.init` 的时刻。SDK 在这之后 5 秒才去初始化蓝牙管理器（见 [awaitSdkReady]）。 */
    private val initAt = SystemClock.elapsedRealtime()

    init {
        val manager = LansManager.getInstance()
        if (companyKey.isNotBlank()) manager.setComponyKey(companyKey)
        manager.init(application)
        helper = LScaleHelper.getInstance(application)
        helper.setWUCallback(weightCallback())
        helper.getBatteryStatus { isCharge, _, percent ->
            _batteryLow.value = LanstarMapping.batteryLow(isCharge, percent)
        }
        if (companyKey.isBlank()) {
            Log.w(TAG, "未配置蓝石 companyKey：SDK 在没有 key 时的表现未验证")
        }
    }

    override suspend fun search(): List<DiscoveredDevice> {
        awaitSdkReady()
        val seen = ConcurrentHashMap<String, Int>()
        val started = runCatching {
            helper.startscan(false) { device, rssi ->
                val name = deviceName(device)
                if (name.isNotBlank() && NAME_FILTER?.containsMatchIn(name) != false) {
                    found[device.address] = device
                    seen[device.address] = rssi
                }
            }
        }.isSuccess
        if (!started) return emptyList()
        try {
            delay(SCAN_MS)
        } finally {
            runCatching { helper.stopscan() }
        }
        return seen.entries
            .sortedByDescending { it.value }
            .mapNotNull { (address, rssi) ->
                val device = found[address] ?: return@mapNotNull null
                DiscoveredDevice(address, deviceName(device), LanstarMapping.rssiBars(rssi))
            }
    }

    override suspend fun connect(id: String) {
        val device = found[id] ?: remoteDevice(id)
        if (device == null) {
            _link.value = LinkStatus.DISCONNECTED
            return
        }
        val wasLost = _link.value == LinkStatus.LOST
        // 一次只连一台：已连着就先断开，再连新的
        if (_link.value == LinkStatus.CONNECTED) {
            wanted = false
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runInterruptible(Dispatchers.IO) { helper.close() } }
        }
        retryingFromLost = wasLost
        wanted = true
        _paired.value = id
        _link.value = LinkStatus.CONNECTING
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        try {
            // 进程刚起来就自动连（LinkKeeper 登录后即连）时，SDK 的蓝牙管理器可能还没初始化好
            awaitSdkReady()
            helper.connect(device) { result, _ -> onConnectResult(result) }
            // 结果体现在连接状态流上；SDK 一直不回调时到时间按失败处理
            val done = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() }
            if (done == null) onConnectResult(LanstarMapping.CONNECT_FAILED)
        } catch (e: CancellationException) {
            // 被取消（如退出登录停掉 LinkKeeper）时不能把状态留在“连接中”，否则之后不会再连
            onConnectResult(LanstarMapping.CONNECT_FAILED)
            throw e
        }
    }

    override suspend fun disconnect() {
        wanted = false
        connectWaiter?.complete(Unit)
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) { runInterruptible(Dispatchers.IO) { helper.close() } }
        _link.value = LinkStatus.DISCONNECTED
        _readings.value = null
    }

    override suspend fun setDigitalTare(jin: Jin) {
        callBlocking {
            // 称重皮与数字皮互斥：先清皮。返回值不看——没有皮时清皮的返回码示例没写
            helper.clearTare()
            if (jin.cents <= 0) return@callBlocking
            LanstarMapping.setPeelFailure(helper.setPeel(LanstarMapping.peelText(jin, unit())))?.let { error(it) }
        }
    }

    override suspend fun tareWeigh() {
        callBlocking {
            helper.clearTare()
            LanstarMapping.tareFailure(helper.tare())?.let { error(it) }
        }
    }

    override suspend fun clearTare() {
        callBlocking {
            LanstarMapping.clearTareFailure(helper.clearTare())?.let { error(it) }
        }
    }

    /**
     * `LansManager.init` 里有一句 `Handler(mainLooper).postDelayed({ 没连上就 BleManager.init }, 5000)`
     * （二进制里能看到 `ldc2_w 5000`）：蓝牙管理器要等 5 秒才初始化。示例是人点了才搜、才连，
     * 碰不到这个窗口；这里 `LinkKeeper` 会在登录后立刻连，所以搜索与连接前先等过这 5 秒。
     */
    private suspend fun awaitSdkReady() {
        val wait = initAt + SDK_INIT_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
    }

    /** 去皮相关的 SDK 方法会阻塞到回调回来（或 SDK 自己超时），放 IO 线程，并设一个总时限。 */
    private suspend fun callBlocking(block: () -> Unit) {
        withTimeout(TARE_TIMEOUT_MS) { runInterruptible(Dispatchers.IO) { block() } }
    }

    private fun weightCallback() = object : WeightUpdateCallback {
        override fun onWeightUpdate(
            weight: BigDecimal?,
            isStable: Boolean,
            isTared: Boolean,
            isZero: Boolean,
            peelMode: PeelMode?,
            dataStatus: Int,
            error: Boolean,
        ) {
            if (error) {
                // 注册回调时 SDK 会立刻用 error=true 调一次，此时本就未连接，onDisconnected 里会略过
                when (dataStatus) {
                    LanstarMapping.DISCONNECT_ACTIVE, LanstarMapping.DISCONNECT_ACCIDENT -> onDisconnected()
                    LanstarMapping.DATA_OVERLOAD, LanstarMapping.DATA_NEGATIVE -> _readings.value = null
                }
                return
            }
            if (weight == null || _link.value != LinkStatus.CONNECTED) return
            _readings.value = DeviceScaleReading(LanstarMapping.toJin(weight, unit()), isStable)
        }

        override fun onrssi(rssi: Int) = Unit
    }

    /** 连接回调（只有 connect 注册）：1 成功、2 失败、3 主动断开、31 意外断开，其余（如 21、22）按失败。 */
    private fun onConnectResult(result: Int) {
        when (result) {
            LanstarMapping.CONNECT_OK -> {
                _link.value = LinkStatus.CONNECTED
                connectWaiter?.complete(Unit)
            }
            LanstarMapping.DISCONNECT_ACTIVE, LanstarMapping.DISCONNECT_ACCIDENT -> onDisconnected()
            else -> {
                if (_link.value == LinkStatus.CONNECTING) {
                    _link.value = if (retryingFromLost) LinkStatus.LOST else LinkStatus.DISCONNECTED
                }
                connectWaiter?.complete(Unit)
            }
        }
    }

    /** 只处理从“已连接”掉线；连接中、未连接时收到的断开通知是上一次连接的尾声，略过。 */
    private fun onDisconnected() {
        if (_link.value != LinkStatus.CONNECTED) return
        _readings.value = null
        _link.value = if (wanted) LinkStatus.LOST else LinkStatus.DISCONNECTED
    }

    private fun remoteDevice(address: String): BluetoothDevice? = runCatching {
        application.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
    }.getOrNull()

    private fun deviceName(device: BluetoothDevice): String =
        try {
            device.name.orEmpty()
        } catch (_: SecurityException) {
            ""
        }

    override fun toString(): String = "LanstarScale"

    companion object {
        private const val TAG = "LanstarScale"

        /** SDK 初始化蓝牙管理器的延迟是 5000ms，再留 500ms。 */
        const val SDK_INIT_MS = 5500L

        /** 一次搜索扫多久。SDK 默认扫描超时是 2s，这里留宽一点。 */
        const val SCAN_MS = 3000L

        /** SDK 自己的连接超时是每次 1s、重试 3 次；SDK 一直没回调时，到这个时间按失败处理。 */
        const val CONNECT_TIMEOUT_MS = 15_000L

        const val CLOSE_TIMEOUT_MS = 3000L

        /** 去皮、设皮、清皮各自的总时限；SDK 超时会自己返回 0xff，这里是兜底。 */
        const val TARE_TIMEOUT_MS = 8000L

        /**
         * 搜索结果只留名字匹配的设备。蓝石秤的蓝牙名特征开发方不知道，所以默认 `null`
         * 不滤；联调时按真秤的名字填，如 `Regex("^LS")`。
         */
        val NAME_FILTER: Regex? = null
    }
}

package com.jidan.receiving.device.fake

import com.jidan.receiving.core.Kg
import com.jidan.receiving.device.DeviceScaleReading
import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 模拟秤（对照 lab/app-sim.html）。未经实物验证。
 * 异常开关供第 4 章选项走查：读数未扣皮 / 稳定标志失灵 / 去皮指令无效。
 */
class FakeScale(
    private val scope: CoroutineScope,
    initialPairedId: String? = MINE_ID,
) : Scale {
    private val _link = MutableStateFlow(LinkStatus.DISCONNECTED)
    private val _readings = MutableStateFlow<DeviceScaleReading?>(null)
    private val _batteryLow = MutableStateFlow(false)
    private val _paired = MutableStateFlow(initialPairedId)

    override val linkStatus: StateFlow<LinkStatus> = _link.asStateFlow()
    override val readings: StateFlow<DeviceScaleReading?> = _readings.asStateFlow()
    override val batteryLow: StateFlow<Boolean> = _batteryLow.asStateFlow()
    override val pairedId: StateFlow<String?> = _paired.asStateFlow()

    /** 设备是否在手机附近（模拟台「断开/恢复」）。 */
    var reachable: Boolean = true
        set(value) {
            field = value
            if (!value && _link.value == LinkStatus.CONNECTED) {
                _link.value = LinkStatus.LOST
                _readings.value = null
            }
        }

    var batteryLowFlag: Boolean
        get() = _batteryLow.value
        set(value) { _batteryLow.value = value }

    /** 读数未扣皮：上报值多加一份当前数字皮（毛重伪装成净重场景的反面）。 */
    var anomalyReadingNotNet: Boolean = false

    /** 稳定标志失灵：stable 恒为 false。 */
    var anomalyStableBroken: Boolean = false

    /** 去皮指令无效：忽略 SDK 去皮调用。 */
    var anomalyTareIgnored: Boolean = false

    /** 内部台面目标重量（0.01kg），模拟放箱。 */
    private var targetCenti: Int = 0
    private var settleJob: Job? = null
    private var connectJob: Job? = null

    /** 当前数字皮（0.01kg）；仅 Fake 内部记，真正换算在 :core。 */
    private var digitalTareCenti: Int = 0

    val catalog: List<DiscoveredDevice> = listOf(
        DiscoveredDevice(MINE_ID, "LS-S2", 3),
        DiscoveredDevice("B812", "LS-S2", 2),
        DiscoveredDevice("0C47", "LS-S1", 1),
    )

    override suspend fun search(): List<DiscoveredDevice> =
        if (reachable) catalog else emptyList()

    override suspend fun connect(id: String) {
        connectJob?.cancel()
        settleJob?.cancel()
        if (!reachable || catalog.none { it.id == id }) {
            _link.value = LinkStatus.DISCONNECTED
            return
        }
        _paired.value = id
        _link.value = LinkStatus.CONNECTING
        val job = scope.launch {
            delay(CONNECT_MS)
            if (!reachable) {
                _link.value = LinkStatus.LOST
                return@launch
            }
            _link.value = LinkStatus.CONNECTED
            emitReading(stable = targetCenti == 0)
        }
        connectJob = job
        job.join()
    }

    override suspend fun disconnect() {
        connectJob?.cancel()
        settleJob?.cancel()
        _link.value = LinkStatus.DISCONNECTED
        _readings.value = null
    }

    override suspend fun setDigitalTare(kg: Kg) {
        if (anomalyTareIgnored) return
        digitalTareCenti = kg.cents
    }

    override suspend fun tareWeigh() {
        if (anomalyTareIgnored) return
        // 空箱称重皮：取当前台面读数
        digitalTareCenti = targetCenti.coerceAtLeast(0)
    }

    override suspend fun clearTare() {
        if (anomalyTareIgnored) return
        digitalTareCenti = 0
    }

    /** 模拟台：设置台面重量（0.01kg）。先不稳定再稳定。 */
    fun setLoadCenti(centi: Int) {
        targetCenti = centi.coerceAtLeast(0)
        settleJob?.cancel()
        if (_link.value != LinkStatus.CONNECTED) return
        settleJob = scope.launch {
            emitReading(stable = false)
            delay(UNSTABLE_MS)
            emitReading(stable = true)
        }
    }

    fun placeOneBox(netCenti: Int, boxTareCenti: Int = BOX_TARE_C) {
        setLoadCenti(netCenti + boxTareCenti)
    }

    fun placeBoxes(netCentis: List<Int>, boxTareCenti: Int = BOX_TARE_C) {
        setLoadCenti(netCentis.sum() + boxTareCenti * netCentis.size)
    }

    fun placeEmptyBox(boxTareCenti: Int = BOX_TARE_C) {
        setLoadCenti(boxTareCenti)
    }

    fun removeLoad() {
        setLoadCenti(0)
    }

    private fun emitReading(stable: Boolean) {
        if (_link.value != LinkStatus.CONNECTED) {
            _readings.value = null
            return
        }
        var raw = targetCenti
        if (anomalyReadingNotNet && digitalTareCenti > 0) {
            // 读数未扣皮：多报一份皮重，模拟秤回传毛重
            raw += digitalTareCenti
        }
        val s = if (anomalyStableBroken) false else stable
        _readings.value = DeviceScaleReading(Kg(raw), s)
    }

    companion object {
        const val MINE_ID = "A3F1"
        const val CONNECT_MS = 1000L
        const val UNSTABLE_MS = 900L
        const val BOX_TARE_C = 200 // 2.00kg

        /** 李四 10 箱净重算例（0.01kg），fixtures / 原型 CASE_NETS_C。 */
        val CASE_NETS_C = intArrayOf(4650, 4580, 4600, 4600, 4600, 4560, 4560, 4560, 4560, 4780)
    }
}

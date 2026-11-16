package com.jidan.receiving.registry

import com.jidan.receiving.core.Kg
import com.jidan.receiving.device.DeviceScaleReading
import com.jidan.receiving.device.DiscoveredDevice
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.Scale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实机版占位秤（P6-2）。P8-1 换成 LanstarScale。
 * 未经实物验证。
 */
class PlaceholderScale(
    private val modelKey: String = "lanstar-ble",
) : Scale {
    private val _link = MutableStateFlow(LinkStatus.DISCONNECTED)
    private val _readings = MutableStateFlow<DeviceScaleReading?>(null)
    private val _battery = MutableStateFlow(false)
    private val _paired = MutableStateFlow<String?>(null)

    override val linkStatus: StateFlow<LinkStatus> = _link.asStateFlow()
    override val readings: StateFlow<DeviceScaleReading?> = _readings.asStateFlow()
    override val batteryLow: StateFlow<Boolean> = _battery.asStateFlow()
    override val pairedId: StateFlow<String?> = _paired.asStateFlow()

    override suspend fun search(): List<DiscoveredDevice> = emptyList()
    override suspend fun connect(id: String) {
        _paired.value = id
        _link.value = LinkStatus.DISCONNECTED
    }
    override suspend fun disconnect() {
        _link.value = LinkStatus.DISCONNECTED
        _readings.value = null
    }
    override suspend fun setDigitalTare(kg: Kg) {}
    override suspend fun tareWeigh() {}
    override suspend fun clearTare() {}

    override fun toString(): String = "PlaceholderScale($modelKey)"
}

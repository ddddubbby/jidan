package com.jidan.receiving.device

import com.jidan.receiving.core.Kg
import kotlinx.coroutines.flow.StateFlow

/**
 * 电子秤契约（开发规格 9.3）。界面与称重状态机只认此接口。
 * 去皮三方法在 `scale.tare-by = app` 时不会被调用。
 */
interface Scale {
    val linkStatus: StateFlow<LinkStatus>
    val readings: StateFlow<DeviceScaleReading?>
    val batteryLow: StateFlow<Boolean>
    /** 已配对设备 id（若有） */
    val pairedId: StateFlow<String?>

    suspend fun search(): List<DiscoveredDevice>
    suspend fun connect(id: String)
    suspend fun disconnect()

    suspend fun setDigitalTare(kg: Kg)
    suspend fun tareWeigh()
    suspend fun clearTare()
}

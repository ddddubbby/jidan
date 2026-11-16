package com.jidan.receiving

import com.jidan.receiving.data.Prefs
import com.jidan.receiving.registry.DeviceRegistry
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.Scale
import kotlinx.coroutines.CoroutineScope

/**
 * 实机版：按型号取 DeviceRegistry（秤为占位，打印机为 GainschaPrinter 但 SDK 未接入，均未经实物验证）。
 */
object DeviceFactory {
    const val variantName = "实机版"
    const val isSim = false

    fun createScale(scope: CoroutineScope, prefs: Prefs, scaleModel: String?): Scale =
        DeviceRegistry.createScale(scaleModel)

    fun createPrinter(scope: CoroutineScope, prefs: Prefs, printerModel: String?): LabelPrinter =
        DeviceRegistry.createPrinter(printerModel, scope, prefs.pairedPrinterId)
}

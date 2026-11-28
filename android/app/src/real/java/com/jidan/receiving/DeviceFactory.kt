package com.jidan.receiving

import android.app.Application
import android.content.Context
import com.jidan.receiving.data.Prefs
import com.jidan.receiving.registry.DeviceRegistry
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.Scale
import com.jidan.receiving.device.ScaleUnit
import kotlinx.coroutines.CoroutineScope

/**
 * 实机版：按型号取 DeviceRegistry（秤是 LanstarScale（P8-1），打印机是 GainschaPrinter + 佳博 SDK2（P8-2）；均未经实物验证）。
 */
object DeviceFactory {
    const val variantName = "实机版"
    const val isSim = false

    fun createScale(context: Context, scope: CoroutineScope, prefs: Prefs, scaleModel: String?, unit: () -> ScaleUnit): Scale =
        DeviceRegistry.createScale(
            scaleModel,
            context.applicationContext as Application,
            prefs.pairedScaleId,
            BuildConfig.LANSTAR_COMPANY_KEY,
            unit,
        )

    fun createPrinter(context: Context, scope: CoroutineScope, prefs: Prefs, printerModel: String?): LabelPrinter =
        DeviceRegistry.createPrinter(printerModel, context, scope, prefs.pairedPrinterId)
}

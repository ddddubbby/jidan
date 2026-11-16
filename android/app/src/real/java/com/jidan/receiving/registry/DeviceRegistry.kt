package com.jidan.receiving.registry

import android.app.Application
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LanstarScale
import com.jidan.receiving.device.Scale

/**
 * 型号 → 适配器（开发规格 9.3）。秤一行已是 LanstarScale（P8-1），打印机一行仍是占位（P8-2 换）。
 * 不认识的型号用默认型号。
 */
object DeviceRegistry {
    const val DEFAULT_SCALE = "lanstar-ble"
    const val DEFAULT_PRINTER = "gainscha-gp-m32b"

    /** [companyKey] 是蓝石 SDK 的 key，不入库，由构建配置给（见 sdk/README.md）。 */
    fun createScale(
        model: String?,
        application: Application,
        pairedId: String?,
        companyKey: String,
    ): Scale {
        val key = model?.takeIf { it.isNotBlank() } ?: DEFAULT_SCALE
        return when (key) {
            "lanstar-ble" -> LanstarScale(application, companyKey, pairedId)
            else -> LanstarScale(application, companyKey, pairedId) // 不认识的型号用默认型号
        }
    }

    fun createPrinter(model: String?): LabelPrinter {
        val key = model?.takeIf { it.isNotBlank() } ?: DEFAULT_PRINTER
        return when (key) {
            "gainscha-gp-m32b" -> PlaceholderPrinter(key)
            else -> PlaceholderPrinter(DEFAULT_PRINTER)
        }
    }
}

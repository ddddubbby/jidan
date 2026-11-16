package com.jidan.receiving.registry

import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.Scale

/**
 * 型号 → 适配器（开发规格 9.3）。P6-2 两行都是占位；P8-1/P8-2 换成真适配器。
 * 不认识的型号用默认型号。
 */
object DeviceRegistry {
    const val DEFAULT_SCALE = "lanstar-ble"
    const val DEFAULT_PRINTER = "gainscha-gp-m32b"

    fun createScale(model: String?): Scale {
        val key = model?.takeIf { it.isNotBlank() } ?: DEFAULT_SCALE
        return when (key) {
            "lanstar-ble" -> PlaceholderScale(key)
            else -> PlaceholderScale(DEFAULT_SCALE)
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

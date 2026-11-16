package com.jidan.receiving.registry

import android.app.Application
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.LanstarScale
import com.jidan.receiving.device.Scale
import com.jidan.receiving.device.gainscha.GainschaPrinter
import com.jidan.receiving.device.gainscha.GainschaSdkMissing
import kotlinx.coroutines.CoroutineScope

/**
 * 型号 → 适配器（开发规格 9.3）。秤一行是 LanstarScale（P8-1）；打印机一行是 GainschaPrinter（P8-2），
 * 其 SDK 一层现在是 GainschaSdkMissing（缺 sdk2-release.aar，接法见 android/device/sdk-link/README.md）。
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

    fun createPrinter(model: String?, scope: CoroutineScope, pairedId: String?): LabelPrinter {
        val key = model?.takeIf { it.isNotBlank() } ?: DEFAULT_PRINTER
        return when (key) {
            // 接上 SDK 时，只把这里的 GainschaSdkMissing() 换成真实现（android/device/sdk-link/README.md 第 3 步）
            "gainscha-gp-m32b" -> GainschaPrinter(GainschaSdkMissing(), scope, pairedId)
            else -> GainschaPrinter(GainschaSdkMissing(), scope, pairedId)
        }
    }
}

package com.jidan.receiving.device

import com.jidan.receiving.core.Kg

/** 与原型 link.status 对齐：未连接 / 连接中 / 已连接 / 丢失 */
enum class LinkStatus { DISCONNECTED, CONNECTING, CONNECTED, LOST }

/** 搜索列表一行；信号 1–3 格（原型 bars） */
data class DiscoveredDevice(val id: String, val model: String, val rssiBars: Int)

/**
 * 秤回调原值（0.01kg）与是否稳定。不做扣皮（开发规格 9.3）。
 */
data class DeviceScaleReading(val raw: Kg, val stable: Boolean)

enum class PrinterIssue { OK, NO_PAPER, OPEN_COVER, OVERHEAT }

sealed class PrintOutcome {
    data object Success : PrintOutcome()
    data class Failure(val reason: String) : PrintOutcome()
}

/**
 * Text 是拼好的 TSPL 指令文本；Bitmap 是整张标签的位图（PNG，200 DPI，1mm = 8 点），
 * 位图没有纸张间隙信息，随带 [gapMm] 供适配器拼 TSPL 的 GAP。
 */
sealed class PrintPayload {
    data class Text(val tspl: String) : PrintPayload()
    data class Bitmap(val png: ByteArray, val gapMm: Float = 2f) : PrintPayload() {
        override fun equals(other: Any?): Boolean =
            other is Bitmap && png.contentEquals(other.png) && gapMm == other.gapMm
        override fun hashCode(): Int = png.contentHashCode() * 31 + gapMm.hashCode()
    }
}

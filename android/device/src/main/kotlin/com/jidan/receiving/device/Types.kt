package com.jidan.receiving.device

import com.jidan.receiving.core.Jin

/** 与原型 link.status 对齐：未连接 / 连接中 / 已连接 / 丢失 */
enum class LinkStatus { DISCONNECTED, CONNECTING, CONNECTED, LOST }

/** 搜索列表一行；信号 1–3 格（原型 bars） */
data class DiscoveredDevice(val id: String, val model: String, val rssiBars: Int)

/**
 * 秤回调原值（0.01斤，已按 [ScaleUnit] 换成斤）与是否稳定。不做扣皮（开发规格 9.3）。
 */
data class DeviceScaleReading(val raw: Jin, val stable: Boolean)

/**
 * 秤本身显示和回传的单位（选项 `scale.unit`）。系统里的重量一律是斤，秤按公斤计时读数 ×2、下发皮重 ÷2，
 * 只在秤适配器里换算（附录 E R-1 行）。默认 kg：蓝石秤出厂按公斤计，未经实物验证。
 */
enum class ScaleUnit(val jinPerUnit: Int) {
    KG(2),
    JIN(1);

    companion object {
        /** 选项值 `kg` / `jin`；认不出时按 kg。 */
        fun of(v: String?): ScaleUnit = if (v?.trim().equals("jin", ignoreCase = true)) JIN else KG
    }
}

enum class PrinterIssue { OK, NO_PAPER, OPEN_COVER, OVERHEAT }

sealed class PrintOutcome {
    data object Success : PrintOutcome()
    data class Failure(val reason: String) : PrintOutcome()
}

/**
 * Text 是拼好的 TSPL 指令文本；Bitmap 是整张标签的位图（PNG，每毫米点数见 [Bitmap.dotsPerMm]，默认 203 DPI 即 8 点），
 * 位图没有纸张间隙信息，随带 [gapMm] 供适配器拼 TSPL 的 GAP。
 */
sealed class PrintPayload {
    data class Text(val tspl: String) : PrintPayload()
    data class Bitmap(
        val png: ByteArray,
        val gapMm: Float = 2f,
        val dotsPerMm: Int = 8,
    ) : PrintPayload() {
        override fun equals(other: Any?): Boolean =
            other is Bitmap && png.contentEquals(other.png) && gapMm == other.gapMm && dotsPerMm == other.dotsPerMm
        override fun hashCode(): Int = (png.contentHashCode() * 31 + gapMm.hashCode()) * 31 + dotsPerMm
    }
}

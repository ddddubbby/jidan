package com.jidan.receiving.device

import com.jidan.receiving.core.Jin
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 蓝石 SDK 回传值到契约值的换算与错误文案。不引用 SDK 的类，只收基本类型，
 * 所以能在 JVM 单测里跑；调用 SDK 的部分都在 [LanstarScale]。
 *
 * 状态码与返回码的含义抄自 lans_ble_demo 的 `Presenter.java` 注释与 `BleModel.kt`、
 * `MainActivity2.kt`（见 [LanstarScale] 文件头）。未经实物验证。
 */
internal object LanstarMapping {
    /** 连接回调 `result`：成功 */
    const val CONNECT_OK = 1

    /** 连接回调 `result`：失败 */
    const val CONNECT_FAILED = 2

    /** 连接回调 `result`：主动断开（我们自己调了 `close()`） */
    const val DISCONNECT_ACTIVE = 3

    /** 连接回调 `result`：意外断开 */
    const val DISCONNECT_ACCIDENT = 31

    /** 重量回调 `dataStatus`（`error = true` 时）：过载 */
    const val DATA_OVERLOAD = 4

    /** 重量回调 `dataStatus`（`error = true` 时）：负重（开机后取掉秤盘） */
    const val DATA_NEGATIVE = 5

    /** 秤盘读数（秤的单位 [unit]，BigDecimal）→ 0.01 斤整数；换算后第 3 位起四舍五入。 */
    fun toJin(weight: BigDecimal, unit: ScaleUnit): Jin =
        Jin(weight.multiply(BigDecimal(unit.jinPerUnit)).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt())

    /** `setPeel` 的参数是秤的单位下的文本（示例传 `"2.16"`），固定两位小数；斤换成秤的单位。 */
    fun peelText(jin: Jin, unit: ScaleUnit): String =
        BigDecimal(jin.cents).movePointLeft(2).divide(BigDecimal(unit.jinPerUnit), 2, RoundingMode.HALF_UP).toPlainString()

    /**
     * 信号 1–3 格。扫描回调的 rssi 是负的 dBm（0 到 -100，越大越强）；
     * 系统经典蓝牙发现路径给 -1，当作未知，显示 1 格。阈值是估的，未经实物验证。
     */
    fun rssiBars(rssi: Int): Int = when {
        rssi == -1 -> 1
        rssi >= -60 -> 3
        rssi >= -75 -> 2
        else -> 1
    }

    /**
     * 电量低：没在充电，且百分比不高于 [LOW_BATTERY_PERCENT]。
     * `getBatteryStatus` 的第三个参数是百分比文本（示例注释只写“电量百分比”，
     * 是否带 `%` 未知），取其中的数字；取不到就不报低。
     */
    fun batteryLow(isCharging: Boolean, percentText: String?): Boolean {
        if (isCharging) return false
        val pct = percentText?.let { DIGITS.find(it)?.value?.toDoubleOrNull() } ?: return false
        return pct <= LOW_BATTERY_PERCENT
    }

    /** “秤电量低”的阈值。SDK 没给低电门限，这是估值；联调时按实物改这一个常量。 */
    const val LOW_BATTERY_PERCENT = 20

    private val DIGITS = Regex("""\d+(\.\d+)?""")

    /** `tare()` 返回码 → 失败原因；成功（1）与旧版无返回值（-1）返回 null。 */
    fun tareFailure(code: Int): String? = when (code) {
        1, -1 -> null
        0 -> "秤未连接"
        2 -> "秤零点错误报警"
        3 -> "超过去皮范围"
        4 -> "秤上已有数字皮，不能称重去皮"
        5 -> "重量不稳定，不能去皮"
        0xff -> "秤去皮超时"
        else -> "秤去皮失败（返回码 $code）"
    }

    /** `setPeel()` 返回码 → 失败原因；成功（1）与旧版无成功状态返回（0xee）返回 null。 */
    fun setPeelFailure(code: Int): String? = when (code) {
        1, 0xee -> null
        0 -> "秤未连接"
        2 -> "秤上已有称重皮，设数字皮失败"
        3 -> "重量不稳定，不能设皮重"
        4 -> "秤零点错误"
        5 -> "皮重超过去皮范围"
        6 -> "皮重值不合法"
        0xff -> "秤设皮重超时"
        else -> "秤设皮重失败（返回码 $code）"
    }

    /** `clearTare()` 返回码 → 失败原因；成功（1）返回 null。 */
    fun clearTareFailure(code: Int): String? = when (code) {
        1 -> null
        -2 -> "这台秤的固件不支持清皮"
        0 -> "秤未连接"
        0xff -> "秤清皮超时"
        else -> "秤清皮失败（返回码 $code）"
    }
}

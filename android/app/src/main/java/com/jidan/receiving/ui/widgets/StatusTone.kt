package com.jidan.receiving.ui.widgets

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.jidan.receiving.R
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.PrinterIssue

/**
 * 状态的四种语气（原型 .chip / .chip.warn / .chip.err / .chip.busy 与 .dev-line .st）：
 * 正常绿、要注意黄、坏了红、还在连灰。红只给真出了故障的（断开、连不上）；没连过、还在连不是故障。
 */
enum class StatusTone(@ColorRes val ink: Int, @ColorRes val bg: Int, @ColorRes val dot: Int) {
    OK(R.color.ok_ink, R.color.ok_bg, R.color.accent),
    WARN(R.color.warn_ink, R.color.warn_bg, R.color.warn_ink),
    ERR(R.color.err_ink, R.color.err_bg, R.color.err_ink),
    BUSY(R.color.ink_2, R.color.sunken, R.color.faint),
}

/** 称重页顶部那两颗芯片的内容（纯数据，没有 Android 依赖，单测直接用）。 */
data class StatusChip(val text: String, val tone: StatusTone)

/** 原型 live()：秤已连接（电量低改黄）、秤连接中、秤未连接；本 APP 另有“秤断开了”（连上过又断了，红）。 */
fun scaleChip(status: LinkStatus, batteryLow: Boolean, lost: Boolean = false): StatusChip = when {
    lost || status == LinkStatus.LOST -> StatusChip("秤断开了", StatusTone.ERR)
    status == LinkStatus.CONNECTED ->
        if (batteryLow) StatusChip("秤电量低", StatusTone.WARN) else StatusChip("秤已连接", StatusTone.OK)
    status == LinkStatus.CONNECTING -> StatusChip("秤连接中", StatusTone.BUSY)
    else -> StatusChip("秤未连接", StatusTone.ERR)
}

/** 原型 live()：打印机就绪（绿）、连接中（灰）、没连或缺纸开盖过热（黄）。打印机问题不拦称重（v7 APP-01），所以不用红。 */
fun printerChip(status: LinkStatus, issue: PrinterIssue): StatusChip = when (status) {
    LinkStatus.CONNECTING -> StatusChip("打印机连接中", StatusTone.BUSY)
    LinkStatus.CONNECTED -> when (issue) {
        PrinterIssue.OK -> StatusChip("打印机就绪", StatusTone.OK)
        PrinterIssue.NO_PAPER -> StatusChip("打印机缺纸", StatusTone.WARN)
        PrinterIssue.OPEN_COVER -> StatusChip("打印机开盖", StatusTone.WARN)
        PrinterIssue.OVERHEAT -> StatusChip("打印机过热", StatusTone.WARN)
    }
    LinkStatus.LOST, LinkStatus.DISCONNECTED -> StatusChip("打印机未连接", StatusTone.WARN)
}

/** 文字前一颗圆点、整体按语气着色的芯片（样式 Chip.Status 带圆点底图）。 */
fun TextView.bindChip(chip: StatusChip) {
    text = chip.text
    val ctx = context
    setTextColor(ContextCompat.getColor(ctx, chip.tone.ink))
    ViewCompat.setBackgroundTintList(this, ColorStateList.valueOf(ContextCompat.getColor(ctx, chip.tone.bg)))
    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(ContextCompat.getColor(ctx, chip.tone.dot)))
}

/** 一行提示（Text.Event）：底色与字色随语气。 */
fun TextView.bindEventTone(tone: StatusTone?) {
    val ctx = context
    val ink = when (tone) {
        StatusTone.OK -> R.color.ok_ink
        StatusTone.WARN -> R.color.warn_ink
        StatusTone.ERR -> R.color.err_ink
        else -> R.color.ink_2
    }
    val bg = when (tone) {
        StatusTone.OK -> R.color.ok_bg
        StatusTone.WARN -> R.color.warn_bg
        StatusTone.ERR -> R.color.err_bg
        else -> R.color.sunken
    }
    setTextColor(ContextCompat.getColor(ctx, ink))
    ViewCompat.setBackgroundTintList(this, ColorStateList.valueOf(ContextCompat.getColor(ctx, bg)))
}

/** 文字前的圆点着色（启动卡片、设备行用）。 */
fun TextView.tintDot(@ColorRes color: Int) {
    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(ContextCompat.getColor(context, color)))
}

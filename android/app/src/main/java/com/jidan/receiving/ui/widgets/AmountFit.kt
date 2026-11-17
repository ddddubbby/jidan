package com.jidan.receiving.ui.widgets

import android.util.TypedValue
import android.widget.TextView

/**
 * 金额字号随位数缩小（原型 .amt：`min(64, 容器内宽 × 1.55 / 字符数)`，字符数是金额的字符数加“元”一个），
 * 长金额也在一行放得下。汇总页的金额卡、完成页共用。
 *
 * @param chars 金额字符数加 1（千分位的逗号也算一个字符）
 * @param insetDp 屏幕宽度里除了金额容器之外的左右留白合计（dp），含页边距、卡片内边距
 */
fun TextView.fitAmount(chars: Int, insetDp: Float) {
    val dm = resources.displayMetrics
    val widthDp = dm.widthPixels / dm.density - insetDp
    val fit = widthDp * 1.55f / chars / resources.configuration.fontScale
    setTextSize(TypedValue.COMPLEX_UNIT_SP, minOf(MAX_SP, fit))
}

private const val MAX_SP = 64f

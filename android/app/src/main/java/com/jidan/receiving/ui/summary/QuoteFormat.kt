package com.jidan.receiving.ui.summary

import com.jidan.receiving.data.QuoteResult
import com.jidan.receiving.ui.widgets.TextGlue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 数值展示：去多余小数（原型 `num`）。均重、基准价、单价（181 元/箱）、尾差系数都走这里，不加千分位。
 * 空值显示“—”。
 */
fun formatNum(n: Double?): String {
    if (n == null) return "—"
    val r = (n * 100).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) {
        r.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }
}

/**
 * 金额（元，一单的合计）展示：数字同 [formatNum]，整数部分每三位加一个逗号（原型 `money`：1810 → 1,810）。
 * 小数、舍入不动，不到 1000 的金额与 [formatNum] 一样。汇总页、完成页的金额都只走这一处。
 */
fun formatMoney(n: Double?): String = groupThousands(formatNum(n))

/** 给“-1234567.5”这样的数字串整数部分加千分位；不是数字（如“—”）原样返回。 */
fun groupThousands(plain: String): String {
    val neg = plain.startsWith("-")
    val body = if (neg) plain.substring(1) else plain
    val dot = body.indexOf('.')
    val int = if (dot < 0) body else body.substring(0, dot)
    val frac = if (dot < 0) "" else body.substring(dot)
    if (int.isEmpty() || !int.all { it in '0'..'9' }) return plain
    val grouped = buildString(int.length + int.length / 3) {
        int.forEachIndexed { i, c ->
            if (i > 0 && (int.length - i) % 3 == 0) append(',')
            append(c)
        }
    }
    return (if (neg) "-" else "") + grouped + frac
}

fun roundModeLabel(mode: String?): String = when (mode?.uppercase(Locale.ROOT)) {
    "HALF" -> "四舍五入"
    "FLOOR" -> "抹零"
    "CEIL" -> "进位"
    else -> "四舍五入"
}

/**
 * 均重行公式（原型 `formulaLine` + `priceLine`）。
 * 数字与单位（“1 元”“181 元/箱”）之间用不换行空格、单位内部加 WORD JOINER，折行时不拆开，
 * 末行不会只剩一个“箱”字（[TextGlue]）。去掉这两种字符后就是原型的文字。
 */
fun formulaLine(q: QuoteResult): String {
    if (q.unitPrice == null || q.boxCount <= 0) return ""
    val raw = q.rawAvgJin?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    val avg = formatNum(q.avgJin)
    val round = roundModeLabel(q.roundMode)
    // 规则带每箱损耗时写在称得均重后面：均重 92.10（每箱 −1 斤）四舍五入为 91斤
    val loss = q.lossJin?.takeIf { abs(it) > 0.000_000_1 }
        ?.let { "（每箱 ${if (it > 0) "+" else "−"}${formatNum(abs(it))}${TextGlue.NBSP}斤）" }.orEmpty()
    return "均重 $raw$loss ${round}为 ${avg}斤 · ${priceLine(q)}"
}

fun priceLine(q: QuoteResult): String {
    val base = formatNum(q.basePrice)
    val unit = TextGlue.withUnit(formatNum(q.unitPrice), "元/箱")
    val avg = formatNum(q.avgJin)
    val diff = q.priceDiff ?: 0.0
    if (abs(diff) < 0.000_000_1) {
        return "基准 $base，均重正好 ${avg}斤 = $unit"
    }
    val coef = TextGlue.withUnit(formatNum(q.coefficient), "元")
    val tail = formatNum(abs(q.tailJin ?: 0.0))
    val sign = if (diff > 0) "+ 多" else "− 少"
    return "基准 $base $sign ${tail}斤 × $coef = $unit"
}

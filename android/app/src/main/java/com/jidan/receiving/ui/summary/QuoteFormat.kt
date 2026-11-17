package com.jidan.receiving.ui.summary

import com.jidan.receiving.data.QuoteResult
import com.jidan.receiving.ui.widgets.TextGlue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 金额展示：去多余小数（原型 `money` / `num`）。 */
fun formatMoney(n: Double?): String {
    if (n == null) return "—"
    val r = (n * 100).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) {
        r.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }
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
    val raw = q.rawAvgKg?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
    val avg = formatMoney(q.avgKg)
    val round = roundModeLabel(q.roundMode)
    return "均重 $raw ${round}为 ${avg}kg · ${priceLine(q)}"
}

fun priceLine(q: QuoteResult): String {
    val base = formatMoney(q.basePrice)
    val unit = TextGlue.withUnit(formatMoney(q.unitPrice), "元/箱")
    val avg = formatMoney(q.avgKg)
    val diff = q.priceDiff ?: 0.0
    if (abs(diff) < 0.000_000_1) {
        return "基准 $base，均重正好 ${avg}kg = $unit"
    }
    val coef = TextGlue.withUnit(formatMoney(q.coefficient), "元")
    val tail = formatMoney(abs(q.tailKg ?: 0.0))
    val sign = if (diff > 0) "+ 多" else "− 少"
    return "基准 $base $sign ${tail}kg × $coef = $unit"
}

package com.jidan.receiving.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import androidx.core.content.ContextCompat
import com.jidan.receiving.R

/**
 * 独占一个位置的“数字 + 单位”：单位缩小、变灰（ink-3）、500 字重，与数字之间一条细缝，不用普通空格。
 * 与 Web 同一套数字排版（开发规格附录 E，F4-3）。
 *
 * - 大数字（称重、金额）单位取 [UNIT_SCALE_BIG]（约 0.62 倍），行内数值取 [UNIT_SCALE_ROW]（约 0.85 倍）。
 * - 只管排版，不动数字：位数、舍入由调用方原样给来；均重取整、箱净重保留小数、金额的写法都不在这里改。
 * - 数字所在的 TextView 用 `Text.Num.*` 样式（Inter，等宽数字，只含数字和少数符号）；单位、汉字、字母用系统字体。
 * - 细缝用发丝空格（大数字）或细空格（行内）两侧各夹一个 WORD JOINER，所以数字和单位不会被换行拆开。
 */
object NumText {
    const val UNIT_SCALE_BIG = 0.62f
    const val UNIT_SCALE_ROW = 0.85f

    /** 单位在 Web 里是系统字体 500 字重（Inter 只管数字），这里同样用系统的 medium。 */
    private val UNIT_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private const val HAIR = '\u200A'   // 约 0.09em：大数字旁
    private const val THIN = '\u2009'   // 约 0.17em：行内

    /** 称重、金额那种大数字，如 `NumText.big(ctx, "46.5", "kg")`。 */
    fun big(context: Context, number: CharSequence, unit: CharSequence): CharSequence =
        format(context, number, unit, UNIT_SCALE_BIG, HAIR)

    /** 行内数值，如 `NumText.row(ctx, "46", "kg")`。 */
    fun row(context: Context, number: CharSequence, unit: CharSequence): CharSequence =
        format(context, number, unit, UNIT_SCALE_ROW, THIN)

    private fun format(
        context: Context,
        number: CharSequence,
        unit: CharSequence,
        unitScale: Float,
        gap: Char,
    ): CharSequence {
        if (unit.isEmpty()) return number
        val out = SpannableStringBuilder(number)
        val start = out.length
        // 细缝本身也在“单位”这段里，缝宽随单位字号走
        out.append(TextGlue.WJ).append(gap).append(TextGlue.WJ).append(TextGlue.noBreak(unit.toString()))
        val end = out.length
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        out.setSpan(RelativeSizeSpan(unitScale), start, end, flags)
        out.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.ink_3)), start, end, flags)
        out.setSpan(TypefaceSpanCompat(UNIT_TYPEFACE), start, end, flags)
        return out
    }

    /** `TypefaceSpan(Typeface)` 要 API 28，这里最低 26。 */
    private class TypefaceSpanCompat(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) { paint.typeface = typeface }
        override fun updateMeasureState(paint: TextPaint) { paint.typeface = typeface }
    }
}

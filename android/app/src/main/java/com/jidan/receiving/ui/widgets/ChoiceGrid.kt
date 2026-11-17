package com.jidan.receiving.ui.widgets

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import kotlin.math.roundToInt

/** 一排选项里的一个（原型 `.seg button`）：名称，名称下一行小字（如“1.5 kg · 上一单”），选中为墨底白字。 */
class ChoiceItem(
    val label: String,
    val badge: String? = null,
    val warn: Boolean = false,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

object ChoiceGrid {
    /** 一行放几个：3 个以内各占一格，多了两列；名字长的少放，免得挤成竖条（原型 segCols）。 */
    fun cols(labels: List<String>): Int {
        val longest = labels.maxOfOrNull { it.codePointCount(0, it.length) } ?: 0
        val cols = if (labels.size <= 3) labels.size else 2
        return when {
            longest > 10 -> 1
            longest > 5 -> minOf(cols, 2)
            else -> cols
        }.coerceAtLeast(1)
    }

    /** 名称加小字：小字是名称的 [badgeRatio] 倍，绿色（要注意的黄色，选中的白色）。 */
    fun text(context: Context, item: ChoiceItem, badgeRatio: Float): CharSequence {
        val text = SpannableStringBuilder(item.label)
        if (!item.badge.isNullOrBlank()) {
            val start = text.length
            text.append('\n').append(item.badge)
            val tone = when {
                item.selected -> R.color.on_accent
                item.warn -> R.color.warn_ink
                else -> R.color.ok_ink
            }
            text.setSpan(RelativeSizeSpan(badgeRatio), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, tone)),
                start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return text
    }

    /**
     * 把 [items] 排成每行 [cols] 列、格间 [gapDp] 的网格放进 [host]。按钮由 [button] 产出（要求宽 0、权重 1），
     * 末行不满的用空格补齐，保证每个按钮同宽。
     */
    fun fill(
        host: LinearLayout,
        items: List<ChoiceItem>,
        cols: Int,
        gapDp: Int = 8,
        badgeRatio: Float = 13f / 17f,
        button: (parent: LinearLayout) -> MaterialButton,
    ) {
        host.removeAllViews()
        val ctx = host.context
        val gap = (gapDp * ctx.resources.displayMetrics.density).roundToInt()
        items.chunked(cols.coerceAtLeast(1)).forEachIndexed { rowIdx, rowItems ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false   // 有的带小字、有的不带，按基线对齐会把按钮错开
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { if (rowIdx > 0) it.topMargin = gap }
            }
            for (col in 0 until cols) {
                val item = rowItems.getOrNull(col)
                val cell: View = if (item != null) {
                    button(line).also {
                        it.text = text(ctx, item, badgeRatio)
                        it.isSelected = item.selected
                        it.setOnClickListener { item.onClick() }
                    }
                } else {
                    Space(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
                }
                (cell.layoutParams as LinearLayout.LayoutParams).marginStart = if (col == 0) 0 else gap
                line.addView(cell)
            }
            host.addView(line)
        }
    }
}

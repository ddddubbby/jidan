package com.jidan.receiving.ui.widgets

import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import com.jidan.receiving.printing.ReprintAction

/**
 * 称重页、汇总页、完成页、流水弹层共用的补打按钮：文字与去向都由 [ReprintAction] 定。
 * [again]：这一箱已经打印过，文字写“再打”而不是“补打 N 张标签”（流水弹层逐箱用）。
 */
fun MaterialButton.bindReprint(
    action: ReprintAction?,
    unprinted: Int,
    onPrint: () -> Unit,
    onFixPrinter: () -> Unit,
    again: Boolean = false,
) {
    isVisible = action != null
    when (action) {
        null -> setOnClickListener(null)
        ReprintAction.Print -> {
            text = if (again) {
                context.getString(R.string.print_again)
            } else {
                context.getString(R.string.print_reprint_n, unprinted)
            }
            setOnClickListener { onPrint() }
        }
        is ReprintAction.FixPrinter -> {
            text = if (again) {
                context.getString(R.string.print_again_blocked, action.reason)
            } else {
                context.getString(R.string.print_failed_n, unprinted, action.reason)
            }
            setOnClickListener { onFixPrinter() }
        }
    }
}

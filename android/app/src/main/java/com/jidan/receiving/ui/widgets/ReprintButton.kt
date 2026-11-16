package com.jidan.receiving.ui.widgets

import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.jidan.receiving.R
import com.jidan.receiving.printing.ReprintAction

/** 称重页、汇总页、完成页、流水弹层共用的补打按钮：文字与去向都由 [ReprintAction] 定。 */
fun MaterialButton.bindReprint(
    action: ReprintAction?,
    unprinted: Int,
    onPrint: () -> Unit,
    onFixPrinter: () -> Unit,
) {
    isVisible = action != null
    when (action) {
        null -> setOnClickListener(null)
        ReprintAction.Print -> {
            text = context.getString(R.string.print_reprint_n, unprinted)
            setOnClickListener { onPrint() }
        }
        is ReprintAction.FixPrinter -> {
            text = context.getString(R.string.print_failed_n, unprinted, action.reason)
            setOnClickListener { onFixPrinter() }
        }
    }
}

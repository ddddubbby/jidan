package com.jidan.receiving

import android.view.ViewGroup
import android.widget.TextView
import com.jidan.receiving.ui.sim.SimConsoleController

object SimConsoleHook {
    fun attach(
        parent: ViewGroup,
        onFill100: (() -> Unit)? = null,
        onWorstName: (() -> Unit)? = null,
        onToggleBigFont: (() -> Unit)? = null,
        bigFontOn: Boolean = false,
    ) {
        SimConsoleController.inflateInto(
            parent,
            onFill100 = onFill100,
            onWorstName = onWorstName,
            onToggleBigFont = onToggleBigFont,
            bigFontOn = bigFontOn,
        )
    }

    /** 最近打印的一张标签预览在模拟台里（开发规格 11.4），不在称重页上。 */
    fun showLabelPreview(parent: ViewGroup, preview: String) {
        parent.findViewById<TextView>(R.id.labelPreview)?.text = preview.ifBlank { "—" }
    }
}

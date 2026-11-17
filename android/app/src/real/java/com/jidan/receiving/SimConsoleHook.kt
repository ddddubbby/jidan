package com.jidan.receiving

import android.view.ViewGroup

/** 实机版无模拟台。 */
object SimConsoleHook {
    fun attach(
        parent: ViewGroup,
        onFill100: (() -> Unit)? = null,
        onWorstName: (() -> Unit)? = null,
        onToggleBigFont: (() -> Unit)? = null,
        bigFontOn: Boolean = false,
    ) {
        // no-op
    }

    /** 实机版无模拟台，也就没有标签预览。 */
    fun showLabelPreview(parent: ViewGroup, preview: String) {
        // no-op
    }
}

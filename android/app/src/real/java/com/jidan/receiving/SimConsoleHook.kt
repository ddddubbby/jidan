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
}

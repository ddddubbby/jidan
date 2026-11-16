package com.jidan.receiving

import android.view.ViewGroup
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
}

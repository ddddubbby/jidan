package com.jidan.receiving

import android.view.ViewGroup
import com.jidan.receiving.ui.sim.SimConsoleController

object SimConsoleHook {
    fun attach(parent: ViewGroup) {
        SimConsoleController.inflateInto(parent)
    }
}

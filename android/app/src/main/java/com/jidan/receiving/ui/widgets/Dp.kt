package com.jidan.receiving.ui.widgets

import androidx.fragment.app.Fragment
import kotlin.math.roundToInt

/** 代码里建的控件边距：写 dp，换成像素（LayoutParams 的边距是像素）。 */
fun Fragment.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

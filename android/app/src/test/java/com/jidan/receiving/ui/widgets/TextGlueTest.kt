package com.jidan.receiving.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class TextGlueTest {
    private val wj = TextGlue.WJ
    private val nb = TextGlue.NBSP

    @Test
    fun unitKeepsNumberAndStaysGlued() {
        assertEquals("181${nb}元/${wj}箱", TextGlue.withUnit("181", "元/箱"))
        assertEquals("1${wj}箱", TextGlue.withUnit("1", "箱", space = false))
        // 英文单位不被切开
        assertEquals("46.5${nb}kg", TextGlue.withUnit("46.5", "kg"))
    }

    @Test
    fun noBreakKeepsTextWhenJoinersRemoved() {
        val s = "45.6kg-4箱"
        assertEquals(s, TextGlue.noBreak(s).replace("$wj", ""))
        assertEquals("45.6kg-${wj}4${wj}箱", TextGlue.noBreak(s))
        assertEquals("箱", TextGlue.noBreak("箱"))
        assertEquals("", TextGlue.noBreak(""))
    }
}

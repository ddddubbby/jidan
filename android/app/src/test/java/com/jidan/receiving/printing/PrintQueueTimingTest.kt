package com.jidan.receiving.printing

import com.jidan.receiving.data.AppOptions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrintQueueTimingTest {
    @Test
    fun defaultsArePerBoxAndEnabled() {
        val o = AppOptions.defaults()
        assertTrue(PrintQueue.timingIsPerBox(o))
        assertTrue(o.get("print.enabled").equals("on", ignoreCase = true))
        assertTrue(o.get("label.content").equals("minimal", ignoreCase = true))
    }

    @Test
    fun afterConfirmTiming() {
        val o = AppOptions.from(mapOf("print.timing" to "after-confirm"))
        assertFalse(PrintQueue.timingIsPerBox(o))
    }
}

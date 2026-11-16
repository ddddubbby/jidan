package com.jidan.receiving.data

import com.jidan.receiving.core.RecordBy
import com.jidan.receiving.core.TareBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppOptionsTest {
    @Test
    fun defaultsMatchSpecChapter4() {
        val o = AppOptions.defaults()
        assertEquals("scale", o.get("scale.tare-by"))
        assertEquals("true", o.get("scale.reading-is-net"))
        assertEquals("sdk", o.get("scale.stable-by"))
        assertEquals("auto", o.get("weigh.record-by"))
        assertEquals("auto", o.get("weigh.merge-detect"))
        assertEquals("on", o.get("weigh.manual-entry"))
        assertEquals("on", o.get("print.enabled"))
        assertEquals("block", o.get("submit.without-price"))
        assertEquals("per-box", o.get("print.timing"))
        assertEquals("minimal", o.get("label.content"))
        assertEquals("lanstar-ble", o.get("device.scale-model"))
        assertEquals("gainscha-gp-m32b", o.get("device.printer-model"))
    }

    @Test
    fun fromMergesKnownKeysOnly() {
        val o = AppOptions.from(
            mapOf(
                "weigh.record-by" to "button",
                "unknown.key" to "x",
                "print.enabled" to null,
            ),
        )
        assertEquals("button", o.get("weigh.record-by"))
        assertEquals("on", o.get("print.enabled"))
        assertTrue("unknown.key" !in o.values)
        assertEquals(RecordBy.BUTTON, o.weighOptions().recordBy)
        assertEquals(TareBy.SCALE, o.weighOptions().tareBy)
    }

    @Test
    fun fromNullUsesDefaults() {
        assertEquals(AppOptions.defaults(), AppOptions.from(null))
    }
}

package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WeighOptionsTest {

    @Test
    fun defaultsAreV7() {
        val d = WeighOptions()
        assertEquals(TareBy.SCALE, d.tareBy)
        assertEquals(true, d.readingIsNet)
        assertEquals(StableBy.SDK, d.stableBy)
        assertEquals(800L, d.stableWindowMs)
        assertEquals(Jin.of(0.1), d.stableTolerance)
        assertEquals(Jin.of(10.0), d.emptyBelow)
        assertEquals(10, d.mergeTolerancePct)
        assertEquals(8, d.mergeMaxK)
        assertEquals(RecordBy.AUTO, d.recordBy)
        assertEquals(MergeDetect.AUTO, d.mergeDetect)
    }

    @Test
    fun parsesEveryKey() {
        val m = mapOf(
            "scale.tare-by" to "app",
            "scale.reading-is-net" to "false",
            "scale.stable-by" to "app",
            "scale.stable-window-ms" to "1200",
            "scale.stable-tolerance-jin" to "0.1",
            "weigh.empty-below-jin" to "3",
            "weigh.merge-tolerance-pct" to "15",
            "weigh.merge-max-k" to "6",
            "weigh.record-by" to "button",
            "weigh.merge-detect" to "off",
        )
        val o = WeighOptions.of { m[it] }
        assertEquals(
            WeighOptions(
                tareBy = TareBy.APP, readingIsNet = false, stableBy = StableBy.APP, stableWindowMs = 1200,
                stableTolerance = Jin.of(0.1), emptyBelow = Jin.of(3.0), mergeTolerancePct = 15, mergeMaxK = 6,
                recordBy = RecordBy.BUTTON, mergeDetect = MergeDetect.OFF,
            ),
            o,
        )
    }

    @Test
    fun missingOrUnknownValuesFallBackToDefaults() {
        assertEquals(WeighOptions(), WeighOptions.of { null })
        val m = mapOf("scale.tare-by" to "wifi", "weigh.merge-max-k" to "abc", "scale.reading-is-net" to "yes")
        assertEquals(WeighOptions(), WeighOptions.of { m[it] })
    }
}

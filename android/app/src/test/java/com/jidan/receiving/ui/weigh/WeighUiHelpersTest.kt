package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Box
import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.RoundAvg
import com.jidan.receiving.core.RoundRule
import kotlin.test.Test
import com.jidan.receiving.data.AppOptions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeighUiHelpersTest {
    @Test
    fun lisiAvgMatchesFixture() {
        // 李四 10 箱合计 460.5kg → 均重 46（raw 46.05）
        val total = Kg.of(460.5)
        assertEquals(Kg.of(46.0), RoundAvg.of(total, 10, RoundRule.HALF))
        assertEquals("46.05", String.format("%.2f", total.toDouble() / 10))
    }

    @Test
    fun recentKeepsAtLeastFour() {
        val boxes = (1..6).map {
            Box(seq = it, group = 1, net = Kg.of(46.0), tare = Kg.of(2.0), tareName = "箱")
        }
        val state = WeighUiState(boxes = boxes)
        assertEquals(6, state.recent.size)
        assertEquals(6, state.recent.first().seq)
    }

    @Test
    fun manualEntryOnShowsManualAddAndEdit() {
        val on = manualEntryOn(AppOptions.from(mapOf("weigh.manual-entry" to "on")))
        val state = WeighUiState(manualEntry = on)
        assertTrue(state.canManualAdd)
        assertTrue(state.canManualEdit)
        assertTrue(manualEntryOn(AppOptions.defaults()))
    }

    @Test
    fun manualEntryOffHidesManualAddAndEdit() {
        val off = manualEntryOn(AppOptions.from(mapOf("weigh.manual-entry" to "off")))
        val state = WeighUiState(manualEntry = off)
        assertFalse(off)
        assertFalse(state.canManualAdd)
        assertFalse(state.canManualEdit)
    }

    @Test
    fun lockedHidesManualAddEvenWhenAllowed() {
        assertFalse(WeighUiState(manualEntry = true, locked = true).canManualAdd)
    }
}

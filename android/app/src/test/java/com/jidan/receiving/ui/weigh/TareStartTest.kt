package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.ScaleReading
import com.jidan.receiving.core.Tare
import com.jidan.receiving.core.WeighOptions
import com.jidan.receiving.core.WeighPhase
import com.jidan.receiving.core.WeighingMachine
import com.jidan.receiving.data.TareDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v7 `APP-04`：新开一单时上一单用的皮重排首位，但不自动选中。 */
class TareStartTest {
    private val hotkeys = listOf(
        TareDto(id = 1, name = "1号箱", tareKg = 1.5),
        TareDto(id = 2, name = "2号箱", tareKg = 2.0),
        TareDto(id = 3, name = "3号箱", tareKg = 2.5),
    )

    @Test
    fun lastTareIdSetStillStartsWithoutTareAndPickerOpenLastFirst() {
        val start = TareStart.of(hotkeys, lastTareId = 3)
        assertNull(start.tare)
        assertTrue(start.showPicker)
        assertEquals(3L, start.lastTareId)
        assertEquals(listOf(3L, 1L, 2L), start.tares.map { it.id })
    }

    @Test
    fun noLastTareKeepsOrderAndPickerOpen() {
        val start = TareStart.of(hotkeys, lastTareId = null)
        assertNull(start.tare)
        assertTrue(start.showPicker)
        assertNull(start.lastTareId)
        assertEquals(listOf(1L, 2L, 3L), start.tares.map { it.id })
    }

    @Test
    fun lastTareNoLongerInHotkeysIsIgnored() {
        val start = TareStart.of(hotkeys, lastTareId = 99)
        assertNull(start.lastTareId)
        assertEquals(listOf(1L, 2L, 3L), start.tares.map { it.id })
    }

    @Test
    fun machineStaysLockedUntilATareIsSelected() {
        val m = WeighingMachine(WeighOptions(), Kg.of(45.0))
        m.onReading(ScaleReading(Kg.ZERO, true), 100)
        assertEquals(WeighPhase.LOCKED, m.phase)
        // 台面放了 48.5kg，没选皮重就不记
        assertNull(m.onReading(ScaleReading(Kg.of(48.5), true), 200))
        assertEquals(WeighPhase.LOCKED, m.phase)
        // 选中一项后才解除锁定
        val picked = TareStart.of(hotkeys, 3).tares.first()
        m.setTare(Tare(Kg.of(picked.tareKg), picked.name))
        m.onReading(ScaleReading(Kg.ZERO, true), 300)
        assertEquals(WeighPhase.EMPTY, m.phase)
    }
}

package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Session
import com.jidan.receiving.core.Tare
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 秤不可用时的最后兜底：连接卡片上有“手工加一箱”，0 箱也能用（APP-11）。 */
class ScaleCardStateTest {
    private fun emptySession() = Session(
        batchNo = "SG20261116-1",
        farmer = Pick(1, "李四"), product = Pick(2, "粉蛋"), spec = Pick(3, "360枚/箱"), warehouse = Pick(4, "一号库"),
        tare = Tare(Jin.of(2.0), "2号箱"),
    )

    @Test
    fun scaleNotConnectedWithZeroBoxesStillOffersManualAdd() {
        val state = WeighUiState(scaleConnected = false, boxes = emptyList())
        assertEquals(0, state.count)
        assertTrue(state.showScaleCard)
        assertTrue(state.showManualAdd)

        // 加一箱 46.5 后流水里有 1 箱：带手录、原读数为空
        val after = emptySession().addManual(Jin.of(46.5))
        val next = state.copy(boxes = after.boxes)
        assertEquals(1, next.count)
        val box = next.boxes.single()
        assertEquals(Jin.of(46.5), box.net)
        assertTrue(box.manual)
        assertNull(box.raw)
    }

    @Test
    fun lostScaleAlsoShowsCardAndConnectedScaleDoesNot() {
        assertTrue(WeighUiState(scaleConnected = false, scaleLost = true).showScaleCard)
        assertFalse(WeighUiState(scaleConnected = true).showScaleCard)
    }

    @Test
    fun manualAddFollowsOptionAndLock() {
        assertFalse(WeighUiState(scaleConnected = false, manualEntry = false).showManualAdd)
        assertFalse(WeighUiState(scaleConnected = false, locked = true).showManualAdd)
    }
}

package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Box
import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.RoundAvg
import com.jidan.receiving.core.RoundRule
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

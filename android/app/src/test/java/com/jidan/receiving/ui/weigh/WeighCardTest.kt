package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Box
import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.WeighPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeighCardTest {
    private fun box(seq: Int, group: Int, printed: Boolean = false) =
        Box(seq = seq, group = group, net = Jin.of(46.0), tare = Jin.of(2.0), tareName = "箱", printed = printed)

    @Test
    fun cardToneFollowsPhase() {
        assertEquals(CardTone.OK, WeighPhase.RECORDED.cardTone())
        assertEquals(CardTone.WARN, WeighPhase.HOLD.cardTone())
        assertEquals(CardTone.WARN, WeighPhase.ASK.cardTone())
        assertEquals(CardTone.IDLE, WeighPhase.READY.cardTone())
        assertEquals(CardTone.IDLE, WeighPhase.LOCKED.cardTone())
    }

    @Test
    fun numberToneFadesWhenNoTareAndPalesWhenIdle() {
        assertEquals(NumberTone.FADED, WeighPhase.LOCKED.numberTone())
        assertEquals(NumberTone.PALE, WeighPhase.UNSTABLE.numberTone())
        assertEquals(NumberTone.PALE, WeighPhase.EMPTY.numberTone())
        assertEquals(NumberTone.INK, WeighPhase.RECORDED.numberTone())
    }

    @Test
    fun recordedLabelNamesTheLastGroup() {
        assertEquals("✓ 第 1 箱已记录", recordedLabel(listOf(box(1, 1)), "已记录"))
        val three = listOf(box(1, 1), box(2, 2), box(3, 2), box(4, 2))
        assertEquals("✓ 第 2–4 箱已记录", recordedLabel(three, "已记录 3 箱"))
        assertEquals("✓ 已记录", recordedLabel(emptyList(), ""))
    }

    @Test
    fun recordedLabelAfterReweighUsesTheReweighedBox() {
        val boxes = listOf(box(1, 1), box(2, 2), box(3, 3))
        assertEquals("✓ 第 2 箱已重称", recordedLabel(boxes, "第 2 箱已重称"))
        assertEquals(2, reweighedSeq("第 2 箱已重称"))
        assertNull(reweighedSeq("已记录"))
    }

    @Test
    fun plainRecordAndReweighPromptHaveNoSeparateLine() {
        assertFalse(showEventLine(""))
        assertFalse(showEventLine("已记录"))
        assertFalse(showEventLine("已记录 3 箱"))
        assertFalse(showEventLine("重称第 3 箱"))
        assertTrue(showEventLine("第 3 箱已重称"))
        assertTrue(showEventLine("已删除"))
    }

    @Test
    fun printMarkOnlyWhenItIsTimeToPrint() {
        assertEquals(PrintMark.NONE, printMark(printEnabled = false, warnable = true, box = box(1, 1)))
        assertEquals(PrintMark.DONE, printMark(true, true, box(1, 1, printed = true)))
        assertEquals(PrintMark.PENDING, printMark(true, true, box(1, 1)))
        assertEquals(PrintMark.NONE, printMark(true, false, box(1, 1)))
    }
}

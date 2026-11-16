package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitBoxesTest {

    private fun scaled(net: Int, k: Int, unit: Int, group: Int) = SplitBoxes.split(
        net = Kg(net), k = k, tare = Kg(unit * k), tareName = "空箱", group = group,
        gross = Kg(net + unit * k), unit = Kg(unit),
    )

    /** 单箱 46 | 3 箱一组 | 单箱 45，组号 1、2、3。 */
    private fun sample(): List<Box> {
        var boxes = emptyList<Box>()
        boxes = SplitBoxes.append(boxes, scaled(4600, 1, 200, 1))
        boxes = SplitBoxes.append(boxes, scaled(13800, 3, 200, 2))
        return SplitBoxes.append(boxes, scaled(4500, 1, 200, 3))
    }

    @Test
    fun threeBoxes138kgGivesThree46() {
        val boxes = SplitBoxes.append(emptyList(), scaled(13800, 3, 200, 1))
        assertEquals(listOf(1, 2, 3), boxes.map { it.seq })
        assertEquals(List(3) { Kg(4600) }, boxes.map { it.net })
        assertEquals(List(3) { Kg(200) }, boxes.map { it.tare })
        assertEquals(setOf(1), boxes.map { it.group }.toSet())
    }

    @Test
    fun remainderGoesToTheLastBox() {
        // 138.01 ÷ 3：46.00、46.00、46.01
        val boxes = SplitBoxes.split(Kg(13801), 3, Kg(600), "空箱", 1)
        assertEquals(listOf(Kg(4600), Kg(4600), Kg(4601)), boxes.map { it.net })
        assertEquals(Kg(13801), boxes.map { it.net }.sum())
    }

    @Test
    fun lastBoxCanBeSmallerAndTotalAlwaysAddsUp() {
        // 2.00 ÷ 3：每箱四舍五入 0.67，最后一箱 0.66
        val boxes = SplitBoxes.split(Kg(200), 3, Kg.ZERO, "", 1)
        assertEquals(listOf(Kg(67), Kg(67), Kg(66)), boxes.map { it.net })
    }

    @Test
    fun halfCentRoundsUp() {
        // 0.05 ÷ 2：每箱 0.025 → 0.03，最后一箱 0.02
        assertEquals(listOf(Kg(3), Kg(2)), SplitBoxes.split(Kg(5), 2, Kg.ZERO, "", 1).map { it.net })
    }

    @Test
    fun tareIsSplitTheSameWay() {
        val boxes = SplitBoxes.split(Kg(10000), 3, Kg(601), "空箱", 1)
        assertEquals(listOf(Kg(200), Kg(200), Kg(201)), boxes.map { it.tare })
    }

    @Test
    fun singleBoxKeepsEverything() {
        val b = SplitBoxes.split(Kg(4600), 1, Kg(200), "空箱", 7).single()
        assertEquals(Kg(4600), b.net)
        assertEquals(Kg(200), b.tare)
        assertEquals(7, b.group)
    }

    @Test
    fun consecutiveWeighingsKeepCountingFromOne() {
        val boxes = sample()
        assertEquals((1..5).toList(), boxes.map { it.seq })
        assertEquals(listOf(1, 2, 2, 2, 3), boxes.map { it.group })
    }

    @Test
    fun deleteWholeGroupRenumbers() {
        // 删第 3 箱（组 2 的中间一箱），整组 2、3、4 都没了，后面的顺延
        val after = SplitBoxes.deleteGroup(sample(), 3)
        assertEquals(listOf(1, 2), after.map { it.seq })
        assertEquals(listOf(1, 3), after.map { it.group })
        assertEquals(listOf(Kg(4600), Kg(4500)), after.map { it.net })
    }

    @Test
    fun regroupToFewerBoxes() {
        // 3 箱 144kg（每箱皮 2）改成 2 箱：净 140，每箱 70，皮重每箱 2
        val after = SplitBoxes.regroup(sample(), 2, 2)
        assertEquals((1..4).toList(), after.map { it.seq })
        assertEquals(listOf(Kg(4600), Kg(7000), Kg(7000), Kg(4500)), after.map { it.net })
        assertEquals(List(4) { Kg(200) }, after.map { it.tare })
        assertEquals(listOf(1, 2, 2, 3), after.map { it.group })
        assertEquals(Kg(14400), after[1].gross)
    }

    @Test
    fun regroupToMoreBoxesWithRemainder() {
        val boxes = SplitBoxes.append(emptyList(), scaled(13800, 3, 200, 1))
        // 4 箱：净 144 − 8 = 136，每箱 34
        assertEquals(List(4) { Kg(3400) }, SplitBoxes.regroup(boxes, 1, 4).map { it.net })
        // 7 箱：净 144 − 14 = 130，130 ÷ 7 = 18.571…，每箱 18.57，最后一箱 18.58
        val seven = SplitBoxes.regroup(boxes, 1, 7)
        assertEquals(List(6) { Kg(1857) } + Kg(1858), seven.map { it.net })
        assertEquals(Kg(13000), seven.map { it.net }.sum())
        assertEquals(Kg(1400), seven.map { it.tare }.sum())
    }

    @Test
    fun regroupToSameCountChangesNothing() {
        val boxes = SplitBoxes.append(emptyList(), scaled(13800, 3, 200, 1)).map { it.copy(printed = true) }
        assertEquals(boxes, SplitBoxes.regroup(boxes, 2, 3))
    }

    @Test
    fun regroupResetsManualEditsAndPrintMarks() {
        val boxes = SplitBoxes.append(emptyList(), scaled(13800, 3, 200, 1))
            .map { it.copy(printed = true, manual = true, net = Kg(1)) }
        val after = SplitBoxes.regroup(boxes, 1, 2)
        assertEquals(listOf(false, false), after.map { it.printed })
        assertEquals(listOf(false, false), after.map { it.manual })
        assertEquals(Kg(14000), after.map { it.net }.sum())
    }

    @Test
    fun manualAddedBoxIsOneBoxWithoutScaleReading() {
        val b = SplitBoxes.split(Kg(4500), 1, Kg(200), "空箱", 4, manual = true).single()
        assertEquals(true, b.manual)
        assertEquals(null, b.gross)
    }

    // ---- 序号变了，旧标签作废（打印标记清掉）----

    private fun allPrinted() = sample().map { it.copy(printed = true) }

    @Test
    fun deleteGroupClearsPrintedOnlyOnBoxesWhoseSeqShifted() {
        // 第 1 箱 | 第 2~4 箱一组 | 第 5 箱；删中间组后第 5 箱变成第 2 箱
        val after = SplitBoxes.deleteGroup(allPrinted(), 3)
        assertEquals(listOf(1, 2), after.map { it.seq })
        assertEquals(listOf(true, false), after.map { it.printed })
    }

    @Test
    fun deleteLastGroupKeepsEveryPrintMark() {
        val after = SplitBoxes.deleteGroup(allPrinted(), 5)
        assertEquals(listOf(1, 2, 3, 4), after.map { it.seq })
        assertEquals(List(4) { true }, after.map { it.printed })
    }

    @Test
    fun deleteFirstBoxClearsEveryFollowingMark() {
        val after = SplitBoxes.deleteGroup(allPrinted(), 1)
        assertEquals(listOf(1, 2, 3, 4), after.map { it.seq })
        assertEquals(List(4) { false }, after.map { it.printed })
    }

    @Test
    fun regroupClearsPrintedOnLaterBoxesOnlyWhenTheCountChanges() {
        // 中间组 3 箱改成 2 箱：第 5 箱前移成第 4 箱；前面的第 1 箱不动
        val shorter = SplitBoxes.regroup(allPrinted(), 2, 2)
        assertEquals(listOf(1, 2, 3, 4), shorter.map { it.seq })
        assertEquals(listOf(true, false, false, false), shorter.map { it.printed })
        // 改成 4 箱：后面那箱后移成第 6 箱
        val longer = SplitBoxes.regroup(allPrinted(), 2, 4)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), longer.map { it.seq })
        assertEquals(listOf(true, false, false, false, false, false), longer.map { it.printed })
    }

    @Test
    fun appendKeepsPrintedMarksOfExistingBoxes() {
        val after = SplitBoxes.append(allPrinted(), scaled(4700, 1, 200, 4))
        assertEquals(listOf(true, true, true, true, true, false), after.map { it.printed })
    }
}

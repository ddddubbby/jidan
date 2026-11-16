package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SessionTest {

    private val tare = Tare(Kg(200), "2号箱")

    private fun empty() = Session(
        batchNo = "SG20261116-1",
        farmer = Pick(11, "李四"), product = Pick(21, "粉蛋"), spec = Pick(31, "360枚/箱"), warehouse = Pick(41, "一号库"),
        tare = tare,
    )

    /** 秤上放 [k] 箱、净重合计 [net]（0.01kg）时状态机记下的结果。 */
    private fun weighing(net: Int, k: Int = 1) =
        Weighing(k = k, platform = Kg(net + 200 * k), tareUnit = Kg(200), tareTotal = Kg(200 * k), net = Kg(net))

    private fun Session.weigh(net: Int, k: Int = 1) = apply(WeighOutcome.Recorded(weighing(net, k)))

    /** v7 6.2 的 10 箱：46.5, 45.8, 46, 46, 46, 45.6 x4, 47.8。 */
    private fun baseline(): Session =
        listOf(4650, 4580, 4600, 4600, 4600, 4560, 4560, 4560, 4560, 4780).fold(empty()) { s, n -> s.weigh(n) }

    // ---- 加进流水 ----

    @Test
    fun recordedWeighingAddsKBoxesAsOneGroup() {
        val s = empty().weigh(4600).weigh(13800, 3)
        assertEquals(listOf(1, 2, 3, 4), s.boxes.map { it.seq })
        assertEquals(listOf(1, 2, 2, 2), s.boxes.map { it.group })
        assertEquals(List(3) { Kg(4600) }, s.boxes.drop(1).map { it.net })
        assertEquals(2, s.groupSeq)
        assertTrue(s.boxes.none { it.manual })
        assertTrue(s.boxes.all { it.raw == null && !it.printed && it.tareName == "2号箱" })
    }

    @Test
    fun replacedWeighingKeepsSeqAndGroupAndClearsManualMarks() {
        val s = empty().weigh(4600).weigh(4500)
            .editManual(2, Kg(5000))
            .markPrinted(2)
            .apply(WeighOutcome.Replaced(2, weighing(4700)))
        val b = s.box(2)
        assertEquals(Kg(4700), b.net)
        assertEquals(2, b.group)
        assertFalse(b.manual)
        assertNull(b.raw)
        assertFalse(b.printed)
        assertEquals(2, s.count)
    }

    @Test
    fun baselineTenBoxesAddUp() {
        val s = baseline()
        assertEquals(10, s.count)
        assertEquals(Kg(46050), s.totalNet)
        assertEquals(Kg(4600), RoundAvg.of(s.totalNet, s.count))
    }

    // ---- 手工录入（APP-11） ----

    @Test
    fun manualEditMarksManualAndKeepsScaleReading() {
        val s = baseline().editManual(10, Kg(5280))
        val b = s.box(10)
        assertEquals(Kg(5280), b.net)
        assertTrue(b.manual)
        assertEquals(Kg(4780), b.raw)
        // v7 6.6：累计 465.5kg，均重 47kg
        assertEquals(Kg(46550), s.totalNet)
        assertEquals(Kg(4700), RoundAvg.of(s.totalNet, s.count))
        // 其他箱不受影响
        assertTrue(s.boxes.take(9).none { it.manual || it.raw != null })
    }

    @Test
    fun manualEditClearsPrintedMark() {
        val s = baseline().markPrinted(3).editManual(3, Kg(4000))
        assertFalse(s.box(3).printed)
    }

    @Test
    fun manuallyAddedBoxHasNoScaleReading() {
        val s = baseline().addManual(Kg(4400))
        val b = s.box(11)
        assertTrue(b.manual)
        assertNull(b.raw)
        assertNull(b.gross)
        assertEquals(Kg(4400), b.net)
        assertEquals(Kg(200), b.tare)
        assertEquals("2号箱", b.tareName)
        assertEquals(11, b.group)
    }

    @Test
    fun manuallyAddedBoxWithoutTareHasZeroTare() {
        val b = empty().withTare(null).addManual(Kg(4400)).box(1)
        assertEquals(Kg.ZERO, b.tare)
        assertEquals("", b.tareName)
    }

    @Test
    fun editingAManualBoxAgainKeepsTheOriginalScaleReading() {
        val s = baseline().editManual(10, Kg(5280)).editManual(10, Kg(5000)).editManual(10, Kg(4900))
        val b = s.box(10)
        assertEquals(Kg(4900), b.net)
        assertTrue(b.manual)
        assertEquals(Kg(4780), b.raw)
    }

    @Test
    fun editingAManuallyAddedBoxKeepsRawEmpty() {
        val s = baseline().addManual(Kg(4400)).editManual(11, Kg(4500)).editManual(11, Kg(4600))
        assertNull(s.box(11).raw)
        assertEquals(Kg(4600), s.box(11).net)
    }

    @Test
    fun editingBackToTheOriginalValueStaysManual() {
        val s = baseline().editManual(10, Kg(5280)).editManual(10, Kg(4780))
        assertTrue(s.box(10).manual)
        assertEquals(Kg(4780), s.box(10).raw)
    }

    @Test
    fun reweighingAManualBoxTakesTheScaleBack() {
        val s = baseline().editManual(10, Kg(5280)).apply(WeighOutcome.Replaced(10, weighing(4800)))
        assertFalse(s.box(10).manual)
        assertNull(s.box(10).raw)
        // 之后再手录，原读数是这次重称的读数
        assertEquals(Kg(4800), s.editManual(10, Kg(5000)).box(10).raw)
    }

    @Test
    fun manualNetMustBePositive() {
        assertFailsWith<IllegalArgumentException> { baseline().editManual(1, Kg.ZERO) }
        assertFailsWith<IllegalArgumentException> { baseline().addManual(Kg(-5)) }
    }

    @Test
    fun noUpperLimitOnManualNet() {
        assertEquals(Kg(99999), baseline().editManual(1, Kg(99999)).box(1).net)
    }

    // ---- 删除与改箱数 ----

    @Test
    fun deleteRemovesTheWholeGroupAndRenumbers() {
        val s = empty().weigh(4600).weigh(13800, 3).weigh(4500).delete(3)
        assertEquals(listOf(1, 2), s.boxes.map { it.seq })
        assertEquals(listOf(Kg(4600), Kg(4500)), s.boxes.map { it.net })
    }

    @Test
    fun newGroupNumbersKeepGoingAfterADelete() {
        val s = empty().weigh(4600).weigh(4500).delete(2).weigh(4400)
        assertEquals(listOf(1, 3), s.boxes.map { it.group })
    }

    @Test
    fun regroupRespreadsTheWeighing() {
        val s = empty().weigh(4600).weigh(13800, 3).regroup(2, 2)
        assertEquals(listOf(1, 2, 3), s.boxes.map { it.seq })
        // 总重 138+6 = 144，按 2 箱：(144 - 4) / 2 = 70
        assertEquals(listOf(Kg(4600), Kg(7000), Kg(7000)), s.boxes.map { it.net })
    }

    @Test
    fun regroupIsNotAllowedAfterManualEditOrSingleReweigh() {
        val grouped = empty().weigh(13800, 3)
        assertTrue(grouped.canRegroup(1))
        assertFalse(grouped.editManual(2, Kg(5000)).canRegroup(1))
        assertFalse(grouped.apply(WeighOutcome.Replaced(2, weighing(5000))).canRegroup(1))
        assertFalse(empty().addManual(Kg(4400)).canRegroup(1))
        assertFailsWith<IllegalArgumentException> { empty().addManual(Kg(4400)).regroup(1, 2) }
    }

    // ---- 打印标记 ----

    @Test
    fun printedMarksAndUnprinted() {
        val s = empty().weigh(4600).weigh(4500).markPrinted(1)
        assertEquals(listOf(2), s.unprinted.map { it.seq })
        assertEquals(2, s.markPrinted(1, false).unprinted.size)
    }

    // ---- 序列化 ----

    @Test
    fun jsonRoundTripEqualsOriginal() {
        val s = baseline().weigh(13800, 3).addManual(Kg(4400))
            .editManual(10, Kg(5280)).editManual(2, Kg(1)).markPrinted(1).markPrinted(10).markPrinted(14)
        val back = Session.fromJson(s.toJson())
        assertEquals(s, back)
        // 读回后各字段也要对：手录、原读数、打印标记
        assertEquals(Kg(4780), back.box(10).raw)
        assertNull(back.box(14).raw)
        assertTrue(back.box(14).manual)
        assertTrue(back.box(1).printed)
        assertEquals(s.groupSeq, back.groupSeq)
    }

    @Test
    fun jsonRoundTripOfEmptyAndNoTare() {
        val s = empty().withTare(null)
        assertEquals(s, Session.fromJson(s.toJson()))
        assertEquals(empty(), Session.fromJson(empty().toJson()))
    }

    @Test
    fun jsonRoundTripOfAwkwardText() {
        val tricky = "农户\"李\\四\"\n\t换行 \u0001 😀 /"
        val s = Session(
            batchNo = "SG-1", farmer = Pick(Long.MAX_VALUE, tricky), product = Pick(1, "粉蛋"), spec = Pick(2, "a"),
            warehouse = Pick(3, "库"), tare = Tare(Kg(200), tricky),
        ).weigh(4600)
        assertEquals(s, Session.fromJson(s.toJson()))
    }

    @Test
    fun draftIsValidJsonWithExpectedShape() {
        val text = baseline().addManual(Kg(4400)).editManual(10, Kg(5280)).toJson()
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(
            setOf("batchNo", "farmer", "product", "spec", "warehouse", "tare", "groupSeq", "boxes"),
            root.keys,
        )
        assertEquals(11, (root["boxes"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun readingBackKeepsFurtherEditingWorking() {
        val back = Session.fromJson(baseline().editManual(10, Kg(5280)).toJson())
        // 进程被回收重开后继续改，原读数仍是秤最初的读数
        assertEquals(Kg(4780), back.editManual(10, Kg(5100)).box(10).raw)
        assertEquals(11, back.weigh(4600).box(11).group)
    }

    @Test
    fun brokenDraftIsRejected() {
        assertFailsWith<IllegalArgumentException> { Session.fromJson("") }
        assertFailsWith<IllegalArgumentException> { Session.fromJson("{") }
        assertFailsWith<IllegalArgumentException> { Session.fromJson("[]") }
        assertFailsWith<IllegalArgumentException> { Session.fromJson("{\"batchNo\":\"x\"}") }
        assertFailsWith<IllegalArgumentException> { Session.fromJson(empty().toJson() + "x") }
    }
}

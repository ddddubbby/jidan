package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/** 三种秤读数的换算组合（`scale.tare-by` × `scale.reading-is-net`，`tare-by = app` 时后者无意义）。 */
private val combos = listOf(
    "app" to WeighOptions(tareBy = TareBy.APP, readingIsNet = false),
    "app-net" to WeighOptions(tareBy = TareBy.APP, readingIsNet = true),
    "scale-net" to WeighOptions(tareBy = TareBy.SCALE, readingIsNet = true),
    "scale-gross" to WeighOptions(tareBy = TareBy.SCALE, readingIsNet = false),
)

/**
 * 把状态机接上一份流水：台面总重按选项换成秤的原始读数喂进去，记下的结果用 `SplitBoxes` 加进列表。
 * 这一段就是 P3-4 `Session` 要做的事，这里只为让测试看到逐箱的流水。
 */
private class Rig(
    val options: WeighOptions,
    tare: Tare? = Tare(Jin.of(2.0), "2号箱"),
    std: Double = 45.0,
    detect: (Jin, Jin, Jin, Int, Int) -> DetectResult = DetectK::detect,
) {
    val machine = WeighingMachine(options, Jin.of(std), detect)
    var boxes = emptyList<Box>()
    var group = 0
    var tareName = tare?.name ?: ""

    /** 每次记录：触发它的事件下标与新增（或被替换）的流水。 */
    val log = mutableListOf<Pair<Int, List<Box>>>()
    private var n = 0
    private var tareUnit = tare?.unit ?: Jin.ZERO

    init {
        machine.setTare(tare)
    }

    fun setTare(t: Tare?) {
        machine.setTare(t)
        tareUnit = t?.unit ?: Jin.ZERO
        tareName = t?.name ?: ""
    }

    /** 台面总重 → 秤的原始读数（空秤也一样换算，设皮后空秤显示 −皮重）。 */
    fun scaleReading(platformJin: Double): Jin {
        val p = Jin.of(platformJin)
        return if (options.tareBy == TareBy.SCALE && options.readingIsNet) p - tareUnit else p
    }

    fun feed(platformJin: Double, stable: Boolean, nowMs: Long = n * 100L): WeighOutcome? {
        val out = machine.onReading(ScaleReading(scaleReading(platformJin), stable), nowMs)
        apply(out)
        n++
        return out
    }

    fun tick(nowMs: Long): WeighOutcome? = machine.onTick(nowMs).also { apply(it) }

    fun press() = machine.press().also { apply(it) }
    fun choose(k: Int) = machine.choose(k).also { apply(it) }

    private fun apply(out: WeighOutcome?) {
        when (out) {
            null -> return
            is WeighOutcome.Recorded -> {
                val added = out.weighing.toBoxes(++group, tareName)
                boxes = SplitBoxes.append(boxes, added)
                log += (n to added)
            }
            is WeighOutcome.Replaced -> {
                boxes = boxes.map { if (it.seq == out.seq) out.weighing.replacing(it, tareName) else it }
                log += (n to boxes.filter { it.seq == out.seq })
            }
        }
    }

    /** 放上箱子：先不稳定、再稳定。 */
    fun place(platformJin: Double): WeighOutcome? {
        feed(platformJin + 0.3, false)
        return feed(platformJin, true)
    }

    fun clear() = feed(0.0, true)
}

class WeighingMachineTest {

    private fun optionsOf(setup: Map<String, kotlinx.serialization.json.JsonElement>) =
        WeighOptions.of { (setup[it] as? JsonPrimitive)?.content }

    // ---- fixtures/weighing.json，每个算例在每种读数组合下都要得到同样的流水 ----

    @Test
    fun fixtureCasesUnderEveryReadingCombo() {
        val cases = loadFixture("weighing.json")["cases"]!!.arr().map { it.obj() }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val id = c["id"]!!.str()
            val setup = c["setup"]!!.obj()
            val base = optionsOf(setup["options"]!!.obj())
            val tare = Tare(setup["tareJin"]!!.jin(), "测试皮")
            val events = c["events"]!!.arr().map { it.obj() }
            val expected = c["expected"]!!.obj()["records"]!!.arr().map { it.obj() }
            for ((name, combo) in combos) {
                val o = base.copy(tareBy = combo.tareBy, readingIsNet = combo.readingIsNet)
                val rig = Rig(o, tare, std = setup["standardNetJin"]!!.jin().toDouble())
                val at = mutableListOf<Int>()
                events.forEachIndexed { i, e ->
                    val before = rig.boxes.size
                    rig.feed(e["grossJin"]!!.jin().toDouble(), e["stable"]!!.toString().toBoolean())
                    repeat(rig.boxes.size - before) { at += i }
                }
                val tag = "$id/$name"
                assertEquals(expected.size, rig.boxes.size, "$tag 记录条数")
                expected.forEachIndexed { i, e ->
                    val b = rig.boxes[i]
                    assertEquals(e["seq"]!!.int(), b.seq, "$tag #$i seq")
                    assertEquals(e["group"]!!.int(), b.group, "$tag #$i group")
                    assertEquals(e["netJin"]!!.jin(), b.net, "$tag #$i 净重")
                    assertEquals(e["tareJin"]!!.jin(), b.tare, "$tag #$i 皮重")
                    assertEquals(e["grossJin"]!!.jin(), b.net + b.tare, "$tag #$i 毛重")
                    assertEquals(e["atEvent"]!!.int(), at[i], "$tag #$i 触发事件")
                }
                // k 是该组的箱数
                expected.forEachIndexed { i, e ->
                    assertEquals(e["k"]!!.int(), rig.boxes.count { it.group == rig.boxes[i].group }, "$tag #$i k")
                }
            }
        }
    }

    // ---- 未设皮重不记 ----

    @Test
    fun noTareNeverRecordsThenRecordsOnceTareIsSet() {
        for ((name, o) in combos) {
            val rig = Rig(o, tare = null)
            rig.clear()
            assertNull(rig.place(48.0), name)
            assertEquals(WeighPhase.LOCKED, rig.machine.phase, name)
            assertTrue(rig.boxes.isEmpty(), name)

            // 设皮重后，下一个稳定读数照常记（箱子一直在秤上，之前空秤时已经“上膛”）
            rig.setTare(Tare(Jin.of(2.0), "2号箱"))
            assertEquals(WeighPhase.UNSTABLE, rig.machine.phase, "$name：换皮重后旧读数作废，等下一个读数")
            val out = rig.feed(48.0, true)
            assertTrue(out is WeighOutcome.Recorded, name)
            assertEquals(Jin.of(46.0), rig.boxes.single().net, name)
        }
    }

    @Test
    fun boxAlreadyOnScaleAtStartIsNotRecordedUntilRemoved() {
        for ((name, o) in combos) {
            val rig = Rig(o)
            assertNull(rig.feed(48.0, true), name)
            assertEquals(WeighPhase.HOLD, rig.machine.phase, name)
            assertNull(rig.feed(48.0, true), name)
            rig.clear()
            assertEquals(WeighPhase.EMPTY, rig.machine.phase, name)
            assertNotNull(rig.place(47.8), name)
            assertEquals(Jin.of(45.8), rig.boxes.single().net, name)
        }
    }

    @Test
    fun resetScaleRequiresTakingBoxOffAgain() {
        val rig = Rig(WeighOptions())
        rig.clear()
        rig.place(48.0)
        rig.machine.resetScale()
        assertNull(rig.feed(48.0, true))
        assertEquals(WeighPhase.HOLD, rig.machine.phase)
        assertEquals(1, rig.boxes.size)
    }

    // ---- 称重框状态 ----

    @Test
    fun phasesFollowTheScale() {
        val rig = Rig(WeighOptions())
        assertEquals(WeighPhase.UNSTABLE, rig.machine.phase)   // 还没有读数
        rig.feed(0.0, true)
        assertEquals(WeighPhase.EMPTY, rig.machine.phase)
        rig.feed(48.5, false)
        assertEquals(WeighPhase.UNSTABLE, rig.machine.phase)
        rig.feed(48.5, true)
        assertEquals(WeighPhase.RECORDED, rig.machine.phase)
        assertEquals(Jin.of(46.5), rig.machine.displayNet)
        rig.feed(48.5, false)
        assertEquals(WeighPhase.UNSTABLE, rig.machine.phase)
    }

    // ---- 识别不准：暂停，选定后记录 ----

    @Test
    fun ambiguousPausesUntilChosen() {
        for ((name, o) in combos) {
            val rig = Rig(o)
            rig.clear()
            // fixtures/detect-k.json 的识别不准算例：256斤，5 箱与 6 箱都在容差内
            assertNull(rig.place(256.0), name)
            assertEquals(WeighPhase.ASK, rig.machine.phase, name)
            assertEquals(5 to 6, rig.machine.candidates, name)
            assertTrue(rig.boxes.isEmpty(), name)
            assertNull(rig.feed(256.0, true), "$name：不点选就一直不记")

            assertNull(rig.choose(7), "$name：只能选候选里的箱数")
            val out = rig.choose(6)
            assertTrue(out is WeighOutcome.Recorded, name)
            assertEquals(6, rig.boxes.size, name)
            assertEquals(Jin.of(244.0), rig.boxes.fold(Jin.ZERO) { s, b -> s + b.net }, name)   // 256 − 6×2
            assertTrue(rig.boxes.all { it.group == 1 }, name)
            assertEquals(WeighPhase.RECORDED, rig.machine.phase, name)
            assertNull(rig.choose(6), "$name：记完后点选无效")

            // 拿走后恢复正常
            rig.clear()
            assertNotNull(rig.place(48.0), name)
            assertEquals(7, rig.boxes.size, name)
        }
    }

    @Test
    fun ambiguousChoosingTheSmallerCandidate() {
        val rig = Rig(WeighOptions())
        rig.clear()
        rig.place(256.0)
        rig.choose(5)
        assertEquals(5, rig.boxes.size)
        assertEquals(Jin.of(246.0), rig.boxes.fold(Jin.ZERO) { s, b -> s + b.net })
    }

    @Test
    fun ambiguousChoiceWhileButtonModeRecordsDirectly() {
        val rig = Rig(WeighOptions(recordBy = RecordBy.BUTTON))
        rig.clear()
        rig.place(256.0)
        assertFalse(rig.machine.canPress, "识别不准时记录按钮不可按")
        assertNotNull(rig.choose(5))
    }

    // ---- 重称：替换指定箱 ----

    @Test
    fun reweighReplacesTheGivenBox() {
        for ((name, o) in combos) {
            val rig = Rig(o)
            rig.clear()
            listOf(48.5, 47.8, 48.0).forEach { rig.place(it); rig.clear() }
            assertEquals(3, rig.boxes.size, name)
            // 第 2 箱手录过，重称后要回到秤的读数
            rig.boxes = rig.boxes.map {
                if (it.seq == 2) it.copy(net = Jin.of(46.0), manual = true, raw = it.net, printed = true) else it
            }

            rig.machine.startReweigh(2)
            assertEquals(2, rig.machine.reweighSeq, name)
            val out = rig.place(49.0)
            assertEquals(WeighOutcome.Replaced::class, out!!::class, name)
            assertEquals(3, rig.boxes.size, name)
            val b = rig.boxes[1]
            assertEquals(2, b.seq, name)
            assertEquals(2, b.group, name)
            assertEquals(Jin.of(47.0), b.net, name)
            assertFalse(b.manual, name)
            assertNull(b.raw, name)
            assertFalse(b.printed, name)
            assertEquals(listOf(46.5, 47.0, 46.0).map { Jin.of(it) }, rig.boxes.map { it.net }, name)
            assertNull(rig.machine.reweighSeq, name)

            // 重称只管一次，之后又是新增
            rig.clear()
            rig.place(48.0)
            assertEquals(4, rig.boxes.size, name)
        }
    }

    @Test
    fun reweighIsAlwaysOneBox() {
        val rig = Rig(WeighOptions())
        rig.clear()
        rig.place(48.0)
        rig.clear()
        rig.machine.startReweigh(1)
        rig.place(144.0)   // 平时会识别成 3 箱
        assertEquals(1, rig.boxes.size)
        assertEquals(Jin.of(142.0), rig.boxes[0].net)
    }

    @Test
    fun cancelReweighGoesBackToAppending() {
        val rig = Rig(WeighOptions())
        rig.clear()
        rig.place(48.0)
        rig.clear()
        rig.machine.startReweigh(1)
        rig.machine.cancelReweigh()
        rig.place(47.0)
        assertEquals(2, rig.boxes.size)
    }

    // ---- scale.stable-by = app：不看秤的稳定标志 ----

    @Test
    fun appStabilityRecordsWithoutTheScaleFlag() {
        for ((name, combo) in combos) {
            val rig = Rig(combo.copy(stableBy = StableBy.APP))
            var t = 0L
            // 秤的稳定标志一直是 false；空秤先满一个窗口才算稳定、才“上膛”
            while (t <= 900) { assertNull(rig.feed(0.0, false, t), name); t += 100 }
            assertEquals(WeighPhase.EMPTY, rig.machine.phase, name)

            var recordedAt: Long? = null
            while (t <= 3000 && recordedAt == null) {
                // 放上去后读数在 ±0.02斤 内小幅跳动，不算动
                if (rig.feed(48.5 + (t / 100 % 2) * 0.02, false, t) != null) recordedAt = t
                t += 100
            }
            // 第一个读数在 1000ms，窗口 800ms
            assertEquals(1800L, recordedAt, name)
            assertEquals(Jin.of(46.5), rig.boxes.single().net.let { Jin((it.cents / 10) * 10) }, name)
            assertNull(rig.feed(48.5, false, t), "$name：不拿走不记第二次")
        }
    }

    @Test
    fun appStabilityRestartsWindowWhenReadingMoves() {
        val rig = Rig(WeighOptions(stableBy = StableBy.APP, tareBy = TareBy.APP))
        var t = 0L
        while (t <= 900) { rig.feed(0.0, false, t); t += 100 }
        // 1000..1300 在 48.5，1400 起变成 48.9（超出 0.05 容差），窗口重新数
        val fed = mutableListOf<Long>()
        while (t <= 3000 && rig.boxes.isEmpty()) {
            rig.feed(if (t < 1400) 48.5 else 48.9, false, t)
            fed += t
            t += 100
        }
        assertEquals(1, rig.boxes.size)
        assertEquals(2200L, fed.last())
        assertEquals(Jin.of(46.9), rig.boxes.single().net)
    }

    @Test
    fun appStabilityNeedsNoNewReadingToCrossTheWindow() {
        val rig = Rig(WeighOptions(stableBy = StableBy.APP))
        rig.feed(0.0, false, 0)
        assertNull(rig.tick(700))
        assertNull(rig.tick(800))                // 空秤稳定，上膛
        assertEquals(WeighPhase.EMPTY, rig.machine.phase)
        rig.feed(48.0, false, 1000)              // 读数不变秤就不再回调
        assertNull(rig.tick(1700))
        assertTrue(rig.tick(1800) is WeighOutcome.Recorded)
        assertEquals(1, rig.boxes.size)
    }

    @Test
    fun sdkModeIgnoresTick() {
        val rig = Rig(WeighOptions())
        rig.feed(0.0, true)
        rig.feed(48.0, false)
        assertNull(rig.tick(10_000))
        assertTrue(rig.boxes.isEmpty())
    }

    // ---- weigh.record-by = button：不自动记 ----

    @Test
    fun buttonModeRecordsOnlyOnPress() {
        for ((name, combo) in combos) {
            val rig = Rig(combo.copy(recordBy = RecordBy.BUTTON))
            rig.clear()
            assertNull(rig.press(), "$name：空秤按了不记")
            assertNull(rig.place(48.5), "$name：稳定了也不自动记")
            assertTrue(rig.boxes.isEmpty(), name)
            assertEquals(WeighPhase.READY, rig.machine.phase, name)
            assertTrue(rig.machine.canPress, name)

            assertTrue(rig.press() is WeighOutcome.Recorded, name)
            assertEquals(Jin.of(46.5), rig.boxes.single().net, name)
            assertNull(rig.press(), "$name：按一次只记一次")
            assertFalse(rig.machine.canPress, name)

            rig.clear()
            rig.feed(47.8, false)
            assertNull(rig.press(), "$name：读数未稳定时按了不记")
            rig.feed(47.8, true)
            assertNotNull(rig.press(), name)
            assertEquals(2, rig.boxes.size, name)
        }
    }

    @Test
    fun buttonModeStillNeedsATare() {
        val rig = Rig(WeighOptions(recordBy = RecordBy.BUTTON), tare = null)
        rig.clear()
        rig.place(48.0)
        assertFalse(rig.machine.canPress)
        assertNull(rig.press())
    }

    @Test
    fun autoModeNeverExposesThePressButton() {
        val rig = Rig(WeighOptions())
        rig.clear()
        rig.place(48.0)
        assertFalse(rig.machine.canPress)
        assertNull(rig.press())
    }

    // ---- weigh.merge-detect = off：恒为 1 箱 ----

    @Test
    fun mergeDetectOffAlwaysOneBoxAndRegroupFixesIt() {
        for ((name, combo) in combos) {
            val rig = Rig(combo.copy(mergeDetect = MergeDetect.OFF))
            rig.clear()
            rig.place(144.0)   // 开着识别时是 3 箱
            assertEquals(1, rig.boxes.size, name)
            assertEquals(1, rig.machine.k, name)
            assertEquals(Jin.of(142.0), rig.boxes[0].net, name)

            // 箱数在最新一行改（SplitBoxes.regroup）
            val fixed = SplitBoxes.regroup(rig.boxes, 1, 3)
            assertEquals(3, fixed.size, name)
            assertEquals(listOf(Jin.of(46.0), Jin.of(46.0), Jin.of(46.0)), fixed.map { it.net }, name)
        }
    }

    @Test
    fun mergeDetectOffNeverAsksEvenWhenAmbiguous() {
        val rig = Rig(WeighOptions(mergeDetect = MergeDetect.OFF))
        rig.clear()
        assertNotNull(rig.place(256.0))
        assertNull(rig.machine.candidates)
        assertEquals(1, rig.boxes.size)
    }

    // ---- 注入的箱数识别 ----

    @Test
    fun usesTheInjectedDetector() {
        var seen: List<Any>? = null
        val rig = Rig(WeighOptions(), detect = { gross, tare, std, pct, maxK ->
            seen = listOf(gross, tare, std, pct, maxK)
            DetectResult(2, tare + tare, gross - tare - tare, null)
        })
        rig.clear()
        rig.place(100.0)
        assertEquals(listOf<Any>(Jin.of(100.0), Jin.of(2.0), Jin.of(45.0), 10, 8), seen)
        assertEquals(2, rig.boxes.size)
        assertEquals(Jin.of(96.0), rig.boxes.fold(Jin.ZERO) { s, b -> s + b.net })
    }

    @Test
    fun detectorGetsTheOptionsThresholds() {
        var seen: List<Int>? = null
        val rig = Rig(WeighOptions(mergeTolerancePct = 5, mergeMaxK = 4), detect = { g, t, _, pct, maxK ->
            seen = listOf(pct, maxK)
            DetectResult(1, t, g - t, null)
        })
        rig.clear()
        rig.place(48.0)
        assertEquals(listOf(5, 4), seen)
    }

    // ---- weigh.empty-below-jin ----

    @Test
    fun emptyBelowOptionDecidesWhatCountsAsEmpty() {
        val rig = Rig(WeighOptions(emptyBelow = Jin.of(10.0)))
        rig.clear()
        assertNull(rig.place(11.5))   // 净重 9.5，低于 10，当作空秤
        assertEquals(WeighPhase.EMPTY, rig.machine.phase)
        assertNotNull(rig.place(12.5))   // 净重 10.5
    }
}

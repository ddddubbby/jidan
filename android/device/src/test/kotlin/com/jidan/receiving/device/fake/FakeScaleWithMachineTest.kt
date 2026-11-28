package com.jidan.receiving.device.fake

import com.jidan.receiving.core.Box
import com.jidan.receiving.core.Jin
import com.jidan.receiving.core.ScaleReading
import com.jidan.receiving.core.SplitBoxes
import com.jidan.receiving.core.StableBy
import com.jidan.receiving.core.Tare
import com.jidan.receiving.core.TareBy
import com.jidan.receiving.core.WeighOptions
import com.jidan.receiving.core.WeighOutcome
import com.jidan.receiving.core.WeighingMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 把 [FakeScale] 与 `WeighingMachine` 接在一起（做 APP 在 `ReceivingViewModel` 里做的事：
 * 选皮重时 `tare-by = scale` 才下发数字皮；读数一到就喂状态机；`stable-by = app` 时定时 tick），
 * 看“放上、稳定、拿走”之后记出的流水。模拟环境验证，未经实物验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeScaleWithMachineTest {

    private val tare2 = Tare(Jin.of(2.0), "2号箱")

    private class Rig(
        val scope: TestScope,
        val options: WeighOptions,
        val scale: FakeScale,
        val machine: WeighingMachine,
    ) {
        var boxes = emptyList<Box>()
        private var group = 0
        private val tareName = "2号箱"

        fun apply(out: WeighOutcome?) {
            if (out is WeighOutcome.Recorded) {
                boxes = SplitBoxes.append(boxes, out.weighing.toBoxes(++group, tareName))
            }
        }

        /** 选皮重：与 APP 一致，`tare-by = scale` 才下发给秤，再交给状态机。 */
        suspend fun selectTare(t: Tare) {
            if (options.tareBy == TareBy.SCALE) scale.setDigitalTare(t.unit)
            machine.setTare(t)
            scope.runCurrent()
        }

        /** 放上 -> 稳定 -> 拿走 -> 稳定清空；每步给足时间（含 `stable-by = app` 的窗口）。 */
        suspend fun placeAndRemove(netCenti: Int) {
            scale.placeOneBox(netCenti)
            scope.advanceTimeBy(SETTLE_MS)
            scope.runCurrent()
            scale.removeLoad()
            scope.advanceTimeBy(SETTLE_MS)
            scope.runCurrent()
        }

        suspend fun placeBoxesAndRemove(netCentis: List<Int>) {
            scale.placeBoxes(netCentis)
            scope.advanceTimeBy(SETTLE_MS)
            scope.runCurrent()
            scale.removeLoad()
            scope.advanceTimeBy(SETTLE_MS)
            scope.runCurrent()
        }
    }

    /** 建好秤与状态机、连上秤、等空秤稳定（armed）。皮重由各测试自己选。 */
    private suspend fun TestScope.rig(
        options: WeighOptions = WeighOptions(),
        scale: FakeScale.() -> Unit = {},
    ): Rig {
        val fake = FakeScale(backgroundScope, FakeScale.MINE_ID).apply(scale)
        val machine = WeighingMachine(options, Jin.of(45.0))
        val r = Rig(this, options, fake, machine)
        backgroundScope.launch {
            fake.readings.collect { rd ->
                if (rd != null) r.apply(machine.onReading(ScaleReading(rd.raw, rd.stable), currentTime))
            }
        }
        backgroundScope.launch {
            while (true) {
                delay(100)
                r.apply(machine.onTick(currentTime))
            }
        }
        val connect = launch { fake.connect(FakeScale.MINE_ID) }
        advanceTimeBy(FakeScale.CONNECT_MS + 1)
        runCurrent()
        connect.join()
        advanceTimeBy(SETTLE_MS)
        runCurrent()
        return r
    }

    @Test
    fun liSiTenBoxesWithDefaultOptions() = runTest {
        val r = rig()
        r.selectTare(tare2)
        for (c in FakeScale.CASE_NETS_C) r.placeAndRemove(c)

        val nets = r.boxes.map { it.net.cents }
        // 46.5, 45.8, 46, 46, 46, 45.6, 45.6, 45.6, 45.6, 47.8
        assertEquals(FakeScale.CASE_NETS_C.toList(), nets)
        assertEquals(46050, nets.sum()) // 460.5斤（单位 0.01斤）
    }

    @Test
    fun emptyScaleAfterTareShowsMinusTare() = runTest {
        val r = rig()
        r.selectTare(tare2)
        assertEquals(Jin(-200), r.scale.readings.value!!.raw)
        assertEquals(Jin(-200), r.machine.displayNet)
        assertEquals("-2", r.machine.displayNet!!.format())
    }

    @Test
    fun threeBoxesAtOnceAreRecognisedAndSplit() = runTest {
        val r = rig()
        r.selectTare(tare2)
        r.placeBoxesAndRemove(listOf(4600, 4600, 4600))

        assertEquals(3, r.boxes.size)
        assertEquals(listOf(4600, 4600, 4600), r.boxes.map { it.net.cents })
        assertEquals(listOf(1, 2, 3), r.boxes.map { it.seq })
    }

    @Test
    fun tareWeighTakesCurrentPlatformAsTare() = runTest {
        val r = rig()
        r.scale.placeEmptyBox(180)
        advanceTimeBy(SETTLE_MS)
        runCurrent()
        r.scale.tareWeigh()
        runCurrent()
        assertEquals(Jin(0), r.scale.readings.value!!.raw)
        r.scale.setLoadCenti(180 + 4650)
        advanceTimeBy(SETTLE_MS)
        assertEquals(Jin(4650), r.scale.readings.value!!.raw)
    }

    @Test
    fun readingNotNetWithMatchingOptionRecords465() = runTest {
        val o = WeighOptions(readingIsNet = false)
        val r = rig(o) { anomalyReadingNotNet = true }
        r.selectTare(tare2)
        r.placeAndRemove(4650)
        assertEquals(listOf(4650), r.boxes.map { it.net.cents })
    }

    @Test
    fun tareIgnoredWithTareByAppRecords465() = runTest {
        val o = WeighOptions(tareBy = TareBy.APP)
        val r = rig(o) { anomalyTareIgnored = true }
        r.selectTare(tare2)
        r.placeAndRemove(4650)
        assertEquals(listOf(4650), r.boxes.map { it.net.cents })
    }

    @Test
    fun stableBrokenWithStableByAppRecords465() = runTest {
        val o = WeighOptions(stableBy = StableBy.APP)
        val r = rig(o) { anomalyStableBroken = true }
        r.selectTare(tare2)
        r.placeAndRemove(4650)
        assertEquals(listOf(4650), r.boxes.map { it.net.cents })
    }

    @Test
    fun anomaliesWithDefaultOptionsGoWrongOrNotRecord() = runTest {
        // 读数未扣皮：状态机当成已扣皮再加一份皮重，净重记成 48.5
        val a = rig { anomalyReadingNotNet = true }
        a.selectTare(tare2)
        a.placeAndRemove(4650)
        assertEquals(listOf(4850), a.boxes.map { it.net.cents })

        // 去皮指令无效：数字皮没设成，读数是台面总重，同样记成 48.5
        val b = rig { anomalyTareIgnored = true }
        b.selectTare(tare2)
        b.placeAndRemove(4650)
        assertEquals(listOf(4850), b.boxes.map { it.net.cents })

        // 稳定标志失灵：stable-by = sdk 时永远不稳定，不记
        val c = rig { anomalyStableBroken = true }
        c.selectTare(tare2)
        c.placeAndRemove(4650)
        assertTrue(c.boxes.isEmpty())
    }

    private companion object {
        const val SETTLE_MS = 2000L
    }
}

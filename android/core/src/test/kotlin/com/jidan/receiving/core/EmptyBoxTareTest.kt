package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 称重皮（v7 `APP-04`，原型 `tareWeigh`）与上一单皮重的排序。
 * 秤以模拟方式给出读数，未经实物验证。
 */
class EmptyBoxTareTest {

    private fun state(platform: Double?, stable: Boolean = true, connected: Boolean = true) =
        EmptyBoxState(connected, platform?.let { Jin.of(it) }, stable)

    @Test
    fun twoJinEmptyBoxStableIsAvailable() {
        assertTrue(state(2.0).available)
        assertEquals("称空箱 · 按 2.0 斤 去皮", EmptyBoxTare.label(state(2.0)))
    }

    @Test
    fun emptyScaleOrFullBoxOrUnstableOrOfflineIsNotAvailable() {
        // 空秤
        assertFalse(state(0.0).available)
        assertEquals("称空箱 · 秤上 0.0 斤", EmptyBoxTare.label(state(0.0)))
        // 整箱蛋（48.5斤 含箱）
        assertFalse(state(48.5).available)
        assertEquals("称空箱 · 秤上 48.5 斤", EmptyBoxTare.label(state(48.5)))
        // 读数还在跳
        assertFalse(state(2.0, stable = false).available)
        // 秤没连上：不写读数
        assertFalse(state(2.0, connected = false).available)
        assertEquals("称空箱 · 秤上 --.- 斤", EmptyBoxTare.label(state(2.0, connected = false)))
        // 刚换皮重，还没有新读数
        assertFalse(state(null).available)
        assertEquals("称空箱 · 秤上 --.- 斤", EmptyBoxTare.label(state(null)))
    }

    @Test
    fun boundariesFollowPrototype() {
        assertFalse(state(0.39).available)
        assertTrue(state(0.4).available)
        assertTrue(state(19.99).available)
        assertFalse(state(20.0).available)
    }

    /** 模拟秤：台面重量由测试给；`tare-by = scale` 且读数已扣皮时，秤上去过皮后读数是台面减皮。 */
    private class SimScale(val options: WeighOptions) {
        var platform = Jin.ZERO
        var scaleTare = Jin.ZERO
        val raw get() = if (options.tareBy == TareBy.SCALE && options.readingIsNet) platform - scaleTare else platform
        fun tareWeigh() { scaleTare = platform }
    }

    private fun run(options: WeighOptions) {
        val machine = WeighingMachine(options, Jin.of(45.0))
        val scale = SimScale(options)
        var t = 0L
        fun feed(jin: Double): WeighOutcome? {
            scale.platform = Jin.of(jin)
            t += 100
            return machine.onReading(ScaleReading(scale.raw, true), t)
        }

        // 未设皮重：称重框锁定，但台面读数已经有了，是只空箱
        assertNull(feed(2.0))
        assertEquals(WeighPhase.LOCKED, machine.phase)
        val st = EmptyBoxState(true, machine.platformNow, machine.isStable)
        assertTrue(st.available)

        // 按下称空箱：先让秤去皮（tare-by = scale），再把台面读数记作每箱皮重
        if (options.tareBy == TareBy.SCALE) scale.tareWeigh()
        val tare = EmptyBoxTare.tareOf(st.platform!!)
        assertEquals(Jin.of(2.0), tare.unit)
        assertEquals("空箱", tare.name)
        machine.setTare(tare)

        // 空箱还在秤上：不记；之后放 48.5斤（含箱）记出 46.5
        assertNull(feed(2.0))
        val out = feed(48.5)
        assertNotNull(out)
        assertEquals(Jin.of(46.5), out.weighing.net)
        assertEquals(1, out.weighing.k)
    }

    @Test
    fun weighTareThenRecordNetScaleReadingIsNet() =
        run(WeighOptions(tareBy = TareBy.SCALE, readingIsNet = true))

    @Test
    fun weighTareThenRecordNetScaleReadingIsGross() =
        run(WeighOptions(tareBy = TareBy.SCALE, readingIsNet = false))

    @Test
    fun weighTareThenRecordNetAppSide() =
        run(WeighOptions(tareBy = TareBy.APP))

    @Test
    fun mergedWeighingMultipliesWeighTare() {
        val o = WeighOptions(tareBy = TareBy.APP)
        val m = WeighingMachine(o, Jin.of(45.0))
        m.onReading(ScaleReading(Jin.of(2.0), true), 100)
        m.setTare(EmptyBoxTare.tareOf(m.platformNow!!))
        m.onReading(ScaleReading(Jin.ZERO, true), 200)
        // 3 箱各 46.5 净 + 各 2 皮
        val out = m.onReading(ScaleReading(Jin.of(3 * 48.5), true), 300)
        assertNotNull(out)
        assertEquals(3, out.weighing.k)
        assertEquals(Jin.of(6.0), out.weighing.tareTotal)
        assertEquals(Jin.of(3 * 46.5), out.weighing.net)
    }

    @Test
    fun lastUsedTareGoesFirstOthersKeepOrder() {
        val ids = listOf(1L, 2L, 3L, 4L)
        assertEquals(listOf(3L, 1L, 2L, 4L), TareChoices.ordered(ids, 3L) { it })
        assertEquals(ids, TareChoices.ordered(ids, null) { it })
        // 上一单用的箱子已不在热键里
        assertEquals(ids, TareChoices.ordered(ids, 99L) { it })
    }
}

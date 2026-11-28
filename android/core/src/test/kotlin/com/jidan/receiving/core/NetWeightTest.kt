package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** `scale.tare-by` 与 `scale.reading-is-net` 的三种组合（`Q-03`）。每箱皮重 2斤，合并 3 箱 144斤（净重合计 138斤）。 */
class NetWeightTest {
    private val tare = Jin.of(2.0)

    private fun jin(v: Double) = Jin.of(v)

    // 组合一：tare-by = app。没向秤下发皮重，读数就是台面总重；reading-is-net 无论取什么都不起作用
    @Test
    fun appTareReadingIsGross() {
        for (net in listOf(true, false)) {
            val o = WeighOptions(tareBy = TareBy.APP, readingIsNet = net)
            val r = NetWeight.convert(jin(144.0), tare, 3, o)
            assertEquals(jin(144.0), r.platform, "reading-is-net=$net")
            assertEquals(jin(6.0), r.tareTotal)
            assertEquals(jin(138.0), r.net)
            assertEquals(jin(48.5), NetWeight.convert(jin(48.5), tare, 1, o).platform)
            assertEquals(jin(46.5), NetWeight.convert(jin(48.5), tare, 1, o).net)
        }
    }

    // 组合二：tare-by = scale、reading-is-net = true。秤扣掉了一份皮重；合并称重时另外 k−1 份由 APP 补扣
    @Test
    fun scaleTareReadingIsNet() {
        val o = WeighOptions(tareBy = TareBy.SCALE, readingIsNet = true)
        val three = NetWeight.convert(jin(142.0), tare, 3, o)   // 秤上 144，扣一份皮重后读数 142
        assertEquals(jin(144.0), three.platform)
        assertEquals(jin(6.0), three.tareTotal)
        assertEquals(jin(138.0), three.net)

        val one = NetWeight.convert(jin(46.5), tare, 1, o)      // 单箱：读数就是净重
        assertEquals(jin(48.5), one.platform)
        assertEquals(jin(46.5), one.net)

        assertEquals(jin(0.0), NetWeight.platform(jin(-2.0), tare, o), "空秤显示 −皮重，台面总重为 0")
    }

    // 组合三：tare-by = scale、reading-is-net = false。下发了皮重，读数仍是台面总重，APP 全额扣
    @Test
    fun scaleTareReadingIsGross() {
        val o = WeighOptions(tareBy = TareBy.SCALE, readingIsNet = false)
        val three = NetWeight.convert(jin(144.0), tare, 3, o)
        assertEquals(jin(144.0), three.platform)
        assertEquals(jin(138.0), three.net)
        assertEquals(jin(46.5), NetWeight.convert(jin(48.5), tare, 1, o).net)
    }

    // Q-03 的两种答案对同一个读数给出不同的净重，选项就是用来换的
    @Test
    fun sameReadingGivesDifferentNetUnderTheTwoAnswers() {
        val reading = jin(46.5)
        val net = NetWeight.convert(reading, tare, 1, WeighOptions(tareBy = TareBy.SCALE, readingIsNet = true)).net
        val gross = NetWeight.convert(reading, tare, 1, WeighOptions(tareBy = TareBy.SCALE, readingIsNet = false)).net
        assertEquals(jin(46.5), net)
        assertEquals(jin(44.5), gross)
    }

    @Test
    fun noTareMeansReadingIsPlatformInEveryCombo() {
        for (o in listOf(
            WeighOptions(tareBy = TareBy.APP),
            WeighOptions(tareBy = TareBy.SCALE, readingIsNet = true),
            WeighOptions(tareBy = TareBy.SCALE, readingIsNet = false),
        )) {
            assertEquals(jin(48.0), NetWeight.platform(jin(48.0), Jin.ZERO, o))
        }
    }
}

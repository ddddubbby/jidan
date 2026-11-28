package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

class DetectKTest {

    private fun detect(gross: Double, tare: Double = 2.0, std: Double = 45.0, pct: Int = 10, maxK: Int = 8) =
        DetectK.detect(Jin.of(gross), Jin.of(tare), Jin.of(std), pct, maxK)

    @Test
    fun fixtureCases() {
        val cases = loadFixture("detect-k.json")["cases"]!!.arr()
        assertTrue(cases.isNotEmpty())
        for (c in cases.map { it.obj() }) {
            val id = c["id"]!!.str()
            val i = c["input"]!!.obj()
            val e = c["expected"]!!.obj()
            val r = DetectK.detect(
                i["grossJin"]!!.jin(), i["tareJin"]!!.jin(), i["standardNetJin"]!!.jin(),
                i["tolerancePct"]!!.int(), i["maxK"]!!.int(),
            )
            assertEquals(e["k"]!!.int(), r.k, "$id k")
            assertEquals(e["tareTotalJin"]!!.jin(), r.tareTotal, "$id 合计皮重")
            assertEquals(e["netTotalJin"]!!.jin(), r.netTotal, "$id 净重合计")
            assertEquals((e["ambiguous"] as JsonPrimitive).booleanOrNull, r.ambiguous, "$id 识别不准")
            val cand = e["candidates"]!!
            if (cand is JsonNull) assertNull(r.candidates, "$id 候选")
            else assertEquals(cand.arr().map { it.int() }, r.candidates!!.toList(), "$id 候选")
        }
    }

    @Test
    fun threeBoxesOf46() {
        val r = detect(144.0)
        assertEquals(3, r.k)
        assertEquals(Jin(13800), r.netTotal)
        assertEquals(Jin(600), r.tareTotal)
        assertNull(r.candidates)
    }

    @Test
    fun ambiguousKeepsCloserKAndListsSmallerFirst() {
        // 256斤 皮 2：5 箱每箱 49.2、6 箱每箱 40.67，5 箱更近；候选小在前
        val five = detect(256.0)
        assertEquals(5, five.k)
        assertEquals(Pair(5, 6), five.candidates)
        // 288斤：6 箱每箱净 46；7 箱每箱 39.14，偏差 5.86 超出 4.5，不含糊
        assertNull(detect(288.0).candidates)
        // 邻居在小的一侧时 k 是较大的那个：容差放宽到 40%，184斤 的 4 箱每箱 44 最近，3 箱每箱 59.3 也在 18 以内
        val wide = detect(184.0, pct = 40)
        assertEquals(4, wide.k)
        assertEquals(Pair(3, 4), wide.candidates)
    }

    @Test
    fun equallyCloseTakesSmallerK() {
        // W0=3、皮 0、gross=4：1 箱偏差 1，2 箱每箱 2 偏差 1，并列取 1
        val r = DetectK.detect(Jin(400), Jin.ZERO, Jin(300), 50, 8)
        assertEquals(1, r.k)
        assertEquals(Pair(1, 2), r.candidates)
    }

    @Test
    fun toleranceIsConfigurable() {
        // 256斤：10% 时 5、6 都在容差内，3% 时都不在
        assertEquals(Pair(5, 6), detect(256.0, pct = 10).candidates)
        assertNull(detect(256.0, pct = 3).candidates)
    }

    @Test
    fun maxKLimitsTheSearch() {
        // 8 箱每箱净 46、皮 2 → 384斤；上限 4 时最多识别成 4 箱
        assertEquals(8, detect(384.0, maxK = 8).k)
        assertEquals(4, detect(384.0, maxK = 4).k)
    }

    @Test
    fun neighborBeyondMaxKIsNotOffered() {
        // 8 箱每箱净 47 偏差很小；9 箱每箱 41.6 也在容差内，但上限是 8，不能当候选
        val r = detect(gross = 8 * 49.0, maxK = 8)
        assertEquals(8, r.k)
        assertNull(r.candidates)
    }
}

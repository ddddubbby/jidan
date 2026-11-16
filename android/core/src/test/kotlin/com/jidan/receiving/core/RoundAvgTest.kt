package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundAvgTest {

    @Test
    fun pricingCasesAverageWeight() {
        val cases = loadFixture("pricing.json")["cases"]!!.arr()
        assertTrue(cases.isNotEmpty())
        for (c in cases.map { it.obj() }) {
            val id = c["id"]!!.str()
            val input = c["input"]!!.obj()
            val expected = c["expected"]!!.obj()
            val boxes = input["boxNetKg"]!!.arr().map { it.kg() }
            val total = boxes.sum()
            assertEquals(expected["totalKg"]!!.kg(), total, "$id 总重")
            val rule = RoundRule.fromWire(input["rounding"]!!.str())
            assertEquals(expected["avgKg"]!!.kg(), RoundAvg.of(total, boxes.size, rule), "$id 取整均重")
        }
    }

    /** v7 2.2 精度表：46.05、46.5、46.9 三行，每行三种规则。 */
    @Test
    fun roundingTableFromV7() {
        val rows = loadFixture("pricing.json")["roundingTable"]!!.arr()
        assertEquals(3, rows.size)
        for (r in rows.map { it.obj() }) {
            val total = r["totalKg"]!!.kg()
            val n = r["boxCount"]!!.int()
            val label = "均重 ${r["rawAvgKg"]!!.kg()}"
            assertEquals(r["half"]!!.kg(), RoundAvg.of(total, n, RoundRule.HALF), "$label 四舍五入")
            assertEquals(r["floor"]!!.kg(), RoundAvg.of(total, n, RoundRule.FLOOR), "$label 抹零")
            assertEquals(r["ceil"]!!.kg(), RoundAvg.of(total, n, RoundRule.CEIL), "$label 进位")
        }
    }

    @Test
    fun exactIntegerAverageIsUnchangedByAnyRule() {
        for (rule in RoundRule.entries) {
            assertEquals(Kg(4600), RoundAvg.of(Kg(13800), 3, rule), rule.name)
        }
    }

    @Test
    fun halfRoundsExactlyPointFiveUp() {
        assertEquals(Kg(4700), RoundAvg.of(Kg(9300), 2, RoundRule.HALF))   // 46.5
        assertEquals(Kg(4600), RoundAvg.of(Kg(9298), 2, RoundRule.HALF))   // 46.49
    }

    @Test
    fun defaultRuleIsHalf() {
        assertEquals(Kg(4700), RoundAvg.of(Kg(4650), 1))
        assertEquals(RoundRule.HALF, RoundRule.DEFAULT)
    }

    @Test
    fun wireNamesMatchFixtures() {
        assertEquals(RoundRule.HALF, RoundRule.fromWire("half"))
        assertEquals(RoundRule.FLOOR, RoundRule.fromWire("floor"))
        assertEquals(RoundRule.CEIL, RoundRule.fromWire("ceil"))
    }
}

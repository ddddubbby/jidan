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
            // 带每箱损耗的算例只在后端计价里成立：APP 的均重按实称净重算，损耗在后端计价时才加
            if (input["lossJin"] != null) continue
            val expected = c["expected"]!!.obj()
            val boxes = input["boxNetJin"]!!.arr().map { it.jin() }
            val total = boxes.sum()
            assertEquals(expected["totalJin"]!!.jin(), total, "$id 总重")
            val rule = RoundRule.fromWire(input["rounding"]!!.str())
            assertEquals(expected["avgJin"]!!.jin(), RoundAvg.of(total, boxes.size, rule), "$id 取整均重")
        }
    }

    /** v7 2.2 精度表：46.05、46.5、46.9 三行，每行三种规则。 */
    @Test
    fun roundingTableFromV7() {
        val rows = loadFixture("pricing.json")["roundingTable"]!!.arr()
        assertEquals(3, rows.size)
        for (r in rows.map { it.obj() }) {
            val total = r["totalJin"]!!.jin()
            val n = r["boxCount"]!!.int()
            val label = "均重 ${r["rawAvgJin"]!!.jin()}"
            assertEquals(r["half"]!!.jin(), RoundAvg.of(total, n, RoundRule.HALF), "$label 四舍五入")
            assertEquals(r["floor"]!!.jin(), RoundAvg.of(total, n, RoundRule.FLOOR), "$label 抹零")
            assertEquals(r["ceil"]!!.jin(), RoundAvg.of(total, n, RoundRule.CEIL), "$label 进位")
        }
    }

    @Test
    fun exactIntegerAverageIsUnchangedByAnyRule() {
        for (rule in RoundRule.entries) {
            assertEquals(Jin(4600), RoundAvg.of(Jin(13800), 3, rule), rule.name)
        }
    }

    @Test
    fun halfRoundsExactlyPointFiveUp() {
        assertEquals(Jin(4700), RoundAvg.of(Jin(9300), 2, RoundRule.HALF))   // 46.5
        assertEquals(Jin(4600), RoundAvg.of(Jin(9298), 2, RoundRule.HALF))   // 46.49
    }

    @Test
    fun defaultRuleIsHalf() {
        assertEquals(Jin(4700), RoundAvg.of(Jin(4650), 1))
        assertEquals(RoundRule.HALF, RoundRule.DEFAULT)
    }

    @Test
    fun wireNamesMatchFixtures() {
        assertEquals(RoundRule.HALF, RoundRule.fromWire("half"))
        assertEquals(RoundRule.FLOOR, RoundRule.fromWire("floor"))
        assertEquals(RoundRule.CEIL, RoundRule.fromWire("ceil"))
    }
}

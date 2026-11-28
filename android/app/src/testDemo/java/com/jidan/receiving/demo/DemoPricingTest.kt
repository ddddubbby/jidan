package com.jidan.receiving.demo

import com.google.gson.JsonParser
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 演示版的离线计价对 `fixtures/pricing.json`（与后端、:core 共用的算例）逐条核对。 */
class DemoPricingTest {

    private val root = JsonParser.parseString(
        File("../../fixtures/pricing.json").also { check(it.exists()) { "找不到 fixtures/pricing.json" } }.readText(),
    ).asJsonObject

    private fun dec(e: com.google.gson.JsonElement) = BigDecimal(e.asString)

    @Test
    fun everyFixtureCaseMatches() {
        val cases = root.getAsJsonArray("cases")
        assertTrue(cases.size() >= 7)
        for (c in cases) {
            val o = c.asJsonObject
            val id = o.get("id").asString
            val input = o.getAsJsonObject("input")
            val expected = o.getAsJsonObject("expected")
            val r = DemoPricing.compute(
                boxNetJin = input.getAsJsonArray("boxNetJin").map(::dec),
                standardNetJin = dec(input.get("standardNetJin")),
                basePrice = dec(input.get("basePrice")),
                ruleId = 1,
                ruleName = "算例",
                coef = dec(input.get("coefficient")),
                roundMode = input.get("rounding").asString,
                lossJin = input.get("lossJin")?.let(::dec) ?: BigDecimal.ZERO,
            )
            fun check(field: String, actual: Double?) {
                val want = dec(expected.get(field))
                assertEquals(0, want.compareTo(BigDecimal.valueOf(actual!!)), "$id.$field 应是 $want，实际 $actual")
            }
            check("totalJin", r.totalJin)
            check("rawAvgJin", r.rawAvgJin)
            check("avgJin", r.avgJin)
            check("tailJin", r.tailJin)
            check("priceDiff", r.priceDiff)
            check("unitPrice", r.unitPrice)
            check("amount", r.amount)
            assertEquals(emptyList(), r.issues, id)
        }
    }

    @Test
    fun roundingTableMatches() {
        for (row in root.getAsJsonArray("roundingTable")) {
            val o = row.asJsonObject
            // 用 boxCount 箱、合计 totalJin 的一批：每箱取均值，合计恰为 totalJin
            val n = o.get("boxCount").asInt
            val total = dec(o.get("totalJin"))
            val each = total.divide(BigDecimal(n), 4, java.math.RoundingMode.HALF_UP)
            for (mode in listOf("half", "floor", "ceil")) {
                val r = DemoPricing.compute(List(n) { each }, BigDecimal(45), BigDecimal(180), 1, "r", BigDecimal.ONE, mode)
                assertEquals(0, dec(o.get(mode)).compareTo(BigDecimal.valueOf(r.avgJin!!)), "${o.get("totalJin")} $mode")
            }
        }
    }

    @Test
    fun missingRuleOrBasePriceGivesIssuesAndNoMoney() {
        val nets = listOf(BigDecimal("46"), BigDecimal("46"))
        val noRule = DemoPricing.compute(nets, BigDecimal(45), BigDecimal(180), null, null, null, "half")
        assertTrue(noRule.noRule && !noRule.noBasePrice)
        assertEquals(null, noRule.unitPrice)
        assertEquals(1.0, noRule.tailJin)
        val noBase = DemoPricing.compute(nets, BigDecimal(45), null, 1, "r", BigDecimal.ONE, "half")
        assertTrue(noBase.noBasePrice && !noBase.noRule)
        assertEquals(null, noBase.amount)
        val empty = DemoPricing.compute(emptyList(), BigDecimal(45), BigDecimal(180), 1, "r", BigDecimal.ONE, "half")
        assertEquals(0, empty.boxCount)
        assertEquals(null, empty.avgJin)
    }
}

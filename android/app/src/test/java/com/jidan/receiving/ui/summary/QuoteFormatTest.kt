package com.jidan.receiving.ui.summary

import com.jidan.receiving.data.QuoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteFormatTest {
    @Test
    fun moneyDropsTrailingZeros() {
        assertEquals("1810", formatMoney(1810.0))
        assertEquals("181", formatMoney(181.0))
        assertEquals("1.25", formatMoney(1.25))
    }

    @Test
    fun acceptanceFormulaMatchesV7_6_4() {
        val q = QuoteResult(
            boxCount = 10,
            totalKg = 460.5,
            rawAvgKg = 46.05,
            avgKg = 46.0,
            standardNetKg = 45.0,
            basePrice = 180.0,
            ruleId = 1,
            ruleName = "粉蛋收购",
            coefficient = 1.0,
            tailKg = 1.0,
            priceDiff = 1.0,
            unitPrice = 181.0,
            amount = 1810.0,
            roundMode = "HALF",
        )
        assertEquals("1810", formatMoney(q.amount))
        assertEquals("181", formatMoney(q.unitPrice))
        assertEquals("46", formatMoney(q.avgKg))
        val line = formulaLine(q)
        assertTrue(line.contains("46.05"))
        assertTrue(line.contains("四舍五入为 46kg"))
        assertTrue(line.contains("基准 180"))
        assertTrue(line.contains("181 元/箱"))
    }

    @Test
    fun issuesFlags() {
        val q = QuoteResult(issues = listOf("NO_RULE", "NO_BASE_PRICE"))
        assertTrue(q.noRule)
        assertTrue(q.noBasePrice)
    }
}

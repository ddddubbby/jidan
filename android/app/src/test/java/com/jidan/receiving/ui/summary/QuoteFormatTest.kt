package com.jidan.receiving.ui.summary

import com.jidan.receiving.data.QuoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteFormatTest {
    @Test
    fun numDropsTrailingZeros() {
        assertEquals("1810", formatNum(1810.0))
        assertEquals("181", formatNum(181.0))
        assertEquals("181.5", formatNum(181.5))
        assertEquals("1.25", formatNum(1.25))
        assertEquals("—", formatNum(null))
    }

    @Test
    fun moneyGroupsThousandsOnly() {
        // 金额 1810 → 1,810（原型 money）；不到 1000 的与不加千分位时一样，小数、舍入不动
        assertEquals("1,810", formatMoney(1810.0))
        assertEquals("1,000", formatMoney(1000.0))
        assertEquals("999", formatMoney(999.0))
        assertEquals("181", formatMoney(181.0))
        assertEquals("181.5", formatMoney(181.5))
        assertEquals("1,234.5", formatMoney(1234.5))
        assertEquals("12,345.68", formatMoney(12345.678))
        assertEquals("1,234,567", formatMoney(1234567.0))
        assertEquals("-1,500", formatMoney(-1500.0))
        assertEquals("0", formatMoney(0.0))
        assertEquals("—", formatMoney(null))
    }

    @Test
    fun groupThousandsLeavesNonNumbersAlone() {
        assertEquals("—", groupThousands("—"))
        assertEquals("", groupThousands(""))
        assertEquals("1,000.25", groupThousands("1000.25"))
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
        assertEquals("1,810", formatMoney(q.amount))
        assertEquals("181", formatNum(q.unitPrice))
        assertEquals("46", formatNum(q.avgKg))
        val line = formulaLine(q)
        // 显示用的文字里夹着不换行空格和零宽连接符；去掉后就是 v7 6.4 的写法
        val plain = line.replace("\u2060", "").replace('\u00A0', ' ')
        assertTrue(plain.contains("基准 180 + 多 1kg × 1 元 = 181 元/箱"))
        assertTrue("181\u00A0元/\u2060箱" in line)   // 单位不拆开
        assertTrue(line.contains("46.05"))
        assertTrue(line.contains("四舍五入为 46kg"))
        assertTrue(line.contains("基准 180"))
        assertTrue(plain.contains("181 元/箱"))
    }

    @Test
    fun issuesFlags() {
        val q = QuoteResult(issues = listOf("NO_RULE", "NO_BASE_PRICE"))
        assertTrue(q.noRule)
        assertTrue(q.noBasePrice)
    }
}

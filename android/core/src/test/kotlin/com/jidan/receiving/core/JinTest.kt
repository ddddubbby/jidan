package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JinTest {
    @Test
    fun formatDropsTrailingZeros() {
        assertEquals("46", Jin(4600).format())
        assertEquals("45.6", Jin(4560).format())
        assertEquals("15.33", Jin(1533).format())
        assertEquals("0.05", Jin(5).format())
        assertEquals("0", Jin(0).format())
        assertEquals("-0.5", Jin(-50).format())
        assertEquals("-1", Jin(-100).format())
    }

    @Test
    fun format1HasFixedOneDecimal() {
        assertEquals("46.0", Jin(4600).format1())
        assertEquals("45.6", Jin(4560).format1())
        assertEquals("15.3", Jin(1533).format1())
        assertEquals("15.4", Jin(1535).format1())
        assertEquals("0.0", Jin(0).format1())
        assertEquals("0.0", Jin(-4).format1())
        assertEquals("-0.5", Jin(-50).format1())
    }

    @Test
    fun conversionFromJinDecimalsIsExact() {
        // 45.6 * 100 在 Double 里是 4560.000000000001，1.15 * 100 是 114.99999999999999
        assertEquals(Jin(4560), Jin.of(45.6))
        assertEquals(Jin(115), Jin.of(1.15))
        assertEquals(Jin(4650), Jin.of(46.5))
        assertEquals(Jin(4560), Jin.parse("45.6"))
        assertEquals(Jin(4600), Jin.parse(" 46 "))
        assertEquals(46.5, Jin(4650).toDouble())
    }

    @Test
    fun arithmeticAndOrdering() {
        assertEquals(Jin(9160), Jin(4560) + Jin(4600))
        assertEquals(Jin(40), Jin(4600) - Jin(4560))
        assertEquals(Jin(9160), listOf(Jin(4560), Jin(4600)).sum())
        assertEquals(Jin.ZERO, emptyList<Jin>().sum())
        assertTrue(Jin(4560) < Jin(4600))
    }
}

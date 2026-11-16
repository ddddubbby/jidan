package com.jidan.receiving.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KgTest {
    @Test
    fun formatDropsTrailingZeros() {
        assertEquals("46", Kg(4600).format())
        assertEquals("45.6", Kg(4560).format())
        assertEquals("15.33", Kg(1533).format())
        assertEquals("0.05", Kg(5).format())
        assertEquals("0", Kg(0).format())
        assertEquals("-0.5", Kg(-50).format())
        assertEquals("-1", Kg(-100).format())
    }

    @Test
    fun format1HasFixedOneDecimal() {
        assertEquals("46.0", Kg(4600).format1())
        assertEquals("45.6", Kg(4560).format1())
        assertEquals("15.3", Kg(1533).format1())
        assertEquals("15.4", Kg(1535).format1())
        assertEquals("0.0", Kg(0).format1())
        assertEquals("0.0", Kg(-4).format1())
        assertEquals("-0.5", Kg(-50).format1())
    }

    @Test
    fun conversionFromKgDecimalsIsExact() {
        // 45.6 * 100 在 Double 里是 4560.000000000001，1.15 * 100 是 114.99999999999999
        assertEquals(Kg(4560), Kg.of(45.6))
        assertEquals(Kg(115), Kg.of(1.15))
        assertEquals(Kg(4650), Kg.of(46.5))
        assertEquals(Kg(4560), Kg.parse("45.6"))
        assertEquals(Kg(4600), Kg.parse(" 46 "))
        assertEquals(46.5, Kg(4650).toDouble())
    }

    @Test
    fun arithmeticAndOrdering() {
        assertEquals(Kg(9160), Kg(4560) + Kg(4600))
        assertEquals(Kg(40), Kg(4600) - Kg(4560))
        assertEquals(Kg(9160), listOf(Kg(4560), Kg(4600)).sum())
        assertEquals(Kg.ZERO, emptyList<Kg>().sum())
        assertTrue(Kg(4560) < Kg(4600))
    }
}

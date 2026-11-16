package com.jidan.receiving.device

import com.jidan.receiving.core.Kg
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 蓝石 SDK 回传值的换算。只测换算；SDK 本身与真秤的表现未经实物验证。 */
class LanstarMappingTest {
    @Test
    fun weightToCents() {
        assertEquals(Kg(4650), LanstarMapping.toKg(BigDecimal("46.5")))
        assertEquals(Kg(4560), LanstarMapping.toKg(BigDecimal("45.60")))
        assertEquals(Kg(1), LanstarMapping.toKg(BigDecimal("0.005"))) // 第 3 位起四舍五入
        assertEquals(Kg(0), LanstarMapping.toKg(BigDecimal("0.004")))
        assertEquals(Kg(-10), LanstarMapping.toKg(BigDecimal("-0.1")))
    }

    @Test
    fun peelTextHasTwoDecimalsAndNoLocaleComma() {
        assertEquals("2.16", LanstarMapping.peelText(Kg(216)))
        assertEquals("2.00", LanstarMapping.peelText(Kg(200)))
        assertEquals("0.50", LanstarMapping.peelText(Kg(50)))
    }

    @Test
    fun rssiBars() {
        assertEquals(3, LanstarMapping.rssiBars(-40))
        assertEquals(2, LanstarMapping.rssiBars(-70))
        assertEquals(1, LanstarMapping.rssiBars(-90))
        assertEquals(1, LanstarMapping.rssiBars(-1)) // 经典蓝牙发现路径的占位值
    }

    @Test
    fun batteryLow() {
        assertTrue(LanstarMapping.batteryLow(false, "15"))
        assertTrue(LanstarMapping.batteryLow(false, "20%"))
        assertFalse(LanstarMapping.batteryLow(false, "21"))
        assertFalse(LanstarMapping.batteryLow(true, "5")) // 充电中不报低
        assertFalse(LanstarMapping.batteryLow(false, "--")) // 取不到数字不报
        assertFalse(LanstarMapping.batteryLow(false, null))
    }

    @Test
    fun tareCodes() {
        assertNull(LanstarMapping.tareFailure(1))
        assertNull(LanstarMapping.tareFailure(-1)) // 旧固件没有返回值
        assertNotNull(LanstarMapping.tareFailure(5))
        assertNull(LanstarMapping.setPeelFailure(1))
        assertNull(LanstarMapping.setPeelFailure(0xee)) // 旧固件成功不回
        assertNotNull(LanstarMapping.setPeelFailure(2))
        assertNotNull(LanstarMapping.setPeelFailure(0xff))
        assertNull(LanstarMapping.clearTareFailure(1))
        assertNotNull(LanstarMapping.clearTareFailure(-2))
        assertNotNull(LanstarMapping.clearTareFailure(999))
    }
}

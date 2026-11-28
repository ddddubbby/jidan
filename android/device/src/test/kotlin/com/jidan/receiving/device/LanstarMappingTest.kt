package com.jidan.receiving.device

import com.jidan.receiving.core.Jin
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
        val j = ScaleUnit.JIN
        assertEquals(Jin(4650), LanstarMapping.toJin(BigDecimal("46.5"), j))
        assertEquals(Jin(4560), LanstarMapping.toJin(BigDecimal("45.60"), j))
        assertEquals(Jin(1), LanstarMapping.toJin(BigDecimal("0.005"), j)) // 第 3 位起四舍五入
        assertEquals(Jin(0), LanstarMapping.toJin(BigDecimal("0.004"), j))
        assertEquals(Jin(-10), LanstarMapping.toJin(BigDecimal("-0.1"), j))
    }

    /** 秤按公斤计（scale.unit = kg）：读数 ×2 成斤，下发皮重 ÷2 成公斤 */
    @Test
    fun kgScaleIsConvertedToJin() {
        val k = ScaleUnit.KG
        assertEquals(Jin(9300), LanstarMapping.toJin(BigDecimal("46.5"), k))
        assertEquals(Jin(9121), LanstarMapping.toJin(BigDecimal("45.605"), k))
        assertEquals(Jin(-20), LanstarMapping.toJin(BigDecimal("-0.1"), k))
        assertEquals("2.00", LanstarMapping.peelText(Jin(400), k))
        assertEquals("4.20", LanstarMapping.peelText(Jin(840), k))
        assertEquals(ScaleUnit.KG, ScaleUnit.of(null))
        assertEquals(ScaleUnit.JIN, ScaleUnit.of("jin"))
    }

    @Test
    fun peelTextHasTwoDecimalsAndNoLocaleComma() {
        val j = ScaleUnit.JIN
        assertEquals("2.16", LanstarMapping.peelText(Jin(216), j))
        assertEquals("2.00", LanstarMapping.peelText(Jin(200), j))
        assertEquals("0.50", LanstarMapping.peelText(Jin(50), j))
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

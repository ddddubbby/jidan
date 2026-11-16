package com.jidan.receiving.device.label

import com.jidan.receiving.core.Kg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelRendererTest {
    private val data = LabelData(
        productName = "粉蛋",
        specName = "360枚/箱",
        net = Kg.of(46.5),
        farmerName = "李四",
        buyDate = "2026.11.16",
        warehouseName = "一号库",
        batchNo = "SG20261116-1",
        seq = 3,
    )

    @Test
    fun qrIsBatchHashSeq() {
        assertEquals("SG20261116-1#3", data.qrPayload)
    }

    @Test
    fun minimalHasFourConceptsNoAvg() {
        val lines = LabelRenderer.lines(data, LabelContent.MINIMAL).map { it.second }
        assertEquals(3, lines.size) // 净重/批次/箱号；二维码单独字段
        assertTrue(lines.any { it.contains("46.5") })
        assertTrue(lines.any { it.contains("SG20261116-1") })
        assertTrue(lines.any { it.contains("第3箱") })
        assertFalse(lines.any { it.contains("均重") })
    }

    @Test
    fun fullHasNineFieldsNoAvg() {
        val lines = LabelRenderer.lines(data, LabelContent.FULL)
        assertEquals(8, lines.size) // 9 项里二维码不在文字行
        assertFalse(lines.any { it.second.contains("均重") })
        assertTrue(lines.any { it.first == "product" && it.second == "粉蛋" })
        assertTrue(lines.any { it.first == "farmer" && it.second.contains("李四") })
    }

    @Test
    fun tsplMinimalContainsQrAndSize() {
        val tpl = LabelTemplate.of(LabelContent.MINIMAL)
        val tspl = LabelRenderer.toTspl(data, tpl)
        assertTrue(tspl.contains("SIZE 60 mm,40 mm"))
        assertTrue(tspl.contains("GAP 2 mm,0"))
        assertTrue(tspl.contains("QRCODE"))
        assertTrue(tspl.contains("SG20261116-1#3"))
        assertTrue(tspl.contains("PRINT 1"))
        assertFalse(tspl.contains("均重"))
    }

    @Test
    fun parseContentAndModeDefaults() {
        assertEquals(LabelContent.MINIMAL, LabelTemplate.parseContent(null))
        assertEquals(LabelContent.FULL, LabelTemplate.parseContent("full"))
        assertEquals(RenderMode.TEXT, LabelRenderer.parseMode(null))
        assertEquals(RenderMode.BITMAP, LabelRenderer.parseMode("bitmap"))
    }
}

package com.jidan.receiving.device.label

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
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
        assertTrue(tspl.contains("GAP 2 mm,0 mm"))
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

    @Test
    fun tsplTextLinesUseSimplifiedChineseFont() {
        for (content in LabelContent.values()) {
            val tspl = LabelRenderer.toTspl(data, LabelTemplate.of(content))
            val texts = tspl.lines().filter { it.startsWith("TEXT ") }
            assertTrue(texts.isNotEmpty())
            // 手册 TEXT 指令字体表：TSS24.BF2 = 简体中文 24×24（GB 码）；"2" 是纯英数字体，印不出中文
            assertTrue(texts.all { it.contains(",\"TSS24.BF2\",") }, tspl)
            assertFalse(texts.any { it.contains(",\"2\",") })
        }
    }

    @Test
    fun qrMatrixIsRealCodeFor21x21WithFinderPatterns() {
        val m = LabelRenderer.qrMatrix(data.qrPayload)
        assertEquals("SG20261116-1#3", data.qrPayload)
        assertEquals(21, m.width) // 14 字节、纠错 L：版本 1
        assertEquals(21, m.height)
        // 三个定位图案（7×7）：外框黑、内圈白、中心 3×3 黑
        for ((ox, oy) in listOf(0 to 0, 14 to 0, 0 to 14)) {
            for (dy in 0 until 7) for (dx in 0 until 7) {
                val ring = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
                assertEquals(ring != 2, m[ox + dx, oy + dy], "定位图案 ($ox,$oy) 格 ($dx,$dy)")
            }
        }
    }

    @Test
    fun qrMatrixDecodesBackToPayload() {
        val m = LabelRenderer.qrMatrix(data.qrPayload)
        val scale = 6
        val quiet = 4
        val side = (m.width + quiet * 2) * scale
        val pixels = IntArray(side * side) { 0xFFFFFF }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (!m[x, y]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0x000000
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        assertEquals("SG20261116-1#3", QRCodeReader().decode(bitmap).text)
    }

    @Test
    fun buyDateComesFromBatchNo() {
        assertEquals("2026.11.16", LabelData.buyDateOf("SG20261116-1"))
        assertEquals("2026.1.5", LabelData.buyDateOf("SG20260105-12"))
        assertEquals(null, LabelData.buyDateOf("abc"))
    }
}

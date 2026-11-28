package com.jidan.receiving.device.label

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.jidan.receiving.core.Jin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelRendererTest {
    private val data = LabelData(
        productName = "粉蛋",
        specName = "360枚/箱",
        net = Jin.of(46.5),
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

    // ---- print.dpi / print.offset-x-mm / print.offset-y-mm ----

    private val g203 = LabelGeometry.parse("203", "0", "0")

    /** TSPL 里每个 TEXT 与 QRCODE 的 (x, y)，按出现顺序。 */
    private fun positions(tspl: String): List<Pair<Int, Int>> =
        tspl.lines().filter { it.startsWith("TEXT ") || it.startsWith("QRCODE ") }.map { l ->
            val xy = l.substringAfter(' ').split(',')
            xy[0].toInt() to xy[1].toInt()
        }

    @Test
    fun dotsPerMmFollowsDpi() {
        assertEquals(8, LabelGeometry.parse("203", null, null).dotsPerMm)
        assertEquals(12, LabelGeometry.parse("300", null, null).dotsPerMm)
        assertEquals(8, LabelGeometry.parse("abc", "x", null).dotsPerMm) // 读不出来用默认
        assertEquals(LabelGeometry(), LabelGeometry.parse(null, null, null))
    }

    @Test
    fun dpi300CoordinatesAreOnePointFiveTimesOf203() {
        for (content in LabelContent.values()) {
            val tpl = LabelTemplate.of(content)
            val p203 = positions(LabelRenderer.toTspl(data, tpl, g203))
            val p300 = positions(LabelRenderer.toTspl(data, tpl, LabelGeometry.parse("300", "0", "0")))
            assertEquals(p203.size, p300.size)
            assertTrue(p203.isNotEmpty())
            // 毫米坐标都是 0.5 的整数倍，乘 8 与乘 12 都是整数，不受截断影响
            p203.zip(p300).forEach { (a, b) ->
                assertEquals(a.first * 3 / 2, b.first, "$content x")
                assertEquals(a.second * 3 / 2, b.second, "$content y")
            }
        }
    }

    @Test
    fun offsetsShiftEveryTextAndQrBy16And8DotsAt203() {
        for (content in LabelContent.values()) {
            val tpl = LabelTemplate.of(content)
            val base = positions(LabelRenderer.toTspl(data, tpl, g203))
            val moved = positions(LabelRenderer.toTspl(data, tpl, LabelGeometry.parse("203", "2", "1")))
            assertEquals(base.size, moved.size)
            base.zip(moved).forEach { (a, b) ->
                assertEquals(a.first + 16, b.first, "$content x")
                assertEquals(a.second + 8, b.second, "$content y")
            }
        }
    }

    @Test
    fun defaultGeometryEqualsOldBehaviour() {
        val tpl = LabelTemplate.of(LabelContent.MINIMAL)
        assertEquals(LabelRenderer.toTspl(data, tpl), LabelRenderer.toTspl(data, tpl, g203))
        // 第一行净重 (3mm, 4mm) = (24, 32) 点
        assertTrue(LabelRenderer.toTspl(data, tpl).contains("TEXT 24,32,"))
    }

    @Test
    fun bitmapLayoutFollowsDpiAndOffsets() {
        // 位图与 TSPL 共用 layout()：这里直接验位置（JVM 单测里没有 android.graphics，不实际画）
        val tpl = LabelTemplate.of(LabelContent.FULL)
        val base = LabelRenderer.layout(data, tpl, g203)
        val moved = LabelRenderer.layout(data, tpl, LabelGeometry.parse("203", "2", "1"))
        base.texts.zip(moved.texts).forEach { (a, b) ->
            assertEquals(a.xDots + 16, b.xDots)
            assertEquals(a.yDots + 8, b.yDots)
            assertEquals(a.fontDots, b.fontDots)
        }
        assertEquals(base.qr.xDots + 16, moved.qr.xDots)
        assertEquals(base.qr.yDots + 8, moved.qr.yDots)
        val big = LabelRenderer.layout(data, tpl, LabelGeometry.parse("300", "0", "0"))
        assertEquals(base.qr.sizeDots * 3 / 2, big.qr.sizeDots)
        base.texts.zip(big.texts).forEach { (a, b) ->
            assertEquals(a.xDots * 3 / 2, b.xDots)
            assertEquals(a.yDots * 3 / 2, b.yDots)
            assertEquals(a.fontDots * 1.5f, b.fontDots)
        }
        // 位图载荷带上每毫米点数，打印适配器据此反推纸张毫米数
        val geo300 = LabelGeometry.parse("300", "0", "0")
        assertEquals(12, geo300.dotsPerMm)
        assertEquals(geo300.dots(60f), 720)
    }
}

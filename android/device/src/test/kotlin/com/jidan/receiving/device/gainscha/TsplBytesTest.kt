package com.jidan.receiving.device.gainscha

import com.jidan.receiving.core.Kg
import com.jidan.receiving.device.label.LabelContent
import com.jidan.receiving.device.label.LabelData
import com.jidan.receiving.device.label.LabelRenderer
import com.jidan.receiving.device.label.LabelTemplate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TsplBytesTest {
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

    private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun chineseGoesOutAsGbBytesNotUtf8() {
        // GB 码取自 Python 的 gb18030 编码器，与 JVM 无关
        assertContentEquals(hex(0xCA, 0xD5, 0xB5, 0xB0), TsplBytes.encode("收蛋"))
        assertContentEquals(hex(0xBE, 0xBB, 0xD6, 0xD8, 0xA3, 0xBA), TsplBytes.encode("净重："))
        assertContentEquals(hex(0xB5, 0xDA, 0x33, 0xCF, 0xE4), TsplBytes.encode("第3箱"))
        assertFalse(TsplBytes.encode("收蛋").contentEquals("收蛋".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun asciiPassesThroughAndLinesEndCrLf() {
        val bytes = TsplBytes.encode("SIZE 60 mm,40 mm\nCLS\r\nPRINT 1\n")
        assertEquals("SIZE 60 mm,40 mm\r\nCLS\r\nPRINT 1\r\n", String(bytes, Charsets.US_ASCII))
    }

    /** 渲染器直接出的 TSPL 就是手册要求的样子：每条以 CR LF 结尾，没有裸 LF，GAP 两个参数都带 mm。 */
    @Test
    fun rendererTsplFollowsManualSyntax() {
        for (content in LabelContent.values()) {
            val tspl = LabelRenderer.toTspl(data, LabelTemplate.of(content))
            assertFalse(tspl.replace("\r\n", "").contains('\n'), "裸 LF")
            assertTrue(tspl.endsWith("PRINT 1\r\n"))
            val lines = tspl.split("\r\n").dropLast(1)
            assertEquals("SIZE 60 mm,40 mm", lines[0])
            assertEquals("GAP 2 mm,0 mm", lines[1])
            assertEquals("DIRECTION 1", lines[2])
            assertEquals("CLS", lines[3])
            val text = Regex("""TEXT \d+,\d+,"TSS24\.BF2",0,1,1,".+"""")
            val qr = Regex("""QRCODE \d+,\d+,L,\d+,A,0,"SG20261116-1#3"""")
            for (l in lines.drop(4).dropLast(1)) {
                assertTrue(text.matches(l) || qr.matches(l), "不符合手册语法：$l")
            }
        }
    }

    @Test
    fun testLabelFollowsManualSyntaxToo() {
        val lines = LabelRenderer.testTspl().split("\r\n").dropLast(1)
        assertEquals(
            listOf(
                "SIZE 60 mm,40 mm",
                "GAP 2 mm,0 mm",
                "DIRECTION 1",
                "CLS",
                "TEXT 24,24,\"TSS24.BF2\",0,1,1,\"收蛋 测试 0123456789\"",
                "PRINT 1",
            ),
            lines,
        )
    }

    /** 手册 BITMAP 示意：0 为黑点；不足 8 的列补 1（白）。 */
    @Test
    fun bitmapRasterBitsAreZeroForBlack() {
        val w = 10
        val h = 2
        val px = IntArray(w * h) { WHITE }
        px[0] = BLACK // 第 0 行最左
        px[9] = BLACK // 第 0 行第 10 列，落在第二个字节的高位第 2 位
        px[w + 7] = BLACK // 第 1 行第 8 列，第一个字节最低位
        val built = TsplBytes.bitmapJob(TsplBytes.Raster(w, h, px), 2f)
        val head = "SIZE 1 mm,0 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1\r\nCLS\r\nBITMAP 0,0,2,2,0,"
        val all = built.bytes
        assertEquals(head, String(all, 0, head.length, Charsets.US_ASCII))
        val raster = all.copyOfRange(head.length, head.length + 4)
        assertContentEquals(hex(0x7F, 0xBF, 0xFE, 0xFF), raster)
        assertEquals("\r\nPRINT 1\r\n", String(all, head.length + 4, all.size - head.length - 4, Charsets.US_ASCII))
        assertTrue(built.logText.contains("BITMAP 0,0,2,2,0,<4 字节点阵>"))
    }

    @Test
    fun bitmapForDefaultPaperIsSixtyBytesWide() {
        val w = 60 * LabelRenderer.DOTS_PER_MM
        val h = 40 * LabelRenderer.DOTS_PER_MM
        val built = TsplBytes.bitmapJob(TsplBytes.Raster(w, h, IntArray(w * h) { WHITE }), 2f)
        val text = String(built.bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 60 mm,40 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1\r\nCLS\r\nBITMAP 0,0,60,320,0,"))
        assertTrue(built.bytes.size > 60 * 320)
    }

    @Test
    fun bitmapPaperMmFollowsDotsPerMm() {
        val w = 60 * 12
        val h = 40 * 12
        val built = TsplBytes.bitmapJob(TsplBytes.Raster(w, h, IntArray(w * h) { WHITE }), 2f, dotsPerMm = 12)
        val text = String(built.bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 60 mm,40 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1\r\nCLS\r\nBITMAP 0,0,90,480,0,"))
    }

    @Test
    fun grayAndTransparentPixels() {
        val px = intArrayOf(0xFF404040.toInt(), 0xFF909090.toInt(), 0x00000000, 0xFF000000.toInt())
        val built = TsplBytes.bitmapJob(TsplBytes.Raster(4, 1, px), 2f)
        val head = "SIZE 0 mm,0 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1\r\nCLS\r\nBITMAP 0,0,1,1,0,"
        // 深灰黑、浅灰白、透明白、黑；剩下 4 位补白
        assertEquals(0x6F.toByte(), built.bytes[head.length])
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}

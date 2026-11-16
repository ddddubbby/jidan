package com.jidan.receiving.device.gainscha

import com.jidan.receiving.device.label.LabelRenderer
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 把 TSPL 指令拼成发给打印机的字节。纯函数，不依赖 Android，对着手册在 JVM 上测。
 * 依据：TSPL 手册 TEXT（字体表 `TSS24.BF2` 为 GB 码）、BITMAP、“设计标签”（每条指令以 CR LF 结尾）。
 */
object TsplBytes {
    /** 佳博 SDK 文档附录 B 推荐中文用 GB18030；字库按 GB 码取字。 */
    val GB: Charset = Charset.forName("GB18030")

    /** 文字指令：统一换行为 CR LF，再转 GB 码。 */
    fun encode(tspl: String): ByteArray =
        tspl.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(GB)

    /** 整张标签的黑白点阵来源：ARGB 像素，行优先。 */
    class Raster(val widthDots: Int, val heightDots: Int, val argb: IntArray)

    /** [bytes] 发给打印机；[logText] 是同一份指令把点阵数据换成“N 字节”的文本，打日志用。 */
    class Built(val bytes: ByteArray, val logText: String)

    /**
     * 位图版：SIZE/GAP/DIRECTION/CLS + 一条 BITMAP（左上角 0,0）+ PRINT。
     * BITMAP 的 width 以字节计、height 以点计，数据为原始字节，行内高位在左，**0 为黑点、1 为白点**（手册 BITMAP 的示意表）。
     * 纸张毫米数由点数反推（200 DPI，1mm = 8 点，同 `LabelRenderer.DOTS_PER_MM`）。
     */
    fun bitmapJob(r: Raster, gapMm: Float): Built {
        val wBytes = (r.widthDots + 7) / 8
        val data = ByteArray(wBytes * r.heightDots) { 0xFF.toByte() }
        for (y in 0 until r.heightDots) {
            for (x in 0 until r.widthDots) {
                if (isBlack(r.argb[y * r.widthDots + x])) {
                    val i = y * wBytes + x / 8
                    data[i] = (data[i].toInt() and (0x80 shr (x % 8)).inv()).toByte()
                }
            }
        }
        val wMm = r.widthDots / LabelRenderer.DOTS_PER_MM
        val hMm = r.heightDots / LabelRenderer.DOTS_PER_MM
        val head = "SIZE $wMm mm,$hMm mm\r\n" +
            "GAP ${gapMm.toInt()} mm,0 mm\r\n" +
            "DIRECTION 1\r\n" +
            "CLS\r\n"
        val bitmapHead = "BITMAP 0,0,$wBytes,${r.heightDots},0,"
        val out = ByteArrayOutputStream()
        out.write(head.toByteArray(GB))
        out.write(bitmapHead.toByteArray(GB))
        out.write(data)
        out.write("\r\nPRINT 1\r\n".toByteArray(GB))
        val log = head + bitmapHead + "<${data.size} 字节点阵>\r\nPRINT 1\r\n"
        return Built(out.toByteArray(), log)
    }

    /** 亮度低于一半算黑；透明像素当白。 */
    private fun isBlack(argb: Int): Boolean {
        val a = argb ushr 24
        if (a < 128) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 < 128
    }
}

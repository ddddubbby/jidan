package com.jidan.receiving.device.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.jidan.receiving.device.PrintPayload
import java.io.ByteArrayOutputStream

/**
 * 按模板生成 TSPL 或位图（开发规格 9.3）。
 * 位图与模拟台预览共用 [drawBitmap]；这里只出指令/图，发给佳博打印机由 `gainscha/GainschaPrinter` 做。
 * 二维码：TSPL 用 QRCODE 指令（打印机自己编码），位图用 zxing 生成的真矩阵，纠错等级两边都是 L。
 */
object LabelRenderer {
    /** 默认 203 DPI：1mm = 8 dots（v7 7.3）；实际按 `print.dpi` 取 [LabelGeometry.dotsPerMm]。 */
    const val DOTS_PER_MM = 8

    /**
     * TSPL 简体中文 24×24 点阵字库（GB 码），见 TSPL 手册 TEXT 指令的字体表（`TSS24.BF2`）。
     * 标签上的行都带中文，统一用它；字库内含半角数字与字母。
     * 注意：中文字库按 GB 码取字，所以指令字符串发给打印机时必须编成 GB18030/GBK 字节，
     * 不能直接按 UTF-8 发送。这一步在 `gainscha/TsplBytes.encode`，这里只出字符串。
     */
    const val FONT_CN = "TSS24.BF2"

    fun render(
        data: LabelData,
        template: LabelTemplate,
        mode: RenderMode,
        geo: LabelGeometry = LabelGeometry(),
    ): PrintPayload = when (mode) {
        RenderMode.TEXT -> PrintPayload.Text(toTspl(data, template, geo))
        RenderMode.BITMAP -> PrintPayload.Bitmap(toPng(data, template, geo), template.gapMm, geo.dotsPerMm)
    }

    fun parseMode(raw: String?): RenderMode =
        when (raw?.trim()?.lowercase()) {
            "bitmap" -> RenderMode.BITMAP
            else -> RenderMode.TEXT
        }

    fun lines(data: LabelData, content: LabelContent): List<Pair<String, String>> = when (content) {
        LabelContent.MINIMAL -> listOf(
            "net" to "净重：${data.net.format()}斤",
            "batch" to "批次：${data.batchNo}",
            "seq" to "第${data.seq}箱",
        )
        LabelContent.FULL -> listOf(
            "product" to data.productName,
            "spec" to data.specName,
            "net" to "净重：${data.net.format()}斤",
            "farmer" to "农户：${data.farmerName}",
            "date" to "收购日期：${data.buyDate}",
            "warehouse" to "收购仓库：${data.warehouseName}",
            "batch" to "批次：${data.batchNo}",
            "seq" to "第${data.seq}箱",
        )
    }

    /** 一行文字落在纸上的点坐标（左上角，含偏移）与字高点数。 */
    data class TextAt(val key: String, val text: String, val xDots: Int, val yDots: Int, val fontDots: Float)

    /** 二维码落在纸上的点坐标（左上角，含偏移）与边长点数。 */
    data class QrAt(val xDots: Int, val yDots: Int, val sizeDots: Int)

    data class Layout(val texts: List<TextAt>, val qr: QrAt)

    /** 所有要素的点坐标：TSPL 与位图共用这一处，毫米换点数与偏移都在这里算。 */
    fun layout(data: LabelData, template: LabelTemplate, geo: LabelGeometry = LabelGeometry()): Layout {
        val textByKey = lines(data, template.content).toMap()
        val texts = template.fields.mapNotNull { f ->
            val text = textByKey[f.key] ?: return@mapNotNull null
            TextAt(f.key, text, geo.xDots(f.xMm), geo.yDots(f.yMm), f.fontMm * geo.dotsPerMm)
        }
        val qr = QrAt(geo.xDots(template.qrXMm), geo.yDots(template.qrYMm), geo.dots(template.qrSizeMm))
        return Layout(texts, qr)
    }

    /**
     * 每条指令以 CR LF 结尾（TSPL 手册“设计标签”）；公制单位与数字之间要有空格，
     * GAP 的两个参数都带 mm（手册 GAP 指令）。
     */
    fun toTspl(data: LabelData, template: LabelTemplate, geo: LabelGeometry = LabelGeometry()): String {
        val w = template.widthMm.toInt()
        val h = template.heightMm.toInt()
        val gap = template.gapMm.toInt()
        val sb = StringBuilder()
        sb.cmd("SIZE $w mm,$h mm")
        sb.cmd("GAP $gap mm,0 mm")
        sb.cmd("DIRECTION 1")
        sb.cmd("CLS")
        val lay = layout(data, template, geo)
        for (t in lay.texts) {
            sb.cmd("TEXT ${t.xDots},${t.yDots},\"$FONT_CN\",0,1,1,\"${escapeTspl(t.text)}\"")
        }
        val cell = (lay.qr.sizeDots / qrMatrix(data.qrPayload).width).coerceIn(1, 10)
        sb.cmd("QRCODE ${lay.qr.xDots},${lay.qr.yDots},L,$cell,A,0,\"${escapeTspl(data.qrPayload)}\"")
        sb.cmd("PRINT 1")
        return sb.toString()
    }

    /** 设备弹层的“打印测试”：一行中文，用来看字库与 GB 编码是否对。 */
    fun testTspl(template: LabelTemplate = LabelTemplate.of(LabelContent.MINIMAL)): String {
        val sb = StringBuilder()
        sb.cmd("SIZE ${template.widthMm.toInt()} mm,${template.heightMm.toInt()} mm")
        sb.cmd("GAP ${template.gapMm.toInt()} mm,0 mm")
        sb.cmd("DIRECTION 1")
        sb.cmd("CLS")
        sb.cmd("TEXT 24,24,\"$FONT_CN\",0,1,1,\"收蛋 测试 0123456789\"")
        sb.cmd("PRINT 1")
        return sb.toString()
    }

    private fun StringBuilder.cmd(line: String) {
        append(line).append("\r\n")
    }

    fun drawBitmap(data: LabelData, template: LabelTemplate, geo: LabelGeometry = LabelGeometry()): Bitmap {
        val w = geo.dots(template.widthMm)
        val h = geo.dots(template.heightMm)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val lay = layout(data, template, geo)
        for (t in lay.texts) {
            paint.textSize = t.fontDots
            canvas.drawText(t.text, t.xDots.toFloat(), t.yDots + t.fontDots, paint)
        }
        drawQr(canvas, data.qrPayload, lay.qr.xDots, lay.qr.yDots, lay.qr.sizeDots)
        return bmp
    }

    fun toPng(data: LabelData, template: LabelTemplate, geo: LabelGeometry = LabelGeometry()): ByteArray {
        val bmp = drawBitmap(data, template, geo)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** 给人看的预览行（模拟台），顺序与版式一致，附二维码原文。 */
    fun previewText(data: LabelData, content: LabelContent): String =
        buildString {
            lines(data, content).forEach { (_, v) -> appendLine(v) }
            append("二维码：").append(data.qrPayload)
        }

    private fun escapeTspl(s: String): String = s.replace("\"", "'")

    /** 箱码的二维码矩阵：纠错等级 L、无白边（纸本身是白的）。尺寸随内容定，`SG20261116-1#3` 是 21×21。 */
    fun qrMatrix(payload: String) = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    /** 把真二维码按整数格宽画进 [size] 见方的区域，左上角在 ([x0], [y0])。 */
    private fun drawQr(canvas: Canvas, payload: String, x0: Int, y0: Int, size: Int) {
        val m = qrMatrix(payload)
        val cell = (size / m.width).coerceAtLeast(1)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m[x, y]) {
                    canvas.drawRect(
                        (x0 + x * cell).toFloat(),
                        (y0 + y * cell).toFloat(),
                        (x0 + (x + 1) * cell).toFloat(),
                        (y0 + (y + 1) * cell).toFloat(),
                        paint,
                    )
                }
            }
        }
    }
}

enum class RenderMode { TEXT, BITMAP }

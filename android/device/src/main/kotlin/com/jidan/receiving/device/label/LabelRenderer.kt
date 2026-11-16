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
 * 位图与模拟台预览共用 [drawBitmap]；真机佳博发送属 P8-2，此处只出指令/图。
 * 二维码：TSPL 用 QRCODE 指令（打印机自己编码），位图用 zxing 生成的真矩阵，纠错等级两边都是 L。
 */
object LabelRenderer {
    /** 200 DPI：1mm = 8 dots（v7 7.3）。 */
    const val DOTS_PER_MM = 8

    /**
     * TSPL 简体中文 24×24 点阵字库（GB 码），见 TSPL 手册 TEXT 指令的字体表（`TSS24.BF2`）。
     * 标签上的行都带中文，统一用它；字库内含半角数字与字母。
     * 注意：中文字库按 GB 码取字，所以指令字符串发给打印机时必须编成 GB18030/GBK 字节，
     * 不能直接按 UTF-8 发送。这一步属 P8-2（佳博适配器），这里只出字符串。
     */
    const val FONT_CN = "TSS24.BF2"

    fun render(
        data: LabelData,
        template: LabelTemplate,
        mode: RenderMode,
    ): PrintPayload = when (mode) {
        RenderMode.TEXT -> PrintPayload.Text(toTspl(data, template))
        RenderMode.BITMAP -> PrintPayload.Bitmap(toPng(data, template))
    }

    fun parseMode(raw: String?): RenderMode =
        when (raw?.trim()?.lowercase()) {
            "bitmap" -> RenderMode.BITMAP
            else -> RenderMode.TEXT
        }

    fun lines(data: LabelData, content: LabelContent): List<Pair<String, String>> = when (content) {
        LabelContent.MINIMAL -> listOf(
            "net" to "净重：${data.net.format()}kg",
            "batch" to "批次：${data.batchNo}",
            "seq" to "第${data.seq}箱",
        )
        LabelContent.FULL -> listOf(
            "product" to data.productName,
            "spec" to data.specName,
            "net" to "净重：${data.net.format()}kg",
            "farmer" to "农户：${data.farmerName}",
            "date" to "收购日期：${data.buyDate}",
            "warehouse" to "收购仓库：${data.warehouseName}",
            "batch" to "批次：${data.batchNo}",
            "seq" to "第${data.seq}箱",
        )
    }

    fun toTspl(data: LabelData, template: LabelTemplate): String {
        val w = template.widthMm.toInt()
        val h = template.heightMm.toInt()
        val gap = template.gapMm.toInt()
        val sb = StringBuilder()
        sb.appendLine("SIZE $w mm,$h mm")
        sb.appendLine("GAP $gap mm,0")
        sb.appendLine("DIRECTION 1")
        sb.appendLine("CLS")
        val textByKey = lines(data, template.content).toMap()
        for (f in template.fields) {
            val text = textByKey[f.key] ?: continue
            val x = mmToDots(f.xMm)
            val y = mmToDots(f.yMm)
            sb.appendLine("TEXT $x,$y,\"$FONT_CN\",0,1,1,\"${escapeTspl(text)}\"")
        }
        val qx = mmToDots(template.qrXMm)
        val qy = mmToDots(template.qrYMm)
        val cell = (mmToDots(template.qrSizeMm) / qrMatrix(data.qrPayload).width).coerceIn(1, 10)
        sb.appendLine("QRCODE $qx,$qy,L,$cell,A,0,\"${escapeTspl(data.qrPayload)}\"")
        sb.appendLine("PRINT 1")
        return sb.toString()
    }

    fun drawBitmap(data: LabelData, template: LabelTemplate): Bitmap {
        val w = mmToDots(template.widthMm)
        val h = mmToDots(template.heightMm)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val textByKey = lines(data, template.content).toMap()
        for (f in template.fields) {
            val text = textByKey[f.key] ?: continue
            paint.textSize = f.fontMm * DOTS_PER_MM
            canvas.drawText(text, f.xMm * DOTS_PER_MM, (f.yMm + f.fontMm) * DOTS_PER_MM, paint)
        }
        drawQr(
            canvas,
            data.qrPayload,
            (template.qrXMm * DOTS_PER_MM).toInt(),
            (template.qrYMm * DOTS_PER_MM).toInt(),
            (template.qrSizeMm * DOTS_PER_MM).toInt(),
        )
        return bmp
    }

    fun toPng(data: LabelData, template: LabelTemplate): ByteArray {
        val bmp = drawBitmap(data, template)
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

    private fun mmToDots(mm: Float): Int = (mm * DOTS_PER_MM).toInt()

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

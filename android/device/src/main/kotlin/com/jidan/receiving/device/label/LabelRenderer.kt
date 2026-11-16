package com.jidan.receiving.device.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.jidan.receiving.device.PrintPayload
import java.io.ByteArrayOutputStream

/**
 * 按模板生成 TSPL 或位图（开发规格 9.3）。
 * 位图与模拟台预览共用 [drawBitmap]；真机佳博发送属 P8-2，此处只出指令/图。
 * 二维码在位图里画伪图案（与原型 fakeQR 同思路），TSPL 用 QRCODE 指令。
 */
object LabelRenderer {
    /** 200 DPI：1mm = 8 dots（v7 7.3）。 */
    const val DOTS_PER_MM = 8

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
            // FONT 2 ≈ 小字；完整内容挤在 40mm 高内
            sb.appendLine("TEXT $x,$y,\"2\",0,1,1,\"${escapeTspl(text)}\"")
        }
        val qx = mmToDots(template.qrXMm)
        val qy = mmToDots(template.qrYMm)
        val cell = (mmToDots(template.qrSizeMm) / 25).coerceIn(3, 8)
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
        drawFakeQr(
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

    /** 伪二维码：由 payload 哈希铺点，角上画定位框（原型 fakeQR）。 */
    private fun drawFakeQr(canvas: Canvas, payload: String, x0: Int, y0: Int, size: Int) {
        val n = 17
        val cell = (size / n).coerceAtLeast(1)
        var h = -2128831035 // FNV-1a 32-bit offset basis
        for (ch in payload) {
            h = h xor ch.code
            h *= 16777619
        }
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (y in 0 until n) {
            for (x in 0 until n) {
                h = h xor (h shl 13)
                h = h xor (h ushr 17)
                h = h xor (h shl 5)
                val corner = (x < 5 && y < 5) || (x > 11 && y < 5) || (x < 5 && y > 11)
                val on = if (corner) {
                    x % 12 == 0 || x % 12 == 4 || y % 12 == 0 || y % 12 == 4 || (x % 12 == 2 && y % 12 == 2)
                } else {
                    (h and 1) != 0
                }
                if (on) {
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

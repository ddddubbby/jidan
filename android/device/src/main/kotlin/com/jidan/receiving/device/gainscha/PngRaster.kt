package com.jidan.receiving.device.gainscha

import android.graphics.BitmapFactory

/** 位图版的 PNG → 点阵来源。唯一用到安卓图像解码的地方，不在 JVM 单测里跑。 */
object PngRaster {
    fun decode(png: ByteArray): TsplBytes.Raster {
        val bmp = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "标签位图解码失败" }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val r = TsplBytes.Raster(bmp.width, bmp.height, px)
        bmp.recycle()
        return r
    }
}

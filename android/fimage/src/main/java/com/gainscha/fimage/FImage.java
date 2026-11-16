package com.gainscha.fimage;

import android.graphics.Bitmap;

public class FImage {

    static {
        System.loadLibrary("fimage");
    }

    /**
     * 图片二值化
     *
     * @param bitmap     图片
     * @param brightness 亮度百分比调整
     * @param gamma      gamma调整，默认1
     * @param dither     是否抖动处理
     */
    public static native Bitmap threshold(Bitmap bitmap, float brightness, float gamma, boolean dither);
}

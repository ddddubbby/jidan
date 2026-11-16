package com.gainscha.jzint;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;

/**
 * @author mxlei
 * @date 2020/12/19
 */
public class JzInt {

    static {
        System.loadLibrary("zbcode");
    }

    private static final String TAG = "JzInt";
    //是否绘制条码辅助线
    private static final boolean DRAW_POSITION_LINE = false;
    public static final int TEXT_LOCATION_NONE = 0;
    public static final int TEXT_LOCATION_TOP = 1;
    public static final int TEXT_LOCATION_BOTTOM = 2;
    public static final int TEXT_ALIGNMENT_LEFT = 0;
    public static final int TEXT_ALIGNMENT_CENTER = 1;
    public static final int TEXT_ALIGNMENT_RIGHT = 2;
    public static final int TEXT_ALIGNMENT_JUSTIFY = 3;
    public static final String DIGITS_CODE_39 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%";
    public static final String DIGITS_CODE_128 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ`¬!\"£$%^*()~=#{}[];':,./?/*-_+&#060;&#062;&#064;&#038;";


    protected static native BitmapResult encodeBarcode(JzintSymbol symbol, String content);

    /**
     * 创建条码
     *
     * @param symbol  条码参数
     * @param content 条码内容
     */
    public static Bitmap create(JzintSymbol symbol, String content) {
        BitmapResult result = encodeBarcode(symbol, content);
        if (result == null) {
            Log.d(TAG, "generate barcode failed");
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(result.getWidth(), result.getHeight(), Bitmap.Config.ARGB_8888);
        byte[] rgb = result.getPixels();
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        int idx = 0;
        for (int i = 0; i < rgb.length; i += 3) {
            pixels[idx++] = (0xFF << 24) | (rgb[i] << 16) | (rgb[i + 1] << 8) | (rgb[i + 2]);
            if (pixels[idx - 1] == Color.WHITE) {
                pixels[idx - 1] = Color.TRANSPARENT;
            }
        }
        bitmap.setPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return bitmap;
    }

    /**
     * 创建条码，非标准宽高
     *
     * @param symbol        条码参数
     * @param content       条码内容
     * @param typeface      文本字体
     * @param removeBlank   是否清除边界空白
     * @param antiAlias     是否抗锯齿
     * @param textLocation  文本位置
     * @param textAlignment 文本对齐方式
     */
    public static Bitmap create(JzintSymbol symbol, String content,
                                Typeface typeface, boolean removeBlank,
                                boolean antiAlias, int textLocation, int textAlignment) {
        if (textLocation < TEXT_LOCATION_NONE || textLocation > TEXT_LOCATION_BOTTOM) {
            textLocation = TEXT_LOCATION_BOTTOM;
        }
        if (textAlignment < TEXT_ALIGNMENT_LEFT || textAlignment > TEXT_ALIGNMENT_JUSTIFY) {
            textAlignment = TEXT_ALIGNMENT_CENTER;
        }
        int show_hrt = symbol.show_hrt;
        int width = symbol.width;
        int height = symbol.height;
        symbol.show_hrt = 0;
        symbol.width = 0;
        symbol.height = 0;
        if (symbol.fontsize < 8) {
            symbol.fontsize = 8;
        }
        Bitmap bitmap = create(symbol, content);
        if (bitmap == null) {
            return null;
        }
        if (width <= 0) {
            width = bitmap.getWidth();
        }
        if (height <= 0) {
            height = (int) (bitmap.getWidth() * bitmap.getHeight() / (float) bitmap.getWidth());
        }
        symbol.width = width;
        symbol.height = height;
        symbol.show_hrt = show_hrt;
        //添加文本
        Bitmap textBarcodeBitmap;
        if (symbol.show_hrt == 0) {
            if (symbol.width == bitmap.getWidth()) {
                textBarcodeBitmap = bitmap;
            } else {
                textBarcodeBitmap = scaleBitmap(bitmap, symbol.width, symbol.height);
            }
        } else {
            //绘制文本
            textBarcodeBitmap = drawBarcodeText(symbol, bitmap, content, typeface, removeBlank, antiAlias, textLocation, textAlignment);
        }
        return textBarcodeBitmap;
    }

    /**
     * 等间采样缩放图片
     */
    public static Bitmap scaleSampleBitmap(Bitmap bitmap, int width, int height) {
        return BitmapFlex.flex(bitmap, width, height);
    }

    private static Bitmap scaleBitmap(Bitmap bitmap, int width, int height) {
        if (bitmap == null) {
            return null;
        }
        if (bitmap.getWidth() == width && bitmap.getHeight() == height) {
            return bitmap;
        }
//        System.out.println(String.format("scale bitmap %d -> %d", bitmap.getWidth(), width));
        Matrix matrix = new Matrix();
        float scaleWidth = ((float) width) / bitmap.getWidth();
        float scaleHeight = ((float) height) / bitmap.getHeight();
        matrix.postScale(scaleWidth, scaleHeight);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * 条码增加绘制文本
     *
     * @param symbol      条码参数
     * @param src         条码图片
     * @param typeface    文本字体
     * @param removeBlank 是否清除边界空白
     * @return 处理后的图片
     */
    private static Bitmap drawBarcodeText(JzintSymbol symbol, Bitmap src, String barcodeText, Typeface typeface,
                                          boolean removeBlank, boolean antiAlias, int textLocation, int textAlignment) {
        if (src == null || src.isRecycled()) {
            return src;
        }
        if (symbol.show_hrt == 0) {
            return src;
        }
        Bitmap bitmap = null;
        switch (symbol.symbology) {
            case JzintSymbol.BARCODE_CODE11:
            case JzintSymbol.BARCODE_CODE16K:
            case JzintSymbol.BARCODE_CODE32:
            case JzintSymbol.BARCODE_CODE39:
            case JzintSymbol.BARCODE_CODE49:
            case JzintSymbol.BARCODE_CODE93:
            case JzintSymbol.BARCODE_CODE128:
            case JzintSymbol.BARCODE_CODE128B:
            case JzintSymbol.BARCODE_CODEONE:
                bitmap = drawNormalText(symbol, src, barcodeText, typeface, removeBlank, antiAlias, textLocation, textAlignment);
                break;
            case JzintSymbol.BARCODE_EANX:
                if (barcodeText.length() == 7) {
                    bitmap = drawEAN8Text(symbol, src, barcodeText, typeface, removeBlank, antiAlias);
                } else if (barcodeText.length() == 12) {
                    bitmap = drawEAN13Text(symbol, src, barcodeText, typeface, removeBlank, antiAlias);
                }
                break;
            case JzintSymbol.BARCODE_UPCA:
                bitmap = drawUPCAText(symbol, src, barcodeText, typeface, removeBlank, antiAlias);
                break;
            case JzintSymbol.BARCODE_UPCE:
                bitmap = drawUPCEText(symbol, src, barcodeText, typeface, removeBlank, antiAlias);
                break;
            case JzintSymbol.BARCODE_ITF14:
                bitmap = drawITF14Text(symbol, src, barcodeText, typeface, removeBlank, antiAlias, textLocation, textAlignment);
                break;
            default:
                bitmap = src;
        }
        return bitmap == null ? src : bitmap;
    }

    private static Bitmap drawNormalText(JzintSymbol symbol, Bitmap src, String barcodeText,
                                         Typeface typeface, boolean removeBlank,
                                         boolean antiAlias, int textLocation, int textAlignment) {
        if (textLocation == TEXT_LOCATION_NONE) {
            return scaleBitmap(src, symbol.width, symbol.height);
        }
        Bitmap bitmap;
        Canvas canvas;
        Paint paint = new Paint();
        paint.setAntiAlias(antiAlias);
        paint.setColor(parseRGBAToColor(symbol));
        if (typeface != null && !typeface.equals(paint.getTypeface())) {
            paint.setTypeface(typeface);
        }
        paint.setStrokeWidth(1);
        paint.setTextSize(symbol.fontsize);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();

        //宽度缩放
        if (symbol.width != src.getWidth()) {
            src = scaleBitmap(src, symbol.width, src.getHeight());
        }
        float textSize = symbol.fontsize;
        float textWidth = paint.measureText(barcodeText);
        float textHeight = fontMetrics.descent - fontMetrics.ascent;
        //水平字号限制
        if (textWidth > src.getWidth()) {
            textSize = textSize * src.getWidth() / textWidth;
            paint.setTextSize(textSize);
            fontMetrics = paint.getFontMetrics();
            textWidth = paint.measureText(barcodeText);
            textHeight = fontMetrics.descent - fontMetrics.ascent;
        }
        float bitMapWidth = src.getWidth();
        float bitmapHeight = src.getHeight() + textHeight;
        bitmap = Bitmap.createBitmap((int) bitMapWidth, (int) bitmapHeight, src.getConfig());
        canvas = new Canvas(bitmap);
//        canvas.drawColor(Color.WHITE);


        float textX, textY;
        float bitmapY;
        switch (textAlignment) {
            case TEXT_ALIGNMENT_LEFT:
                textX = 0;
                break;
            case TEXT_ALIGNMENT_JUSTIFY:
                textX = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && textWidth < bitMapWidth) {
                    paint.setLetterSpacing((bitMapWidth - textWidth) / barcodeText.length() / textSize);
                    fontMetrics = paint.getFontMetrics();
                    textWidth = paint.measureText(barcodeText);
                    textHeight = fontMetrics.descent - fontMetrics.ascent;
                }
                break;
            case TEXT_ALIGNMENT_RIGHT:
                textX = bitMapWidth - textWidth;
                break;
            default:
                textX = (bitMapWidth - textWidth) / 2f;
        }
        switch (textLocation) {
            case TEXT_LOCATION_TOP:
                bitmapY = textHeight;
                textY = textHeight - fontMetrics.descent;
                break;
            default:
                bitmapY = 0;
                textY = bitmapHeight - fontMetrics.descent;
        }
        canvas.drawBitmap(src, 0, bitmapY, null);
        canvas.drawText(barcodeText, textX, textY, paint);

        //高度缩放
        float expandDiv = Math.max(0, bitmap.getHeight() - src.getHeight());
//        int height = (int) Math.max(src.getHeight() * 0.2f + expandDiv, symbol.height);
        int height = (int) Math.max(10 + expandDiv, symbol.height);
        if (height == bitmap.getHeight()) {
            return bitmap;
        } else if (height < bitmap.getHeight()) {
            if (textLocation == TEXT_LOCATION_TOP) {
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), height);
            } else {
                return Bitmap.createBitmap(bitmap, 0, bitmap.getHeight() - height, bitmap.getWidth(), height);
            }
        } else {
            int[] pixels = new int[bitmap.getWidth() * height];
            int divHeight = height - bitmap.getHeight();
            if (textLocation == TEXT_LOCATION_TOP) {
                int offset = bitmap.getWidth() * bitmap.getHeight() - bitmap.getWidth();
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                int[] pixelsLastLine = Arrays.copyOfRange(pixels, offset, offset + bitmap.getWidth());
                for (int i = divHeight; i > 0; i--) {
                    System.arraycopy(pixelsLastLine, 0, pixels, pixels.length - i * pixelsLastLine.length, pixelsLastLine.length);
                }
            } else {
                int offset = divHeight * bitmap.getWidth();
                bitmap.getPixels(pixels, offset, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                int[] pixelsFirstLine = Arrays.copyOfRange(pixels, offset, offset + bitmap.getWidth());
                for (int i = 0; i < divHeight; i++) {
                    System.arraycopy(pixelsFirstLine, 0, pixels, i * pixelsFirstLine.length, pixelsFirstLine.length);
                }
            }
            return Bitmap.createBitmap(pixels, bitmap.getWidth(), height, bitmap.getConfig());
        }
    }

    private static Bitmap drawEAN8Text(JzintSymbol symbol, Bitmap src, String barcodeText, Typeface typeface, boolean removeBlank, boolean antiAlias) {
        //条码内容（带校验位）
        Bitmap bitmap;
        Canvas canvas;
        Paint paint = new Paint();
        paint.setAntiAlias(antiAlias);
        paint.setColor(parseRGBAToColor(symbol));
        if (typeface != null && !typeface.equals(paint.getTypeface())) {
            paint.setTypeface(typeface);
        }
        paint.setStrokeWidth(1);
        paint.setTextSize(symbol.fontsize);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float textSize = symbol.fontsize;
        float textWidth = paint.measureText(barcodeText);
        float textHeight = fontMetrics.descent - fontMetrics.ascent;

        //宽度缩放，适配去空白的宽度
        float w = removeBlank ? 81 * symbol.width / 67f : symbol.width;
        //每个模块的宽度
        float div = w / 81f;
        //文字内容的宽度
        float spaceWidth = 27f * div - 4;
        if (w != src.getWidth()) {
            src = scaleBitmap(src, (int) w, src.getHeight());
        }
        //水平字号约束
        if (spaceWidth < textWidth / 2f) {
            //字号过大时，减小字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                float letterSpace = (textWidth / 2f - spaceWidth) / 4f / textSize;
                if (letterSpace >= 1) {
                    letterSpace = 1;
                }
                paint.setLetterSpacing(-letterSpace);
            } else {
                //字号过大时，缩小字号
                textSize = symbol.fontsize * spaceWidth / (textWidth / 2f);
                paint.setTextSize(textSize);
            }
        } else if (spaceWidth > textWidth / 2f) {
            //字号过小时，增加字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                paint.setLetterSpacing((spaceWidth - textWidth / 2f) / 4f / textSize);
            }
        }
        fontMetrics = paint.getFontMetrics();
        textHeight = fontMetrics.descent - fontMetrics.ascent;

        bitmap = Bitmap.createBitmap(src.getWidth(), (int) (src.getHeight() * 0.9f + textHeight), src.getConfig());
        canvas = new Canvas(bitmap);
//        canvas.drawColor(Color.WHITE);
        //绘制条码
        canvas.drawBitmap(src, 0, 0, null);
        //绘制定点辅助线
        if (DRAW_POSITION_LINE) {
            int color = paint.getColor();
            paint.setColor(Color.RED);
            canvas.drawLine(7 * div, 0, 7 * div, src.getHeight(), paint);
            canvas.drawLine(11 * div, 0, 11 * div, src.getHeight(), paint);
            canvas.drawLine(38 * div, 0, 38 * div, src.getHeight(), paint);
            canvas.drawLine(43 * div, 0, 43 * div, src.getHeight(), paint);
            canvas.drawLine(70 * div, 0, 70 * div, src.getHeight(), paint);
            canvas.drawLine(74 * div, 0, 74 * div, src.getHeight(), paint);
            canvas.drawLine(0, src.getHeight() * 0.9f, src.getWidth(), src.getHeight() * 0.9f, paint);
            paint.setColor(color);
        }
        //绘制文本
        canvas.drawText(barcodeText.substring(0, 4), 11 * div, src.getHeight() * 0.9f - fontMetrics.ascent, paint);
        canvas.drawText(barcodeText.substring(4) + getCheckBit(barcodeText), 43 * div, src.getHeight() * 0.9f - fontMetrics.ascent, paint);
        //清除两边空白
        if (removeBlank) {
            bitmap = Bitmap.createBitmap(bitmap, (int) (7 * div), 0, (int) (67 * div), bitmap.getHeight());
        }

        //高度缩放
        float expandDiv = Math.max(0, bitmap.getHeight() - src.getHeight());
        int height = (int) Math.max(src.getHeight() * 0.2f + expandDiv, symbol.height);
        if (height == bitmap.getHeight()) {
            return bitmap;
        } else if (height < bitmap.getHeight()) {
            return Bitmap.createBitmap(bitmap, 0, bitmap.getHeight() - height, bitmap.getWidth(), height);
        } else {
            int[] pixels = new int[bitmap.getWidth() * height];
            int divHeight = height - bitmap.getHeight();
            int offset = divHeight * bitmap.getWidth();
            bitmap.getPixels(pixels, offset, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            int[] pixelsFirstLine = Arrays.copyOfRange(pixels, offset, offset + bitmap.getWidth());
            for (int i = 0; i < divHeight; i++) {
                System.arraycopy(pixelsFirstLine, 0, pixels, i * pixelsFirstLine.length, pixelsFirstLine.length);
            }
            return Bitmap.createBitmap(pixels, bitmap.getWidth(), height, bitmap.getConfig());
        }
    }

    private static Bitmap drawEAN13Text(JzintSymbol symbol, Bitmap src, String barcodeText, Typeface typeface, boolean removeBlank, boolean antiAlias) {
        //EAN13
        Paint paint = new Paint();
        paint.setAntiAlias(antiAlias);
        paint.setColor(parseRGBAToColor(symbol));
        if (typeface != null && !typeface.equals(paint.getTypeface())) {
            paint.setTypeface(typeface);
        }
        paint.setStrokeWidth(1);

        //宽度缩放，适配去空白的宽度
        float w = removeBlank ? 113f * symbol.width / 106f : symbol.width;
        //每个模块的宽度
        float div = w / 113f;
        //文字内容的宽度
        float spaceWidth = 42f * div - 4;
        if (w != src.getWidth()) {
            src = scaleBitmap(src, (int) w, src.getHeight());
        }

        paint.setTextSize(symbol.fontsize);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float textSize = symbol.fontsize;
        float textWidth = paint.measureText(barcodeText);
        float subTextWidth = 6 * textWidth / barcodeText.length();
        float textHeight = fontMetrics.descent - fontMetrics.ascent;
        //水平字号约束
        if (spaceWidth < subTextWidth) {
            //字号过大时，减小字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                float letterSpace = (subTextWidth - spaceWidth) / 6f / textSize;
                if (letterSpace >= 1) {
                    letterSpace = 1;
                }
                paint.setLetterSpacing(-letterSpace);
            } else {
                //字号过大时，缩小字号
                textSize = symbol.fontsize * spaceWidth / subTextWidth;
                paint.setTextSize(textSize);
            }
        } else if (spaceWidth > subTextWidth) {
            //字号过小时，增加字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                paint.setLetterSpacing((spaceWidth - subTextWidth) / 6f / textSize);
            }
        }
        fontMetrics = paint.getFontMetrics();
        textHeight = fontMetrics.descent - fontMetrics.ascent;
        //垂直字号约束
        Bitmap bitmap = Bitmap.createBitmap(src.getWidth(), (int) (src.getHeight() * 0.9f + textHeight), src.getConfig());
        Canvas canvas = new Canvas(bitmap);
//        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(src, 0, 0, null);
        //绘制定点辅助线
        if (DRAW_POSITION_LINE) {
            int color = paint.getColor();
            paint.setColor(Color.RED);
            canvas.drawLine(11 * div, 0, 11 * div, src.getHeight(), paint);
            canvas.drawLine(14 * div, 0, 14 * div, src.getHeight(), paint);
            canvas.drawLine(56 * div, 0, 56 * div, src.getHeight(), paint);
            canvas.drawLine(61 * div, 0, 61 * div, src.getHeight(), paint);
            canvas.drawLine(103 * div, 0, 103 * div, src.getHeight(), paint);
            canvas.drawLine(106 * div, 0, 106 * div, src.getHeight(), paint);
            canvas.drawLine(0, src.getHeight() * 0.9f, src.getWidth(), src.getHeight() * 0.9f, paint);
            paint.setColor(color);
        }
        //绘制起始符
        canvas.drawText(
                barcodeText.substring(0, 1),
                11 * div / 2f - textWidth / barcodeText.length() / 2,
                bitmap.getHeight() - fontMetrics.descent, paint);
        //绘制左侧数据
        canvas.drawText(barcodeText.substring(1, 7), 14 * div, bitmap.getHeight() - fontMetrics.descent, paint);
        //绘制右侧数据
        int checkBit = getCheckBit(barcodeText);
        canvas.drawText(barcodeText.substring(7) + checkBit, 61 * div, bitmap.getHeight() - fontMetrics.descent, paint);
        //清除两边空白
        if (removeBlank) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, (int) (106 * div), bitmap.getHeight());
        }

        //高度缩放
        float expandDiv = Math.max(0, bitmap.getHeight() - src.getHeight());
        int height = (int) Math.max(src.getHeight() * 0.2f + expandDiv, symbol.height);
        if (height == bitmap.getHeight()) {
            return bitmap;
        } else if (height < bitmap.getHeight()) {
            return Bitmap.createBitmap(bitmap, 0, bitmap.getHeight() - height, bitmap.getWidth(), height);
        } else {
            int[] pixels = new int[bitmap.getWidth() * height];
            int divHeight = height - bitmap.getHeight();
            int offset = divHeight * bitmap.getWidth();
            bitmap.getPixels(pixels, offset, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            int[] pixelsFirstLine = Arrays.copyOfRange(pixels, offset, offset + bitmap.getWidth());
            for (int i = 0; i < divHeight; i++) {
                System.arraycopy(pixelsFirstLine, 0, pixels, i * pixelsFirstLine.length, pixelsFirstLine.length);
            }
            return Bitmap.createBitmap(pixels, bitmap.getWidth(), height, bitmap.getConfig());
        }
    }

    private static Bitmap drawUPCAText(JzintSymbol symbol, Bitmap src, String barcodeText, Typeface typeface, boolean removeBlank, boolean antiAlias) {
        //UPC-A
        Paint paint = new Paint();
        paint.setAntiAlias(antiAlias);
        paint.setColor(parseRGBAToColor(symbol));
        if (typeface != null && !typeface.equals(paint.getTypeface())) {
            paint.setTypeface(typeface);
        }
        paint.setStrokeWidth(1);

        //宽度缩放，适配去空白的宽度
        float w = symbol.width;
        //每个模块的宽度
        float div = w / 113f;
        //文字内容的宽度
        float spaceWidth = 34f * div - 4;
        if (w != src.getWidth()) {
            src = scaleBitmap(src, (int) w, src.getHeight());
        }

        paint.setTextSize(symbol.fontsize);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float textSize = symbol.fontsize;
        float textWidth = paint.measureText(barcodeText);
        float subTextWidth = 5 * textWidth / barcodeText.length();
        float textHeight = fontMetrics.descent - fontMetrics.ascent;
        //水平字号约束
        if (spaceWidth < subTextWidth) {
            //字号过大时，减小字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                float letterSpace = (subTextWidth - spaceWidth) / 5f / textSize;
                if (letterSpace >= 1) {
                    letterSpace = 1;
                }
                paint.setLetterSpacing(-letterSpace);
            } else {
                //字号过大时，缩小字号
                textSize = symbol.fontsize * spaceWidth / subTextWidth;
                paint.setTextSize(textSize);
            }
        } else if (spaceWidth > subTextWidth) {
            //字号过小时，增加字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                paint.setLetterSpacing((spaceWidth - subTextWidth) / 5f / textSize);
            }
        }
        fontMetrics = paint.getFontMetrics();
        textHeight = fontMetrics.descent - fontMetrics.ascent;
        //垂直字号约束
        Bitmap bitmap = Bitmap.createBitmap(src.getWidth(), (int) (src.getHeight() * 0.9f + textHeight), src.getConfig());
        Canvas canvas = new Canvas(bitmap);
//        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(src, 0, 0, null);
        //绘制定点辅助线
        if (DRAW_POSITION_LINE) {
            int color = paint.getColor();
            paint.setColor(Color.RED);
            canvas.drawLine(9 * div, 0, 9 * div, src.getHeight(), paint);
            canvas.drawLine(20 * div, 0, 20 * div, src.getHeight(), paint);
            canvas.drawLine(54 * div, 0, 54 * div, src.getHeight(), paint);
            canvas.drawLine(59 * div, 0, 59 * div, src.getHeight(), paint);
            canvas.drawLine(93 * div, 0, 93 * div, src.getHeight(), paint);
            canvas.drawLine(104 * div, 0, 104 * div, src.getHeight(), paint);
            canvas.drawLine(0, src.getHeight() * 0.9f, src.getWidth(), src.getHeight() * 0.9f, paint);
            paint.setColor(color);
        }
        //绘制起始符
        canvas.drawText(
                barcodeText.substring(0, 1),
//                9 * div / 2f - textWidth / barcodeText.length() / 2f,
                0,
                bitmap.getHeight() - fontMetrics.descent, paint);
        //绘制左侧数据
        canvas.drawText(barcodeText.substring(1, 6), 20 * div, bitmap.getHeight() - fontMetrics.descent, paint);
        //绘制右侧数据
        canvas.drawText(barcodeText.substring(6), 59 * div, bitmap.getHeight() - fontMetrics.descent, paint);
        //绘制右侧校验位
        int checkBit = getCheckBit(barcodeText);
        canvas.drawText(String.valueOf(checkBit), 105 * div, bitmap.getHeight() - fontMetrics.descent, paint);

        //高度缩放
        float expandDiv = Math.max(0, bitmap.getHeight() - src.getHeight());
        int height = (int) Math.max(src.getHeight() * 0.2f + expandDiv, symbol.height);
        if (height == bitmap.getHeight()) {
            return bitmap;
        } else if (height < bitmap.getHeight()) {
            return Bitmap.createBitmap(bitmap, 0, bitmap.getHeight() - height, bitmap.getWidth(), height);
        } else {
            int[] pixels = new int[bitmap.getWidth() * height];
            int divHeight = height - bitmap.getHeight();
            int offset = divHeight * bitmap.getWidth();
            bitmap.getPixels(pixels, offset, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            int[] pixelsFirstLine = Arrays.copyOfRange(pixels, offset, offset + bitmap.getWidth());
            for (int i = 0; i < divHeight; i++) {
                System.arraycopy(pixelsFirstLine, 0, pixels, i * pixelsFirstLine.length, pixelsFirstLine.length);
            }
            return Bitmap.createBitmap(pixels, bitmap.getWidth(), height, bitmap.getConfig());
        }
    }

    private static Bitmap drawUPCEText(JzintSymbol symbol, Bitmap src, String barcodeText, Typeface typeface, boolean removeBlank, boolean antiAlias) {
        //UPC-E
        Paint paint = new Paint();
        paint.setAntiAlias(antiAlias);
        paint.setColor(parseRGBAToColor(symbol));
        if (typeface != null && !typeface.equals(paint.getTypeface())) {
            paint.setTypeface(typeface);
        }
        paint.setStrokeWidth(1);

        //宽度缩放，适配去空白的宽度
        float w = symbol.width;
        //每个模块的宽度
        float div = w / 113f;
        //文字内容的宽度
        float spaceWidth = 73f * div - 4;
        if (w != src.getWidth()) {
            src = scaleBitmap(src, (int) w, src.getHeight());
        }

        paint.setTextSize(symbol.fontsize);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float textSize = symbol.fontsize;
        float textWidth = paint.measureText(barcodeText);
        float subTextWidth = 6 * textWidth / barcodeText.length();
        float textHeight = fontMetrics.descent - fontMetrics.ascent;
        //水平字号约束
        if (spaceWidth < subTextWidth) {
            //字号过大时，减小字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                float letterSpace = (subTextWidth - spaceWidth) / 6f / textSize;
                if (letterSpace >= 1) {
                    letterSpace = 1;
                }
                paint.setLetterSpacing(-letterSpace);
            } else {
                //字号过大时，缩小字号
                textSize = symbol.fontsize * spaceWidth / subTextWidth;
                paint.setTextSize(textSize);
            }
        } else if (spaceWidth > subTextWidth) {
            //字号过小时，增加字间距
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                paint.setLetterSpacing((spaceWidth - subTextWidth) / 6f / textSize);
            }
        }
        fontMetrics = paint.getFontMetrics();
        textHeight = fontMetrics.descent - fontMetrics.ascent;
        //垂直字号约束
        Bitmap bitmap = Bitmap.createBitmap(src.getWidth(), (int) (src.getHeight() * 0.9f + textHeight), src.getConfig());
        Canvas canvas = new Canvas(bitmap);
//        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(src, 0, 0, null);
        //绘制定点辅助线
        if (DRAW_POSITION_LINE) {
            int color = paint.getColor();
            paint.setColor(Color.RED);
            canvas.drawLine(14 * div, 0, 14 * div, src.getHeight(), paint);
            canvas.drawLine(20 * div, 0, 20 * div, src.getHeight(), paint);
            canvas.drawLine(93 * div, 0, 93 * div, src.getHeight(), paint);
            canvas.drawLine(102 * div, 0, 102 * div, src.getHeight(), paint);
            canvas.drawLine(0, src.getHeight() * 0.9f, src.getWidth(), src.getHeight() * 0.9f, paint);
            paint.setColor(color);
        }
        //绘制起始符
        canvas.drawText(
                barcodeText.substring(0, 1),
                0,
                bitmap.getHeight() - fontMetrics.descent, paint);
        //绘制数据
        canvas.drawText(barcodeText.substring(1), 20 * div, bitmap.getHeight() - fontMetrics.descent, paint);
        //绘制右侧校验位
        int checkBit = getCheckBit(barcodeText);
        canvas.drawText(String.valueOf(checkBit), 101 * div, bitmap.getHeight() - fontMetrics.descent, paint);

        //高度缩放
        float expandDiv = Math.max(0, bitmap.getHeight() - src.getHeight());
        int height = (int) Math.max(src.getHeight() * 0.2f + expandDiv, symbol.height);
        if (height == bitmap.getHeight()) {
            return bitmap;
        } else if (height < bitmap.getHeight()) {
            return Bitmap.createBitmap(bitmap, 0, bitmap.getHeight() - height, bitmap.getWidth(), height);
        } else {
            int[] pixels = new int[bitmap.getWidth() * height];
            int divHeight = height - bitmap.getHeight();
            int offset = divHeight * bitmap.getWidth();
            bitmap.getPixels(pixels, offset, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            int[] pixelsFirstLine = Arrays.copyOfRange(pixels, offset, offset + bitmap.getWidth());
            for (int i = 0; i < divHeight; i++) {
                System.arraycopy(pixelsFirstLine, 0, pixels, i * pixelsFirstLine.length, pixelsFirstLine.length);
            }
            return Bitmap.createBitmap(pixels, bitmap.getWidth(), height, bitmap.getConfig());
        }
    }

    private static Bitmap drawITF14Text(JzintSymbol symbol, Bitmap src, String barcodeText,
                                        Typeface typeface, boolean removeBlank,
                                        boolean antiAlias, int textLocation, int textAlignment) {
        if (textLocation == TEXT_LOCATION_NONE) {
            return scaleBitmap(src, symbol.width, symbol.height);
        }
        Bitmap bitmap;
        Canvas canvas;
        Paint paint = new Paint();
        paint.setAntiAlias(antiAlias);
        paint.setColor(parseRGBAToColor(symbol));
        if (typeface != null && !typeface.equals(paint.getTypeface())) {
            paint.setTypeface(typeface);
        }
        paint.setStrokeWidth(1);
        paint.setTextSize(symbol.fontsize);
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();

        //宽度缩放
        if (symbol.width != src.getWidth()) {
            src = scaleBitmap(src, symbol.width, src.getHeight());
        }
        float textSize = symbol.fontsize;
        float textWidth = paint.measureText(barcodeText);
        float textHeight = fontMetrics.descent - fontMetrics.ascent;
        //水平字号限制
        if (textWidth > src.getWidth()) {
            textSize = textSize * src.getWidth() / textWidth;
            paint.setTextSize(textSize);
            fontMetrics = paint.getFontMetrics();
            textWidth = paint.measureText(barcodeText);
            textHeight = fontMetrics.descent - fontMetrics.ascent;
        }

        //高度缩放
        if (symbol.height != src.getHeight() + (int) textHeight) {
            src = scaleBitmap(src, src.getWidth(), symbol.height - (int) textHeight);
        }

        float bitMapWidth = src.getWidth();
        float bitmapHeight = src.getHeight() + textHeight;
        bitmap = Bitmap.createBitmap((int) bitMapWidth, (int) bitmapHeight, src.getConfig());

        float textX, textY;
        float bitmapY;
        switch (textAlignment) {
            case TEXT_ALIGNMENT_LEFT:
                textX = 0;
                break;
            case TEXT_ALIGNMENT_JUSTIFY:
                textX = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && textWidth < bitMapWidth) {
                    paint.setLetterSpacing((bitMapWidth - textWidth) / barcodeText.length() / textSize);
                    fontMetrics = paint.getFontMetrics();
                    textWidth = paint.measureText(barcodeText);
                    textHeight = fontMetrics.descent - fontMetrics.ascent;
                }
                break;
            case TEXT_ALIGNMENT_RIGHT:
                textX = bitMapWidth - textWidth;
                break;
            default:
                textX = (bitMapWidth - textWidth) / 2f;
        }
        switch (textLocation) {
            case TEXT_LOCATION_TOP:
                bitmapY = textHeight;
                textY = textHeight - fontMetrics.descent;
                break;
            default:
                bitmapY = 0;
                textY = bitmapHeight - fontMetrics.descent;
        }
        canvas = new Canvas(bitmap);
//        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(src, 0, bitmapY, null);
        canvas.drawText(barcodeText + getCheckBit(barcodeText), textX, textY, paint);
        return bitmap;
    }

    /**
     * 获取EAN、UPC条码的校验位
     *
     * @param content 内容，必须是数字
     */
    public static int getCheckBit(String content) throws NumberFormatException {
        try {
            int length = content.length();
            int sum = 0;
            for (int i = length - 1; i >= 0; i -= 2) {
                int digit = content.charAt(i) - '0';
                if (digit < 0 || digit > 9) {
                    throw new NumberFormatException();
                }
                sum += digit;
            }
            sum *= 3;
            for (int i = length - 2; i >= 0; i -= 2) {
                int digit = content.charAt(i) - '0';
                if (digit < 0 || digit > 9) {
                    throw new NumberFormatException();
                }
                sum += digit;
            }
            return (1000 - sum) % 10;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static int parseRGBAToColor(JzintSymbol symbol) {
        byte[] fgcolour = new byte[symbol.fgcolour.length];
        if (fgcolour.length == 8) {
            System.arraycopy(symbol.fgcolour, 6, fgcolour, 0, 2);
            System.arraycopy(symbol.fgcolour, 0, fgcolour, 2, 6);
        } else {
            System.arraycopy(symbol.fgcolour, 0, fgcolour, 0, symbol.fgcolour.length);
        }
        String colorStr = "#" + new String(fgcolour);
        return Color.parseColor(colorStr);
    }

    /**
     * 检查条码内容的格式是否正确
     */
    public static boolean isValidBarcodeText(String content, int symbology) {
        if (content == null || content.length() < 1) {
            return false;
        }
        switch (symbology) {
            case JzintSymbol.BARCODE_EANX:
                if (isAllNumber(content) && (content.length() == 7 || content.length() == 12)) {
                    return true;
                } else {
                    return false;
                }
            case JzintSymbol.BARCODE_UPCA:
                if (isAllNumber(content) && content.length() == 11) {
                    return true;
                } else {
                    return false;
                }
            case JzintSymbol.BARCODE_UPCE:
                if (isAllNumber(content) && content.length() == 7) {
                    char ch = content.charAt(0);
                    if (ch == '0' || ch == '1') {
                        return true;
                    }
                } else {
                    return false;
                }
            case JzintSymbol.BARCODE_ITF14:
                if (isAllNumber(content) && content.length() == 13) {
                    return true;
                } else {
                    return false;
                }
            case JzintSymbol.BARCODE_CODE39:
                //共43个字符：26个大写字母、10个数字0-9，7特殊符号：-. $/+%
                for (char ch : content.toCharArray()) {
                    if (!DIGITS_CODE_39.contains(String.valueOf(ch))) {
                        return false;
                    }
                }
                return true;
            case JzintSymbol.BARCODE_CODE128:
                //ASCII 0-127共128个字符
                for (char ch : content.toCharArray()) {
                    if (ch > 127) {
                        return false;
                    }
                }
                return true;
        }
        return true;
    }


    private static boolean isAllNumber(String content) {
        for (char ch : content.toCharArray()) {
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }
}

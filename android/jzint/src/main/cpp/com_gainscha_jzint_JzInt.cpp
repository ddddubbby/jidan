//
// Created by mxlei on 2020/12/19.
//
#include "com_gainscha_jzint_JzInt.h"
#include <stdio.h>
#include "backend/zint.h"
#include <string.h>

extern "C" {
/**
* 根据条码像素创建Bitmap对象
* */
jobject create_bitmap(JNIEnv *jniEnv, struct zint_symbol *my_symbol);

/*
 * Class:     com_gainscha_jzint_JzBarcode
 * Method:    createDataMatrixGs1
 * Signature: (Ljava/lang/String;)Lcom/gainscha/jzint/BitmapResult;
 */
JNIEXPORT jobject JNICALL Java_com_gainscha_jzint_JzBarcode_createDataMatrixGs1
        (JNIEnv *env, jclass, jstring content) {
    struct zint_symbol *my_symbol;
    my_symbol = ZBarcode_Create();
    my_symbol->symbology = BARCODE_DATAMATRIX;
    my_symbol->input_mode = GS1_MODE;
    const char *contentChars = env->GetStringUTFChars(content, nullptr);
    int error_number = ZBarcode_Encode_and_Buffer(my_symbol, (unsigned char *) contentChars,
                                                  strlen(contentChars), 0);
    if (error_number != 0) {
        printf("ZBarcode encode error %d\n", error_number);
        return env->NewGlobalRef(nullptr);
    }
    jobject ret = create_bitmap(env, my_symbol);
    ZBarcode_Delete(my_symbol);
    return ret;
}

/*
 * Class:     com_gainscha_jzint_JzInt
 * Method:    encodeBarcode
 * Signature: (Lcom/gainscha/jzint/JzintSymbol;Ljava/lang/String;)Lcom/gainscha/jzint/BitmapResult;
 */
JNIEXPORT jobject JNICALL Java_com_gainscha_jzint_JzInt_encodeBarcode
        (JNIEnv *jniEnv, jclass, jobject obj, jstring content) {
    const char *contentChars = jniEnv->GetStringUTFChars(content, nullptr);
    struct zint_symbol *zint;
    zint = ZBarcode_Create();

    //获致obj中对象的class
    jclass clazz = jniEnv->GetObjectClass(obj);

    //设置各种参数
    zint->symbology = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "symbology", "I"));
    zint->height = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "height", "I"));
    zint->whitespace_width = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "whitespace_width",
                                                                         "I"));
    //条码颜色
    jbyteArray jba = (jbyteArray) (jniEnv->GetObjectField(obj, jniEnv->GetFieldID(clazz, "fgcolour",
                                                                                  "[B")));
    jbyte *jb = jniEnv->GetByteArrayElements(jba, NULL);
    int len = jniEnv->GetArrayLength(jba);
    memcpy(zint->fgcolour, jb, len);
    zint->fgcolour[len] = '\0';
    jniEnv->ReleaseByteArrayElements(jba, jb, JNI_ABORT);
    zint->fgcolor = zint->fgcolour;

    //背景颜色
    jba = (jbyteArray) (jniEnv->GetObjectField(obj, jniEnv->GetFieldID(clazz, "bgcolour", "[B")));
    jb = jniEnv->GetByteArrayElements(jba, NULL);
    len = jniEnv->GetArrayLength(jba);
    memcpy(zint->bgcolour, jb, len);
    zint->bgcolour[len] = '\0';
    jniEnv->ReleaseByteArrayElements(jba, jb, JNI_ABORT);
    zint->bgcolor = zint->bgcolour;

    zint->scale = jniEnv->GetFloatField(obj, jniEnv->GetFieldID(clazz, "scale", "F"));
    zint->output_options = jniEnv->GetIntField(obj,
                                               jniEnv->GetFieldID(clazz, "output_options", "I"));
    zint->border_width = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "border_width", "I"));
    zint->option_1 = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "option_1", "I"));
    zint->option_2 = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "option_2", "I"));
    zint->option_3 = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "option_3", "I"));
    zint->show_hrt = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "show_hrt", "I"));
    zint->fontsize = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "fontsize", "I"));
    zint->input_mode = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "input_mode", "I"));
    zint->eci = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "eci", "I"));
    zint->rows = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "rows", "I"));
    zint->width = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "width", "I"));
    zint->bitmap_width = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "bitmap_width", "I"));
    zint->bitmap_height = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "bitmap_height", "I"));
    zint->bitmap_byte_length = jniEnv->GetIntField(obj,
                                                   jniEnv->GetFieldID(clazz, "bitmap_byte_length",
                                                                      "I"));
    zint->dot_size = jniEnv->GetFloatField(obj, jniEnv->GetFieldID(clazz, "dot_size", "F"));
    zint->debug = jniEnv->GetIntField(obj, jniEnv->GetFieldID(clazz, "debug", "I"));

//    zint->fgcolour = jniEnv->GetObjectField(obj, jniEnv->GetFieldID(clazz, "fgcolour", "[B"))
    //创建条码
    int error_number = ZBarcode_Encode_and_Buffer(zint, (unsigned char *) contentChars,
                                                  strlen(contentChars), 0);
    //出错
    if (error_number != 0) {
        LOGE("Error on create barcode bitmap. error_number: %d", error_number);
        return jniEnv->NewGlobalRef(nullptr);
    }
    //返回
    jclass returnClazz = jniEnv->FindClass("com/gainscha/jzint/BitmapResult");
    jmethodID returnClazzConstructor = jniEnv->GetMethodID(returnClazz, "<init>", "()V");
    jobject returnObj = jniEnv->NewObject(returnClazz, returnClazzConstructor);
    jniEnv->SetIntField(returnObj, jniEnv->GetFieldID(returnClazz, "width", "I"),
                        zint->bitmap_width);
    jniEnv->SetIntField(returnObj, jniEnv->GetFieldID(returnClazz, "height", "I"),
                        zint->bitmap_height);
    int pixelsSize = zint->bitmap_width * zint->bitmap_height * 3;
    jbyteArray pixels = jniEnv->NewByteArray(pixelsSize);
    jniEnv->SetByteArrayRegion(pixels, 0, pixelsSize,
                               reinterpret_cast<const jbyte *>(zint->bitmap));
    jniEnv->SetObjectField(returnObj, jniEnv->GetFieldID(returnClazz, "pixels", "[B"), pixels);
    ZBarcode_Delete(zint);

    return returnObj;
}


/**
 * 根据条码像素创建Bitmap对象
 * */
jobject create_bitmap(JNIEnv *jniEnv, struct zint_symbol *my_symbol) {
    int width = my_symbol->bitmap_width;
    int height = my_symbol->bitmap_height;
    jclass bitmapConfig = jniEnv->FindClass("android/graphics/Bitmap$Config");
    jfieldID rgba8888FieldID = jniEnv->GetStaticFieldID(bitmapConfig, "ARGB_8888",
                                                        "Landroid/graphics/Bitmap/Config;");
    jobject rgba8888Obj = jniEnv->GetStaticObjectField(bitmapConfig, rgba8888FieldID);

    jclass bitmapClass = jniEnv->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethodID = jniEnv->GetStaticMethodID(bitmapClass, "createBitmap",
                                                               "(IILandroid/graphics/Bitmap/Config;)Landroid/graphics/Bitmap;");
    jobject bitmapObj = jniEnv->CallStaticObjectMethod(bitmapClass, createBitmapMethodID, width,
                                                       height, rgba8888Obj);

    jint pixel_length = width * height;
    jint pixels[pixel_length];
    jintArray jpixels = jniEnv->NewIntArray(pixel_length);
    int row, col, i = 0, pixel_index = 0;
    int red, blue, green;
    for (row = 0; row < height; row++) {
        for (col = 0; col < width; col++) {
            red = (int) my_symbol->bitmap[i];
            green = (int) my_symbol->bitmap[i + 1];
            blue = (int) my_symbol->bitmap[i + 2];
            int currentPixel = (0xFF << 24) | ((red << 16) | (green << 8) | (blue));
            pixels[pixel_index++] = currentPixel;
            i += 3;
        }
    }
    jniEnv->SetIntArrayRegion(jpixels, 0, pixel_length, pixels);
    jmethodID setPixelsMid = jniEnv->GetMethodID(bitmapClass, "setPixels", "([IIIIIII)V");
    jniEnv->CallVoidMethod(bitmapObj, setPixelsMid, jpixels, 0, width, 0, 0, width, height);
    return bitmapObj;
}
}

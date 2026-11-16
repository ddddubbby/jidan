#include <jni.h>
#include "android/log.h"
#include <string>
#include <cstring>
#include "FreeImage.h"

extern "C"
void threshold_pixels(int32_t *pixels, int width, int height, float brightness, float gamma, bool dither) {
    FIBITMAP *fib;
    FIBITMAP *fib_n;
    BYTE *fbits;
    BOOL ret;
    int index;
    FreeImage_Initialise(true);
    fib = FreeImage_Allocate(width, height, 32, 0,0,0);
    fbits = FreeImage_GetBits(fib);
    memcpy(fbits, pixels, 4*width * height);
    ret = FreeImage_AdjustBrightness(fib, brightness);
    FreeImage_AdjustGamma(fib, gamma);
    if(!ret) {
        printf("error adjust brightness\n");
    }
    if(dither) {
        fib_n = FreeImage_Dither(fib, FID_FS);
        if (fib_n) {
            FreeImage_Unload(fib);
            fib = fib_n;
        }
    }
    fib_n = FreeImage_Threshold(fib, 127);
    if (fib_n) {
        FreeImage_Unload(fib);
        fib = fib_n;
    }
    fib_n = FreeImage_ConvertTo8Bits(fib);
    if (fib_n) {
        FreeImage_Unload(fib);
        fib = fib_n;
    }
    for(int i=0;i<height;i++) {
        fbits = FreeImage_GetScanLine(fib, i);
        for(int j=0;j<width;j++) {
            index = i * width + j;
            if(fbits[j] > 0) {
                pixels[index] = 0xFFFFFF;
            }else {
                pixels[index] = 0;
            }
        }
    }
    FreeImage_Unload(fib);
    FreeImage_DeInitialise();
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_gainscha_fimage_FImage_threshold(JNIEnv *env, jclass clazz, jobject bitmap, jfloat brightness, jfloat gamma, jboolean dither) {
    jboolean  isCopy;
    jclass bitmapClz = env->GetObjectClass(bitmap);
    jclass configClz = env->FindClass("android/graphics/Bitmap$Config");
    jmethodID getPixels = env->GetMethodID(bitmapClz, "getPixels", "([IIIIIII)V");
    jmethodID setPixels = env->GetMethodID(bitmapClz, "setPixels", "([IIIIIII)V");
    jmethodID createBitmap = env->GetStaticMethodID(bitmapClz, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jmethodID getWidth = env->GetMethodID(bitmapClz, "getWidth", "()I");
    jmethodID getHeight = env->GetMethodID(bitmapClz, "getHeight", "()I");
    jobject config = env->GetStaticObjectField(configClz, env->GetStaticFieldID(configClz, "RGB_565", "Landroid/graphics/Bitmap$Config;"));
    int width = env->CallIntMethod(bitmap, getWidth);
    int height = env->CallIntMethod(bitmap, getHeight);
    int pixels_size = width * height;
    jintArray pixels = env->NewIntArray(pixels_size);
    env->CallVoidMethod(bitmap, getPixels, pixels,0, width, 0,0,width,height);
    jint *pixels_ptr = env->GetIntArrayElements(pixels, &isCopy);
    threshold_pixels(pixels_ptr, width, height, brightness, gamma, dither);
    env->SetIntArrayRegion(pixels, 0, pixels_size, pixels_ptr);
    jobject bmp = env->CallStaticObjectMethod(bitmapClz, createBitmap, width,height,config);
    env->CallVoidMethod(bmp, setPixels, pixels, 0, width, 0,0, width, height);
    env->ReleaseIntArrayElements(pixels, pixels_ptr, 0);
    return bmp;
}
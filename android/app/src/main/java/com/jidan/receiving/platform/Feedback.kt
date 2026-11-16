package com.jidan.receiving.platform

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * 提示音与震动：只用于「记上了」和「出问题了」（v7 4.4-3）。
 * 尊重系统「减少动态效果」时仍播提示音，仅跳过非必要震动拉长。
 */
object Feedback {
    fun ok(context: Context) {
        tone(ToneGenerator.TONE_PROP_ACK, 70)
        vibrate(context, 30)
    }

    fun warn(context: Context) {
        tone(ToneGenerator.TONE_PROP_NACK, 120)
        vibrate(context, if (reducedMotion(context)) 30 else 60)
    }

    fun reducedMotion(context: Context): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (_: Throwable) {
            false
        }
    }

    private fun tone(type: Int, ms: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tg.startTone(type, ms)
        } catch (_: Throwable) {
            // 模拟器无音频时忽略
        }
    }

    private fun vibrate(context: Context, ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Throwable) {
        }
    }
}

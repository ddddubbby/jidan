package com.jidan.receiving

import android.content.Context
import com.jidan.receiving.data.Prefs
import com.jidan.receiving.device.LabelPrinter
import com.jidan.receiving.device.Scale
import com.jidan.receiving.device.ScaleUnit
import com.jidan.receiving.device.fake.FakePrinter
import com.jidan.receiving.device.fake.FakeScale
import kotlinx.coroutines.CoroutineScope

/**
 * 模拟版：始终 Fake，不看 bootstrap 型号（开发规格 9.3）。
 * 另挂出 [fakeScale]/[fakePrinter] 给模拟台。
 */
object DeviceFactory {
    const val variantName = "模拟版"
    const val isSim = true

    lateinit var fakeScale: FakeScale
        private set
    lateinit var fakePrinter: FakePrinter
        private set

    /** 模拟秤直接按斤给读数，不看 [unit] */
    fun createScale(context: Context, scope: CoroutineScope, prefs: Prefs, scaleModel: String?, unit: () -> ScaleUnit): Scale {
        val fake = FakeScale(scope, prefs.pairedScaleId ?: FakeScale.MINE_ID)
        fakeScale = fake
        return fake
    }

    fun createPrinter(context: Context, scope: CoroutineScope, prefs: Prefs, printerModel: String?): LabelPrinter {
        val fake = FakePrinter(scope, prefs.pairedPrinterId ?: FakePrinter.MINE_ID)
        fakePrinter = fake
        return fake
    }
}

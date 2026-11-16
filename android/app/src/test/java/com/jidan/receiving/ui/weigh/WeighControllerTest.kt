package com.jidan.receiving.ui.weigh

import com.jidan.receiving.core.Kg
import com.jidan.receiving.core.Pick
import com.jidan.receiving.core.Session
import com.jidan.receiving.core.Tare
import com.jidan.receiving.core.WeighOutcome
import com.jidan.receiving.core.WeighPhase
import com.jidan.receiving.data.AppOptions
import com.jidan.receiving.data.OrderHeader
import com.jidan.receiving.data.TareDto
import com.jidan.receiving.device.LinkStatus
import com.jidan.receiving.device.Scale
import com.jidan.receiving.device.fake.FakeScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private class FakeHost(
    var options: AppOptions = AppOptions.defaults(),
    var specW0: Map<Long, Double> = mapOf(3L to 45.0),
) : WeighHost {
    var failure: Throwable? = null
    var bootstrapCalls = 0
    var batchCalls = 0

    override suspend fun loadBootstrap(): BootstrapSnapshot {
        bootstrapCalls++
        failure?.let { throw it }
        return BootstrapSnapshot(options, specW0)
    }

    override suspend fun nextBatchNo(): String {
        batchCalls++
        return "SG20261116-$batchCalls"
    }

    override suspend fun tares(): List<TareDto> = listOf(TareDto(1, "2号箱", 2.0))

    override var lastTareId: Long? = null
}

/** 设数字皮时按指令抛异常（如蓝石“秤上已有称重皮，设数字皮失败”），其余照 [inner]。 */
private class FailingTareScale(val inner: FakeScale) : Scale by inner {
    var failWith: Throwable? = IllegalStateException("秤上已有称重皮，设数字皮失败")

    override suspend fun setDigitalTare(kg: Kg) {
        failWith?.let { throw it }
        inner.setDigitalTare(kg)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WeighControllerTest {
    private val tare2 = Tare(Kg.of(2.0), "2号箱")

    private fun draft(tare: Tare? = tare2, stdNet: Kg? = Kg.of(45.0)) = Session(
        batchNo = "SG20261116-1",
        farmer = Pick(1, "李四"), product = Pick(2, "粉蛋"), spec = Pick(3, "360枚/箱"), warehouse = Pick(4, "一号库"),
        tare = tare,
        stdNet = stdNet,
    )

    private fun header() = OrderHeader(
        farmer = Pick(1, "李四"), product = Pick(2, "粉蛋"), spec = Pick(3, "360枚/箱"),
        stdNetCents = 4500, warehouse = Pick(4, "一号库"),
    )

    private fun TestScope.controller(scale: Scale, host: FakeHost): WeighController {
        lateinit var ctl: WeighController
        ctl = WeighController(
            scope = backgroundScope,
            scale = scale,
            host = host,
            isLocked = { false },
            onChange = {},
            onOutcome = { o ->
                // 与 ReceivingViewModel.applyOutcome 一样把状态机的结果记进流水
                ctl.session = ctl.session!!.apply(o)
            },
            now = { currentTime },
        )
        return ctl
    }

    private suspend fun TestScope.connect(scale: FakeScale) {
        val job = launch { scale.connect(FakeScale.MINE_ID) }
        advanceTimeBy(FakeScale.CONNECT_MS + 1)
        runCurrent()
        job.join()
    }

    private fun TestScope.settle() {
        advanceTimeBy(FakeScale.UNSTABLE_MS + 100)
        runCurrent()
    }

    private fun WeighController.nets() = session!!.boxes.map { it.net.cents }

    // ---- 1. 皮重重新下发 ----

    @Test
    fun restoredDraftSendsTareToANewScaleBeforeRecording() = runTest {
        // 草稿里有 2kg 皮重，进程被回收后换了一个新的秤实例（没有皮重）
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val ctl = controller(scale, FakeHost())
        assertTrue(ctl.start(draft(), OrderHeader()))
        runCurrent()

        scale.placeOneBox(4650) // 台面 48.5kg = 46.5 净重 + 2kg 箱皮
        settle()
        assertEquals(listOf(4650), ctl.nets()) // 不是 4850
        assertEquals("", ctl.tareError)
    }

    @Test
    fun scaleLostAndBackSendsTareAgain() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val ctl = controller(scale, FakeHost())
        assertTrue(ctl.start(draft(), OrderHeader()))
        runCurrent()

        // 模拟台“断开”再“恢复”：秤没有皮重了，APP 要重发
        scale.reachable = false
        runCurrent()
        assertTrue(ctl.scaleLost)
        scale.reachable = true
        connect(scale)
        runCurrent()
        assertFalse(ctl.scaleLost)

        scale.placeOneBox(4650)
        settle()
        assertEquals(listOf(4650), ctl.nets())
    }

    @Test
    fun tareChosenWhileScaleOffIsSentWhenItConnects() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val ctl = controller(scale, FakeHost())
        assertTrue(ctl.start(draft(), OrderHeader()))
        runCurrent()
        connect(scale) // 开始称重时秤还没连上，连上时才下发

        scale.placeOneBox(4650)
        settle()
        assertEquals(listOf(4650), ctl.nets())
    }

    @Test
    fun tareByAppSendsNothing() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val opts = AppOptions.from(mapOf("scale.tare-by" to "app"))
        val ctl = controller(scale, FakeHost(options = opts))
        assertTrue(ctl.start(draft(), OrderHeader()))
        runCurrent()

        // 没下发皮重：读数是台面总重 48.5，APP 自己扣 2.0
        scale.placeOneBox(4650)
        settle()
        assertEquals(listOf(4650), ctl.nets())
        assertEquals(4850, scale.readings.value!!.raw.cents)
    }

    // ---- 2. 下发失败 ----

    @Test
    fun tareFailureKeepsLockedAndDoesNotRecord() = runTest {
        val fake = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val scale = FailingTareScale(fake)
        connect(fake)
        val ctl = controller(scale, FakeHost())
        assertTrue(ctl.start(draft(), OrderHeader()))
        runCurrent()

        assertEquals("秤上已有称重皮，设数字皮失败", ctl.tareError)
        assertNull(ctl.machine!!.tare)
        assertEquals(WeighPhase.LOCKED, ctl.machine!!.phase)

        fake.placeOneBox(4650)
        settle()
        assertEquals(emptyList(), ctl.nets())

        // 界面状态里带着失败原因，皮重行仍显示已选的那只皮重，按钮据此直达重新设皮重
        val ui = com.jidan.receiving.ui.weigh.WeighUiState().withScaleSide(ctl, scaleConnected = true)
        assertEquals("秤上已有称重皮，设数字皮失败", ui.tareError)
        assertEquals(WeighPhase.LOCKED, ui.phase)
        assertEquals(tare2, ui.tare)

        // 排除原因后点“重新设皮重”：下发成功，称重放行
        scale.failWith = null
        ctl.retryTare()
        runCurrent()
        assertEquals("", ctl.tareError)
        assertEquals(tare2, ctl.machine!!.tare)
        settle()
        // 箱子一直在秤上：设好皮重后按已扣皮的读数记一次，不会记成毛重
        assertEquals(listOf(4650), ctl.nets())
        fake.removeLoad()
        settle()
        fake.placeOneBox(4650)
        settle()
        assertEquals(listOf(4650, 4650), ctl.nets())
    }

    @Test
    fun failureWithoutMessageStillShowsAReason() = runTest {
        val fake = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val scale = FailingTareScale(fake)
        connect(fake)
        val ctl = controller(scale, FakeHost())
        scale.failWith = RuntimeException("")
        assertTrue(ctl.start(draft(), OrderHeader()))
        runCurrent()
        assertEquals("秤没有接受皮重", ctl.tareError)
    }

    @Test
    fun choosingTareFailsThenRetries() = runTest {
        val fake = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val scale = FailingTareScale(fake)
        scale.failWith = null
        connect(fake)
        val host = FakeHost()
        val ctl = controller(scale, host)
        assertTrue(ctl.start(draft(tare = null), OrderHeader()))
        runCurrent()
        assertNull(ctl.machine!!.tare)
        assertEquals("", ctl.tareError) // 还没选皮重，不算失败

        scale.failWith = IllegalStateException("秤没有接受皮重")
        ctl.chooseTare(tare2, 1)
        assertEquals("秤没有接受皮重", ctl.tareError)
        assertEquals(tare2, ctl.session!!.tare) // 选的皮重记进草稿，重发或恢复时用
        assertEquals(1L, host.lastTareId)
        assertNull(ctl.machine!!.tare)

        scale.failWith = null
        ctl.retryTare()
        assertEquals("", ctl.tareError)
        assertEquals(tare2, ctl.machine!!.tare)
    }

    // ---- 3. W0 ----

    @Test
    fun w0FromDraftDecidesBoxCount() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val ctl = controller(scale, FakeHost(specW0 = mapOf(3L to 36.0)))
        // 草稿里的 W0 是 45：以它为准，bootstrap 里的 36 不用
        assertTrue(ctl.start(draft(stdNet = Kg.of(45.0)), OrderHeader()))
        runCurrent()

        scale.placeBoxes(listOf(4600, 4600, 4600)) // 台面 144kg，每箱皮 2kg
        settle()
        assertEquals(listOf(4600, 4600, 4600), ctl.nets())
        assertEquals(Kg.of(45.0), ctl.session!!.stdNet)
    }

    @Test
    fun oldDraftWithoutW0TakesItFromBootstrapSpec() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val ctl = controller(scale, FakeHost(specW0 = mapOf(3L to 45.0)))
        assertTrue(ctl.start(draft(stdNet = null), OrderHeader()))
        runCurrent()
        assertEquals(Kg.of(45.0), ctl.session!!.stdNet) // 补上，下次存草稿就有了

        scale.placeBoxes(listOf(4600, 4600, 4600))
        settle()
        assertEquals(listOf(4600, 4600, 4600), ctl.nets())
    }

    @Test
    fun noW0AnywhereRefusesToWeighInsteadOfGuessing() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val ctl = controller(scale, FakeHost(specW0 = emptyMap()))
        assertFalse(ctl.start(draft(stdNet = null), OrderHeader()))
        assertTrue("标准净含量" in ctl.startError, ctl.startError)
        assertNull(ctl.machine)
        assertNull(ctl.session)
    }

    @Test
    fun newOrderWritesW0IntoTheDraft() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val host = FakeHost()
        val ctl = controller(scale, host)
        assertTrue(ctl.start(null, header()))
        runCurrent()
        assertEquals(Kg.of(45.0), ctl.session!!.stdNet)
        assertEquals(Kg.of(45.0), Session.fromJson(ctl.session!!.toJson()).stdNet)
        assertEquals("SG20261116-1", ctl.session!!.batchNo)
    }

    @Test
    fun newOrderTakesW0FromBootstrapWhenHeaderHasNone() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val ctl = controller(scale, FakeHost(specW0 = mapOf(3L to 42.0)))
        assertTrue(ctl.start(null, header().copy(stdNetCents = null)))
        assertEquals(Kg.of(42.0), ctl.session!!.stdNet)
    }

    // ---- 4. bootstrap ----

    @Test
    fun bootstrapFailureBlocksWeighingUntilRetrySucceeds() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val host = FakeHost()
        host.failure = RuntimeException("连不上")
        val ctl = controller(scale, host)

        assertFalse(ctl.start(draft(), OrderHeader()))
        assertTrue(ctl.startError.isNotBlank())
        assertNull(ctl.machine)
        assertNull(ctl.session)
        scale.placeBoxes(listOf(4600))
        settle()
        assertNull(ctl.session) // 没有状态机，什么也不记
        val ui = WeighUiState().withScaleSide(ctl, scaleConnected = true)
        assertFalse(ui.ready)

        host.failure = null
        assertTrue(ctl.start(draft(), OrderHeader()))
        assertEquals("", ctl.startError)
        assertEquals(2, host.bootstrapCalls)
        runCurrent()
        scale.removeLoad()
        settle()
        scale.placeOneBox(4650)
        settle()
        assertEquals(listOf(4650), ctl.nets())
    }

    @Test
    fun newOrderBootstrapFailureDoesNotTakeABatchNumber() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val host = FakeHost()
        host.failure = RuntimeException("连不上")
        val ctl = controller(scale, host)
        assertFalse(ctl.start(null, header()))
        assertEquals(0, host.batchCalls)
        assertNull(ctl.session)
    }

    @Test
    fun restoredMachineUsesTheOptionsBootstrapJustDelivered() = runTest {
        // 现场把 scale.reading-is-net 改成 false：这台秤读数是台面总重（用模拟秤的“读数未扣皮”开关）
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        scale.anomalyReadingNotNet = true
        connect(scale)
        val opts = AppOptions.from(mapOf("scale.reading-is-net" to "false"))
        val ctl = controller(scale, FakeHost(options = opts))
        assertTrue(ctl.start(draft(), OrderHeader()))
        runCurrent()
        assertFalse(ctl.options.readingIsNet)

        scale.placeOneBox(4650)
        settle()
        assertEquals(4850, scale.readings.value!!.raw.cents)
        assertEquals(listOf(4650), ctl.nets()) // 按 false 换算：读数就是台面 48.5，减皮 2.0；按默认值 true 会多加一份皮重
    }

    @Test
    fun optionsAreFixedForTheOrderOnceStarted() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connect(scale)
        val host = FakeHost()
        val ctl = controller(scale, host)
        assertTrue(ctl.start(draft(), OrderHeader()))
        // 之后 bootstrap 变了，这一单不变（下一次建单才生效）
        host.options = AppOptions.from(mapOf("scale.reading-is-net" to "false"))
        runCurrent()
        assertTrue(ctl.options.readingIsNet)
        assertNotNull(ctl.machine)
    }
}

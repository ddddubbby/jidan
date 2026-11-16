package com.jidan.receiving.device

import com.jidan.receiving.device.fake.FakePrinter
import com.jidan.receiving.device.fake.FakeScale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LinkKeeperTest {
    @Test
    fun autoReconnectAfterLostWhenReachableAgain() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val printer = FakePrinter(backgroundScope, FakePrinter.MINE_ID)
        var scaleReach = true
        val keeper = LinkKeeper(
            scale = scale,
            printer = printer,
            scope = backgroundScope,
            isScaleReachable = { scaleReach },
            isPrinterReachable = { true },
            persistScaleId = {},
            persistPrinterId = {},
        )
        val connect = launch { scale.connect(FakeScale.MINE_ID) }
        advanceTimeBy(FakeScale.CONNECT_MS + 1)
        runCurrent()
        connect.join()
        assertEquals(LinkStatus.CONNECTED, scale.linkStatus.value)

        scaleReach = false
        scale.reachable = false
        assertEquals(LinkStatus.LOST, scale.linkStatus.value)

        scaleReach = true
        scale.reachable = true
        keeper.start()
        advanceTimeBy(LinkKeeper.RECONNECT_MS + FakeScale.CONNECT_MS + 200)
        runCurrent()
        assertEquals(LinkStatus.CONNECTED, scale.linkStatus.value)
        keeper.stop()
    }
}

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

    /** 蓝牙没开 / 没授权时登录不去连；条件满足后补连，APP-01。 */
    @Test
    fun connectsRememberedDevicesOnlyOnceBluetoothIsReady() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val printer = FakePrinter(backgroundScope, FakePrinter.MINE_ID)
        var ready = false
        val keeper = LinkKeeper(
            scale = scale,
            printer = printer,
            scope = backgroundScope,
            isScaleReachable = { true },
            isPrinterReachable = { true },
            persistScaleId = {},
            persistPrinterId = {},
            canConnect = { ready },
        )
        keeper.start()
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(LinkStatus.DISCONNECTED, scale.linkStatus.value)
        assertEquals(LinkStatus.DISCONNECTED, printer.linkStatus.value)

        ready = true
        advanceTimeBy(2 * FakeScale.CONNECT_MS + 1000)
        runCurrent()
        assertEquals(LinkStatus.CONNECTED, scale.linkStatus.value)
        assertEquals(LinkStatus.CONNECTED, printer.linkStatus.value)
        keeper.stop()
    }
}

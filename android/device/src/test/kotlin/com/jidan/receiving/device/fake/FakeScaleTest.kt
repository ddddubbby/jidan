package com.jidan.receiving.device.fake

import com.jidan.receiving.device.LinkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FakeScaleTest {
    @Test
    fun connectThenLoadSettles() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val connect = launch { scale.connect(FakeScale.MINE_ID) }
        advanceTimeBy(FakeScale.CONNECT_MS + 1)
        runCurrent()
        connect.join()
        assertEquals(LinkStatus.CONNECTED, scale.linkStatus.value)

        scale.placeOneBox(4650)
        runCurrent()
        assertNotNull(scale.readings.value)
        assertFalse(scale.readings.value!!.stable)
        advanceTimeBy(FakeScale.UNSTABLE_MS + 10)
        advanceUntilIdle()
        assertTrue(scale.readings.value!!.stable)
        assertEquals(4650 + FakeScale.BOX_TARE_C, scale.readings.value!!.raw.cents)
    }

    @Test
    fun disconnectSetsLostWhenUnreachable() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        val connect = launch { scale.connect(FakeScale.MINE_ID) }
        advanceTimeBy(FakeScale.CONNECT_MS + 1)
        runCurrent()
        connect.join()
        scale.reachable = false
        assertEquals(LinkStatus.LOST, scale.linkStatus.value)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.connected(scale: FakeScale) {
        val connect = launch { scale.connect(FakeScale.MINE_ID) }
        advanceTimeBy(FakeScale.CONNECT_MS + 1)
        runCurrent()
        connect.join()
    }

    private fun kotlinx.coroutines.test.TestScope.settle(scale: FakeScale): Int {
        advanceTimeBy(FakeScale.UNSTABLE_MS + 10)
        advanceUntilIdle()
        return scale.readings.value!!.raw.cents
    }

    @Test
    fun lostThenReconnectedHasNoDigitalTareUntilSentAgain() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connected(scale)
        scale.setDigitalTare(com.jidan.receiving.core.Jin(200))
        scale.placeOneBox(4650)
        assertEquals(4650, settle(scale)) // 台面 48.5，已扣皮 2.0

        scale.reachable = false
        assertEquals(LinkStatus.LOST, scale.linkStatus.value)
        scale.reachable = true
        connected(scale)
        scale.placeOneBox(4650)
        assertEquals(4850, settle(scale)) // 断开再连上：皮重没了，读数是台面总重

        scale.setDigitalTare(com.jidan.receiving.core.Jin(200))
        assertEquals(4650, scale.readings.value!!.raw.cents)
    }

    @Test
    fun disconnectClearsDigitalTare() = runTest {
        val scale = FakeScale(backgroundScope, FakeScale.MINE_ID)
        connected(scale)
        scale.setDigitalTare(com.jidan.receiving.core.Jin(200))
        scale.disconnect()
        assertEquals(LinkStatus.DISCONNECTED, scale.linkStatus.value)
        connected(scale)
        scale.placeOneBox(4650)
        assertEquals(4850, settle(scale))
    }
}

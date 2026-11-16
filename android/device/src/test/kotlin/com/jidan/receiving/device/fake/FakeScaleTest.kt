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
}

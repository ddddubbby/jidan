package com.jidan.receiving.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothGateTest {
    @Test
    fun android12AndUpNeedsScanAndConnect() {
        for (api in listOf(31, 33, 35)) {
            assertEquals(
                listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
                BluetoothGate.requiredPermissions(api),
            )
        }
    }

    @Test
    fun beforeAndroid12NeedsLocationOnly() {
        for (api in listOf(26, 28, 30)) {
            assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BluetoothGate.requiredPermissions(api))
        }
    }

    @Test
    fun readyNeedsBothSwitchAndPermission() {
        assertTrue(GateState(bluetoothOn = true, permitted = true).ready)
        assertFalse(GateState(bluetoothOn = false, permitted = true).ready)
        assertFalse(GateState(bluetoothOn = true, permitted = false).ready)
    }
}
